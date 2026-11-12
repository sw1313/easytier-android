use std::sync::{Arc, LazyLock, Mutex};

use easytier::{
    common::config::{ConfigFileControl, ConfigLoader, TomlConfigLoader},
    instance::factory::{NativeInstanceManager, native_instance_manager_with_runtime, attach_mobile_tun_fd},
};
use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};
use serde_json::{Value, json};
mod remote;

static MANAGER: LazyLock<Arc<NativeInstanceManager>> =
    LazyLock::new(|| Arc::new(native_instance_manager_with_runtime(remote::RUNTIME.handle().clone())));
static OPERATIONS: Mutex<()> = Mutex::new(());
static ACTIVE: Mutex<Option<uuid::Uuid>> = Mutex::new(None);
static CLOUD: Mutex<Option<easytier::web_client::WebClient>> = Mutex::new(None);
static GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
#[cfg(feature = "custom")]
const UPSTREAM_COMMIT: &str = "7a4b2b105b3da373ce24ebb629c04be767fcab7b";
#[cfg(not(feature = "custom"))]
const UPSTREAM_COMMIT: &str = "2abb1676b475947e46c527b57acc5f296cb48482";
struct MobileHooks;
#[async_trait::async_trait]
impl easytier::web_client::WebClientHooks for MobileHooks {
    async fn pre_run_network_instance(&self, cfg: &TomlConfigLoader) -> Result<(), String> {
        let mut flags = cfg.get_flags();
        flags.bind_device = false;
        cfg.set_flags(flags);
        Ok(())
    }
    async fn post_run_network_instance(&self, _id: &uuid::Uuid) -> Result<(), String> {
        GENERATION.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        Ok(())
    }
}

fn document(raw: &str) -> anyhow::Result<toml::Value> {
    let mut doc: toml::Value = toml::from_str(raw)
        .map_err(|_| anyhow::anyhow!("TOML 配置格式无效，请检查配置文件"))?;
    if let Some(portal) = doc.get_mut("vpn_portal_config").and_then(toml::Value::as_table_mut) {
        if let Some(cidr) = portal.remove("client_cidr") {
            if portal.contains_key("clients") { return Ok(doc); }
            let network = cidr.as_str().unwrap_or_default().parse::<cidr::Ipv4Inet>()
                .map_err(|_| anyhow::anyhow!("WireGuard 门户客户端网段无效"))?.network();
            let address = u32::from(network.first_address()).checked_add(1)
                .filter(|value| *value < u32::from(network.last_address()))
                .ok_or_else(|| anyhow::anyhow!("WireGuard 门户网段需要可用的客户端地址"))?;
            let client = toml::Table::from_iter([
                ("name".into(), toml::Value::String("legacy-client".into())),
                ("virtual_ip".into(), toml::Value::String(format!("{}/{}", std::net::Ipv4Addr::from(address), network.network_length()))),
            ]);
            portal.insert("clients".into(), toml::Value::Array(vec![toml::Value::Table(client)]));
        }
    }
    Ok(doc)
}

fn config(raw: &str) -> anyhow::Result<TomlConfigLoader> {
    let raw = toml::to_string(&document(raw)?)?;
    let cfg = TomlConfigLoader::new_from_str(&raw)
        .map_err(|_| anyhow::anyhow!("TOML 配置格式无效，请检查配置文件"))?;
    anyhow::ensure!(cfg.get_flags().no_tun || cfg.get_ipv4().is_some() || cfg.get_dhcp(), "配置需要 IPv4 地址或 DHCP");
    Ok(cfg)
}

fn metadata(cfg: &TomlConfigLoader) -> Value {
    json!({
        "name": cfg.get_inst_name(),
        "network": cfg.get_network_identity().network_name,
        "hostname": cfg.get_hostname(),
        "ipv4": cfg.get_ipv4().map(|ip| ip.to_string()),
        "ipv6": cfg.get_ipv6().map(|ip| ip.to_string()),
        "exitNodes": cfg.get_exit_nodes().len(),
        "mtu": cfg.get_flags().mtu.clamp(576, 9000),
        "noTun": cfg.get_flags().no_tun,
        "routes": cfg.get_routes().unwrap_or_default().iter()
            .map(ToString::to_string).collect::<Vec<_>>(),
        "peers": cfg.get_peers().len(),
    })
}

