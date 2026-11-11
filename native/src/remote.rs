use std::{sync::{Arc, LazyLock}, time::Duration};
use easytier::{
    common::{config::{ConfigLoader, TomlConfigLoader}, global_ctx::GlobalCtx},
    proto::{api::manage::*, rpc_impl::bidirect::BidirectRpcManager, rpc_types::controller::BaseController},
    tunnel::IpVersion,
};
use serde_json::{Value, json};

pub(super) static RUNTIME: LazyLock<tokio::runtime::Runtime> = LazyLock::new(||
    tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap());

pub fn call(payload: &str) -> anyhow::Result<Value> {
    let args: Value = serde_json::from_str(payload)?;
    RUNTIME.block_on(async {
        tokio::time::timeout(Duration::from_secs(20), execute(args)).await
            .map_err(|_| anyhow::anyhow!("远程管理请求超时"))?
    })
}

async fn execute(args: Value) -> anyhow::Result<Value> {
    let url = args["url"].as_str().ok_or_else(|| anyhow::anyhow!("请填写 RPC 地址"))?;
    let cfg = TomlConfigLoader::default();
    let mut flags = cfg.get_flags();
    flags.bind_device = false;
    cfg.set_flags(flags);
    let ctx = Arc::new(GlobalCtx::new(cfg));
    let mut connector = easytier::connector::create_connector_by_url(url, &ctx, IpVersion::Both).await?;
    let tunnel = connector.connect().await?;
    let rpc = BidirectRpcManager::new();
    rpc.run_with_tunnel(tunnel);
    let client = rpc.rpc_client().scoped_client::<WebClientServiceClientFactory<BaseController>>(1, 1, String::new());
    let ctrl = BaseController::default();
    let id = || -> anyhow::Result<uuid::Uuid> { Ok(args["id"].as_str().ok_or_else(|| anyhow::anyhow!("缺少实例 ID"))?.parse()?) };
    match args["action"].as_str().unwrap_or("list") {
        "list" => {
            let ids = client.list_network_instance(ctrl.clone(), ListNetworkInstanceRequest {}).await?.inst_ids;
            let metas = client.list_network_instance_meta(ctrl, ListNetworkInstanceMetaRequest { inst_ids: ids }).await?;
            Ok(json!({"instances": metas.metas.iter().map(|m| json!({
                "id": m.inst_id.clone().map(uuid::Uuid::from).map(|id| id.to_string()),
                "name": m.instance_name, "network": m.network_name, "permissions": m.config_permission,
            })).collect::<Vec<_>>()}))
        }
        "get" => {
            let response = client.get_network_instance_config(ctrl, GetNetworkInstanceConfigRequest { inst_id: Some(id()?.into()) }).await?;
            let raw = response.config.ok_or_else(|| anyhow::anyhow!("远端没有返回配置"))?.gen_config()?.dump();
            Ok(json!({"toml": raw}))
        }
        "run" => {
            let raw = args["toml"].as_str().ok_or_else(|| anyhow::anyhow!("缺少配置"))?;
            let cfg = super::config(raw)?;
            if let Some(id) = args["id"].as_str().filter(|id| !id.is_empty()) { cfg.set_id(id.parse()?); }
            let config = NetworkConfig::new_from_config(cfg)?;
            let response = client.run_network_instance(ctrl, RunNetworkInstanceRequest {
                inst_id: config.instance_id.as_ref().and_then(|id| id.parse::<uuid::Uuid>().ok()).map(Into::into),
                config: Some(config), overwrite: true, source: ConfigSource::User as i32,
            }).await?;
            Ok(json!({"id": response.inst_id.map(uuid::Uuid::from).map(|id| id.to_string())}))
        }
        "stop" => {
            client.delete_network_instance(ctrl, DeleteNetworkInstanceRequest { inst_ids: vec![id()?.into()] }).await?;
            Ok(json!({}))
        }
        "info" => {
            let result = client.collect_network_info(ctrl, CollectNetworkInfoRequest { inst_ids: vec![id()?.into()] }).await?;
            Ok(serde_json::to_value(result)?)
        }
        _ => anyhow::bail!("未知远程管理操作"),
    }
}
