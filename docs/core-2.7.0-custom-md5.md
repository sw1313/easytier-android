# EasyTier Custom Android 适配构建 5

基于 EasyTier-Custom 成功构建对应的提交 `7a4b2b105b3da373ce24ebb629c04be767fcab7b`，提供 ARM64、ARMv7 与 x86_64 的 API 1 签名独立核心包。[上游源码](https://github.com/225284228a-droid/EasyTier-Custom/tree/7a4b2b105b3da373ce24ebb629c04be767fcab7b)

- 适配 Custom 的实例管理、远程 RPC 和配置服务器接口。
- 支持 HTTP3、WSS、并行连接及 Custom P2P 传输策略。
- 1.2.8 可视化配置增加 Custom 打洞与传输选项，包括 P2P 传输策略、并行连接数、流量风格和 SNI。
- Android VPN 服务持有 TUN 文件描述符，核心停止后释放接口使用。
- 支持命名 WireGuard 门户客户端与已有门户配置转换。

应用最低版本为 1.2.8。在“设置 → 核心更新 → Custom”下载并应用核心。此后 API 1 兼容包可独立更新，应用按当前架构选择最新可下载的签名兼容包。设置页同时显示上游最新成功构建的提交。

Custom 专属传输需要对端支持相应协议。实际公网打洞结果取决于两端 NAT、网络策略和可用协议。

对应源码包含 `custom/core`、`custom/native`、独立锁文件及共享的 `nightly/native/src` JNI 实现。