fn dispatch(method: &str, payload: &str) -> anyhow::Result<Value> {
    if method == "version" {
        return Ok(json!({"core": easytier::VERSION, "api": 1,
            "adapter_build": if cfg!(feature = "custom") { 5 } else { 4 },
            "upstream_commit": UPSTREAM_COMMIT, "portal_clients": true,
            "custom": cfg!(feature = "custom")}));
    }
    if method == "remote" { return remote::call(payload); }
    if method == "cloudStart" {
        let args: Value = serde_json::from_str(payload)?;
        let cloud = remote::RUNTIME.block_on(easytier::web_client::run_web_client(
            args["url"].as_str().unwrap_or_default(),
            easytier::common::MachineIdOptions {
                explicit_machine_id: args["machineId"].as_str().map(str::to_owned), state_dir: None,
            }, Some("EasyTier MD Android".into()), args["secure"].as_bool().unwrap_or(true),
            MANAGER.clone(), Some(Arc::new(MobileHooks)),
            #[cfg(feature = "custom")]
            Arc::new(easytier_core::management::InstanceStateStore::in_memory()),
        ))?;
        *CLOUD.lock().unwrap() = Some(cloud);
        return Ok(json!({}));
    }
    if method == "cloud" {
        let mut profiles = Vec::new();
        for id in MANAGER.instance_ids() {
            if MANAGER.config_control(id).is_some_and(|c| c.is_no_delete()) { continue; }
            if let Some(cfg) = MANAGER.config(id) {
                profiles.push(json!({"id": id.to_string(), "name": cfg.get_inst_name(), "toml": cfg.dump()}));
            }
        }
        return Ok(json!({
            "connected": CLOUD.lock().unwrap().as_ref().is_some_and(|c| c.is_connected()),
            "profiles": profiles,
        }));
    }
    if method == "decode" {
        let doc: toml::Value = toml::from_str(payload)
            .map_err(|_| anyhow::anyhow!("TOML 配置格式无效"))?;
        return Ok(serde_json::to_value(doc)?);
    }
    if method == "encode" {
        let doc: toml::Value = serde_json::from_str(payload)?;
        let raw = toml::to_string_pretty(&doc)?;
        config(&raw)?;
        return Ok(json!({"toml": raw}));
    }
    if method == "inspect" {
        return Ok(metadata(&config(payload)?));
    }
    let _guard = OPERATIONS.lock().map_err(|_| anyhow::anyhow!("VPN 内核状态异常"))?;
    let manager = &*MANAGER;
    match method {
        "start" | "startCloudProfile" => {
            let cfg = config(payload)?;
            // Android's VpnService owns routing. Excluding our UID in its Builder
            // keeps transport sockets on the underlying network.
            let mut flags = cfg.get_flags();
            flags.bind_device = false;
            cfg.set_flags(flags);
            let id = cfg.get_id();
            let old = ACTIVE.lock().unwrap().take();
            if let Some(old) = old { if old != id { remote::RUNTIME.block_on(manager.delete_network_instances([old]))?; } }
            if !manager.instance_ids().contains(&id) {
                let control = if method == "startCloudProfile" {
                    ConfigFileControl::new(None, Default::default())
                } else { ConfigFileControl::STATIC_CONFIG };
                manager.run_network_instance(cfg, control)?;
            }
            *ACTIVE.lock().unwrap() = Some(id);
            Ok(json!({}))
        }
        "stop" => {
            CLOUD.lock().unwrap().take();
            remote::RUNTIME.block_on(manager.retain_network_instances(&[]))?;
            *ACTIVE.lock().unwrap() = None;
            Ok(json!({}))
        }
        "tun" => {
            let id = *ACTIVE.lock().unwrap();
            let id = id
                .ok_or_else(|| anyhow::anyhow!("VPN 尚未启动"))?;
            // EasyTier's mobile TUN borrows this fd with close_fd_on_drop(false).
            // Java owns it and closes it after the core stops, including on errors.
            let fd: i32 = payload.parse()?;
            anyhow::ensure!(fd > 0, "无效的 VPN 文件描述符");
            let instance = manager.instance(id).ok_or_else(|| anyhow::anyhow!("VPN 实例已停止"))?;
            remote::RUNTIME.block_on(async {
                tokio::time::timeout(std::time::Duration::from_secs(10), async {
                    while !instance.is_ready() {
                        if let Some(error) = instance.latest_error() { anyhow::bail!(error); }
                        anyhow::ensure!(instance.state() != easytier_core::instance::CoreInstanceState::Stopped, "VPN 实例已停止");
                        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
                    }
                    let attached = attach_mobile_tun_fd(manager, fd, Some(&[id])).await?;
                    anyhow::ensure!(attached.instance_ids.contains(&id), "VPN TUN 接入失败");
                    Ok::<(), anyhow::Error>(())
                }).await.map_err(|_| anyhow::anyhow!("等待 VPN TUN 接入超时"))?
            })?;
            Ok(json!({}))
        }
        "snapshot" => {
            let mut infos = manager.collect_network_infos_sync()?;
            let active = *ACTIVE.lock().unwrap();
            let Some(info) = active.as_ref().and_then(|id| infos.get_mut(id)) else {
                return Ok(json!({"running": false, "nodes": [], "routes": []}));
            };
            let mut portal_clients = Vec::new();
            if let Some(instance) = active.and_then(|id| manager.instance(id)) {
                let portal = remote::RUNTIME.block_on(instance.vpn_portal_info());
                portal_clients = portal.clients.iter().map(|client| json!({
                    "name": client.name, "config": client.client_config, "error": client.error,
                })).collect();
                if portal.clients.len() == 1 {
                    if let Some(node) = info.my_node_info.as_mut() {
                        node.vpn_portal_cfg = Some(portal.clients[0].client_config.clone());
                    }
                }
            }
            let mut rx = 0_u64;
            let mut tx = 0_u64;
            for peer in &info.peers {
                for conn in &peer.conns {
                    if let Some(stats) = &conn.stats {
                        rx = rx.saturating_add(stats.rx_bytes);
                        tx = tx.saturating_add(stats.tx_bytes);
                    }
                }
            }
            let nodes: Vec<Value> = info.routes.iter().map(|route| {
                let conn = info.peers.iter().find(|p| p.peer_id == route.peer_id)
                    .and_then(|p| p.conns.iter().find(|c| !c.is_closed));
                json!({
                    "id": route.peer_id,
                    "name": route.hostname,
                    "ip": route.ipv4_addr.as_ref().map(ToString::to_string).unwrap_or_default(),
                    "direct": conn.is_some(),
                    "latency": conn.and_then(|c| c.stats.as_ref()).map(|s| s.latency_us as f64 / 1000.0)
                        .unwrap_or(route.path_latency as f64),
                    "loss": conn.map(|c| c.loss_rate * 100.0),
                    "networks": route.proxy_cidrs,
                    "version": route.version,
                    "cost": route.cost,
                    "connections": conn.map(|c| json!({
                        "tunnel": c.tunnel, "rx": c.stats.as_ref().map(|s| s.rx_bytes),
                        "tx": c.stats.as_ref().map(|s| s.tx_bytes),
                    })),
                })
            }).collect();
            let mut routes: Vec<String> = info.routes.iter()
                .flat_map(|r| r.proxy_cidrs.clone()).collect();
            routes.extend(info.routes.iter().filter_map(|r| r.ipv6_addr.as_ref().map(ToString::to_string)));
            Ok(json!({
                "running": info.running,
                "error": info.error_msg,
                "ipv4": info.my_node_info.as_ref().and_then(|n| n.virtual_ipv4.as_ref()).map(ToString::to_string),
                "nodes": nodes, "routes": routes, "rx": rx, "tx": tx,
                "generation": GENERATION.load(std::sync::atomic::Ordering::Relaxed),
                "details": serde_json::to_value(info)?,
                "portal_clients": portal_clients,
            }))
        }
        _ => anyhow::bail!("未知内核操作"),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hfzy_easytier_NativeCore_call(
    mut env: JNIEnv, _class: JClass, method: JString, payload: JString,
) -> jstring {
    let result = (|| -> anyhow::Result<Value> {
        let method: String = env.get_string(&method)?.into();
        let payload: String = env.get_string(&payload)?.into();
        dispatch(&method, &payload)
    })();
    let response = match result {
        Ok(data) => json!({"ok": true, "data": data}),
        Err(error) => json!({"ok": false, "error": error.to_string()}),
    };
    env.new_string(response.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
