# 核心更新

## 版本显示

应用分别显示官方核心版本和 Android 适配构建号。

- `2.6.4` 表示官方 EasyTier 核心版本。
- 适配构建 `2` 表示 Android JNI 与打包层的构建编号。
- `2.6.4-md2` 是签名包内部标识。

官方版本提升时显示“下载核心更新”。同一官方版本的适配构建提升时显示“更新适配组件”。默认检查官方稳定版，兼容包准备状态会显示在应用中。

## 更新源

应用启动时每隔至少 6 小时检查一次，也可以在设置页点击“检查更新”。

- 官方发布信息来自 `https://api.github.com/repos/EasyTier/EasyTier/releases/latest`。
- 签名目录来自本仓库 `updates/stable.json`。
- Android 核心包由本仓库 GitHub Releases 提供。

下载流程校验目录签名、ZIP 哈希、内部 manifest 签名和动态库哈希。应用更新后重新启动，并恢复原有网络与出口节点设置。加载失败时尝试恢复上个可用核心。

## 生成核心包

先编译两个架构的动态库，再用发布者本地的 RSA 签名目录生成包。

```powershell
./build-native.ps1 -Architecture aarch64
./build-native.ps1 -Architecture x86_64
./package-core.ps1 -Abi arm64-v8a -Version 2.6.4-md2 -SigningDirectory C:/Signing/EasyTier
./package-core.ps1 -Abi x86_64 -Version 2.6.4-md2 -SigningDirectory C:/Signing/EasyTier
```

签名目录提供 PKCS#8 PEM 格式的 `core-signing-private.pem`。对应的公钥需要与应用内置校验公钥匹配。

## 发布目录

准备 Markdown 或文本更新说明，然后生成签名目录。

```powershell
./publish-catalog.ps1 -Version 2.6.4-md2 -CoreVersion 2.6.4 -NotesFile docs/core-2.6.4-md2.md -SigningDirectory C:/Signing/EasyTier
```

将 ZIP 包上传到 `core-v2.6.4-md2` Release，将生成的 `dist/stable.json` 保存为 `updates/stable.json` 并提交。发布新版本时应采用新的版本标识和资产文件名。

升级官方核心源码后需要编译、验证对应的 Android JNI 包。应用使用 JNI API 1，目录会按 API、应用版本和手机架构选择兼容包。
