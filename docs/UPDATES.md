# 核心更新

## 版本显示

应用分别显示官方核心版本和 Android 适配构建号。

- `2.6.4` 表示官方 EasyTier 核心版本。
- 适配构建 `3` 表示 Android JNI 与打包层的构建编号。
- `2.6.4-md3` 是签名包内部标识。

官方版本提升时显示“下载核心更新”。同一官方版本的适配构建提升时显示“更新适配组件”。默认检查官方稳定版，兼容包准备状态会显示在应用中。

## APK 与核心更新

JNI 接口兼容、现有应用功能满足需求时，可以独立更新核心包，继续使用当前 APK。涉及 JNI 接口或应用侧新功能的升级，需要配套发布 APK。

应用自动检查官方发行信息与签名更新目录。官方发布新版后，维护者需要更新核心子模块，编译各架构的 Android JNI，签名并发布核心包和目录。应用随后下载、验证并重新启动加载。核心包的构建与发布目前由维护者执行。

## 更新源

应用启动时每隔至少 6 小时检查一次，也可以在设置页点击“检查更新”。

- 官方发布信息来自 `https://api.github.com/repos/EasyTier/EasyTier/releases/latest`。
- 签名目录来自本仓库 `updates/stable.json`。
- Android 核心包由本仓库 GitHub Releases 提供，支持 `arm64-v8a`、`armeabi-v7a` 和 `x86_64`。

更新包按当前应用进程的架构选择。在支持 32 位应用的 64 位设备上安装 ARMv7 APK 后，更新器会继续使用 ARMv7 核心。

下载流程校验目录签名、ZIP 哈希、内部 manifest 签名和动态库哈希。应用更新后重新启动，并恢复原有网络与出口节点设置。加载失败时尝试恢复上个可用核心。

应用升级时会比较已选择的独立核心与内置核心版本，优先采用较新的内置核心。1.2.4 内置适配构建 3，包含断开 VPN 后释放 TUN 接口的修复。

## 生成核心包

先编译三个架构的动态库，再用发布者本地的 RSA 签名目录生成包。

```powershell
./build-native.ps1 -Architecture aarch64
./build-native.ps1 -Architecture armv7
./build-native.ps1 -Architecture x86_64
./package-core.ps1 -Abi arm64-v8a -Version 2.6.4-md3 -SigningDirectory C:/Signing/EasyTier
./package-core.ps1 -Abi armeabi-v7a -Version 2.6.4-md3 -SigningDirectory C:/Signing/EasyTier
./package-core.ps1 -Abi x86_64 -Version 2.6.4-md3 -SigningDirectory C:/Signing/EasyTier
```

签名目录提供 PKCS#8 PEM 格式的 `core-signing-private.pem`。对应的公钥需要与应用内置校验公钥匹配。

## 发布目录

准备 Markdown 或文本更新说明，然后生成签名目录。

```powershell
./publish-catalog.ps1 -Version 2.6.4-md3 -CoreVersion 2.6.4 -NotesFile docs/core-2.6.4-md3.md -SigningDirectory C:/Signing/EasyTier
```

将 ZIP 包上传到 `core-v2.6.4-md3` Release，将生成的 `dist/stable.json` 保存为 `updates/stable.json` 并提交。发布新版本时应采用新的版本标识和资产文件名。

升级官方核心源码后需要编译、验证对应的 Android JNI 包。应用使用 JNI API 1，目录会按 API、应用版本和手机架构选择兼容包。
