# Custom Android 适配构建 6

基于 EasyTier-Custom 提交 `7a4b2b105b3da373ce24ebb629c04be767fcab7b`，支持 ARM64、ARMv7 和 x86_64。

- 增加 Android 原生套接字保护桥接。
- 配合应用 1.2.10，应用的更新请求遵循 VPN 路由，可通过所选出口节点检查并下载 GitHub 更新。
- 继续提供 JNI API 1，早期应用仍可加载，使用原有网络处理方式。
- 应用升级与核心切换后，通过概览或快捷磁贴手动连接 VPN。
