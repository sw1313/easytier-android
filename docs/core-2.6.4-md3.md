# EasyTier 2.6.4 · Android 适配构建 3

- 修复断开后 TUN 文件描述符副本未释放、系统 VPN 仍连接的问题。
- 按官方移动端接口由 Java 持有 TUN 描述符，Rust 核心借用该描述符。
- 提供 ARM64、ARMv7 和 x86_64 签名包，采用 16 KB 页对齐。
- 官方 EasyTier 核心版本为 2.6.4，JNI API 为 1。

## 实现依据

EasyTier 的移动端 TUN 使用 `close_fd_on_drop(false)`。官方 Android 示例由 `ParcelFileDescriptor` 持有并关闭接口，JNI 直接传递原描述符。[移动端 TUN 实现](https://github.com/EasyTier/EasyTier/blob/8428a89d2dabc94c97d370ec607c6ca142473626/easytier/src/instance/virtual_nic.rs#L597) · [官方 Android 示例](https://github.com/EasyTier/EasyTier/blob/8428a89d2dabc94c97d370ec607c6ca142473626/easytier-contrib/easytier-android-jni/kotlin/com/easytier/jni/EasyTierVpnService.t.kt#L89)

快捷磁贴使用主动模式，VPN 状态发布后通过 `requestListeningState()` 请求刷新，并声明 `TOGGLEABLE_TILE`。[Android 文档](https://developer.android.com/develop/ui/views/quicksettings-tiles)

对照的开源实现中，WireGuard 和 Tailscale 使用普通监听模式，并根据后端隧道或 VPN 运行状态设置 `STATE_ACTIVE` / `STATE_INACTIVE`。[WireGuard](https://github.com/WireGuard/wireguard-android/blob/master/ui/src/main/java/com/wireguard/android/QuickTileService.kt) · [Tailscale](https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/QuickToggleService.java)
