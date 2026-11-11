use std::sync::{Arc, LazyLock, Mutex};

use easytier::{
    common::config::{ConfigFileControl, ConfigLoader, TomlConfigLoader},
    instance_manager::NetworkInstanceManager,
};
use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};
use serde_json::{Value, json};
mod remote;

static MANAGER: LazyLock<Arc<NetworkInstanceManager>> =
    LazyLock::new(|| Arc::new(NetworkInstanceManager::new()));
static OPERATIONS: Mutex<()> = Mutex::new(());
static ACTIVE: Mutex<Option<uuid::Uuid>> = Mutex::new(None);
static CLOUD: Mutex<Option<easytier::web_client::WebClient>> = Mutex::new(None);
static GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
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

fn config(raw: &str) -> anyhow::Result<TomlConfigLoader> {
    let cfg = TomlConfigLoader::new_from_str(raw)
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
        return Ok(json!({"core": easytier::VERSION, "api": 1, "adapter_build": 2}));
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
        ))?;
        *CLOUD.lock().unwrap() = Some(cloud);
        return Ok(json!({}));
    }
    if method == "cloud" {
        let mut profiles = Vec::new();
        for id in MANAGER.list_network_instance_ids() {
            if MANAGER.get_instance_config_control(&id).is_some_and(|c| c.is_no_delete()) { continue; }
            if let Some(service) = MANAGER.get_instance_service(&id) {
                let response = remote::RUNTIME.block_on(service.get_config_service().get_config(
                    easytier::proto::rpc_types::controller::BaseController::default(),
                    easytier::proto::api::config::GetConfigRequest::default(),
                ))?;
                if let Some(cfg) = response.config {
                    let cfg = cfg.gen_config()?;
                    profiles.push(json!({"id": id.to_string(), "name": cfg.get_inst_name(), "toml": cfg.dump()}));
                }
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
            if let Some(old) = old { if old != id { manager.delete_network_instance(vec![old])?; } }
            if !manager.list_network_instance_ids().contains(&id) {
                let control = if method == "startCloudProfile" {
                    ConfigFileControl::new(None, Default::default())
                } else { ConfigFileControl::STATIC_CONFIG };
                manager.run_network_instance(cfg, false, control)?;
            }
            *ACTIVE.lock().unwrap() = Some(id);
            Ok(json!({}))
        }
        "stop" => {
            CLOUD.lock().unwrap().take();
            manager.retain_network_instance(Vec::new())?;
            *ACTIVE.lock().unwrap() = None;
            Ok(json!({}))
        }
        "tun" => {
            let id = *ACTIVE.lock().unwrap();
            let id = id
                .ok_or_else(|| anyhow::anyhow!("VPN 尚未启动"))?;
            // Java retains the original descriptor. The Rust TUN owns this duplicate.
            let original: i32 = payload.parse()?;
            let fd = unsafe { libc::dup(original) };
            anyhow::ensure!(fd >= 0, "无法复制 VPN 文件描述符");
            if let Err(error) = manager.set_tun_fd(&id, fd) {
                unsafe { libc::close(fd); }
                return Err(error);
            }
            Ok(json!({}))
        }
        "snapshot" => {
            let infos = manager.collect_network_infos_sync()?;
            let active = *ACTIVE.lock().unwrap();
            let Some(info) = active.as_ref().and_then(|id| infos.get(id)) else {
                return Ok(json!({"running": false, "nodes": [], "routes": []}));
            };
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
