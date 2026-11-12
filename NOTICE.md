# 项目来源

本项目使用 EasyTier 官方 Rust 核心，固定基线为 v2.6.4，提交为 `8428a89d2dabc94c97d370ec607c6ca142473626`。官方源码通过 `core` 子模块引用。[EasyTier](https://github.com/EasyTier/EasyTier)

Nightly 使用 `nightly/core` 子模块，固定提交为 `2abb1676b475947e46c527b57acc5f296cb48482`，对应 JNI 适配位于 `nightly/native`。

Custom 使用 `custom/core` 子模块，固定提交为 `7a4b2b105b3da373ce24ebb629c04be767fcab7b`。`custom/native` 通过 Cargo feature 复用 `nightly/native/src` 中的 Android JNI 实现，使用独立锁文件。[EasyTier-Custom](https://github.com/225284228a-droid/EasyTier-Custom)

本仓库增加 Kotlin、Jetpack Compose Material 3、Android VPN 与快捷磁贴，以及 JNI 和签名更新流程。项目沿用 LGPL-3.0 许可证，完整文本位于根目录 `LICENSE`。
