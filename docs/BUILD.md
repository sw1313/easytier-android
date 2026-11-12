# 构建说明

## 环境

- Windows 与 PowerShell 7
- JDK 17
- Android SDK 35
- Android NDK 29.0.14206865
- Rust 1.95.0

Gradle Wrapper 使用 8.11.1。原生核心支持 ARM64、ARMv7 与 x86_64，最低 Android 8.0，目标 Android 15。

## 获取源码

```powershell
git clone --recurse-submodules https://github.com/sw1313/easytier-android.git
cd easytier-android
```

已有检出目录可初始化核心子模块。

```powershell
git submodule update --init --recursive
```

`core` 固定在官方 EasyTier v2.6.4。Rust JNI 代码位于 `native`，Android 应用位于 `app`。

Nightly 使用独立的 `nightly/core` 官方子模块，固定在 `2abb1676b475947e46c527b57acc5f296cb48482`。对应的 API 1 JNI 适配位于 `nightly/native`，有独立的 Cargo 锁文件。

## 编译

将本机 SDK 地址写入未纳入版本管理的 `local.properties`，或设置 `ANDROID_HOME`。

```properties
sdk.dir=C:/Android/Sdk
```

```powershell
rustup target add --toolchain 1.95.0 aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
./build-native.ps1 -Architecture aarch64
./gradlew.bat :app:assembleDebug -Pabi=arm64-v8a
```

32 位 ARMv7 构建使用 `armeabi-v7a`。

```powershell
./build-native.ps1 -Architecture armv7
./gradlew.bat :app:assembleRelease -Pabi=armeabi-v7a
```

64 位 x86 设备与模拟器构建使用 `x86_64`。

```powershell
./build-native.ps1 -Architecture x86_64
./gradlew.bat :app:assembleDebug -Pabi=x86_64
./gradlew.bat :app:assembleRelease -Pabi=x86_64
```

构建脚本会为原生动态库设置 16 KB 页对齐，并重映射编译路径。生成的 JNI 动态库放在 `app/src/main/jniLibs`。

Nightly 构建通过 `-Channel nightly` 选择，产物放在 `dist/nightly-libs/<ABI>`。

```powershell
./build-native.ps1 -Channel nightly -Architecture aarch64
./build-native.ps1 -Channel nightly -Architecture armv7
./build-native.ps1 -Channel nightly -Architecture x86_64
```

## 发布 APK

```powershell
./gradlew.bat :app:assembleRelease -Pabi=arm64-v8a
```

使用本地 keystore 为生成的未签名 APK 签名。应用 ID 为 `com.hfzy.easytier`，用于已有安装的覆盖升级兼容。发布包使用同一签名证书进行后续升级。

签名材料由发布者保存在仓库外的目录。核心校验公钥位于 `app/src/main/res/values/core_key.xml`，用于验证更新目录和核心包。
