param([ValidateSet('aarch64', 'x86_64')][string]$Architecture = 'aarch64')
$ErrorActionPreference = 'Stop'
$env:PATH = "$env:USERPROFILE\.cargo\bin;" + $env:PATH
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$ndkBin = Join-Path $sdk 'ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin'
$target = "$Architecture-linux-android"
$targetKey = $target.Replace('-', '_')
$abi = if ($Architecture -eq 'aarch64') { 'arm64-v8a' } else { 'x86_64' }
[Environment]::SetEnvironmentVariable("CARGO_TARGET_$($targetKey.ToUpper())_LINKER", "$ndkBin\$($target)26-clang.cmd", 'Process')
[Environment]::SetEnvironmentVariable("CC_$targetKey", "$ndkBin\$($target)26-clang.cmd", 'Process')
[Environment]::SetEnvironmentVariable("CXX_$targetKey", "$ndkBin\$($target)26-clang++.cmd", 'Process')
[Environment]::SetEnvironmentVariable("AR_$targetKey", "$ndkBin\llvm-ar.exe", 'Process')
$flags = @('-C', 'link-arg=-Wl,-z,max-page-size=16384')
$mappings = @(
    @($PSScriptRoot, '/workspace'),
    @((Join-Path $env:USERPROFILE '.cargo'), '/cargo'),
    @((Join-Path $env:USERPROFILE '.rustup'), '/rustup'),
    @($env:USERPROFILE, '/home/builder')
)
foreach ($mapping in $mappings) {
    $flags += '--remap-path-prefix=' + $mapping[0] + '=' + $mapping[1]
    $flags += '--remap-path-prefix=' + $mapping[0].Replace('\', '/') + '=' + $mapping[1]
}
$env:CARGO_ENCODED_RUSTFLAGS = $flags -join [char]31
$cFlags = $mappings | ForEach-Object { '"-ffile-prefix-map=' + $_[0].Replace('\', '/') + '=' + $_[1] + '"' }
$env:CC_SHELL_ESCAPED_FLAGS = '1'
[Environment]::SetEnvironmentVariable("CFLAGS_$targetKey", ($cFlags -join ' '), 'Process')
[Environment]::SetEnvironmentVariable("CXXFLAGS_$targetKey", ($cFlags -join ' '), 'Process')
$env:LIBCLANG_PATH = $ndkBin
$env:PATH = "$ndkBin;" + $env:PATH
$protoc = Join-Path $PSScriptRoot '.build-tools/protoc/bin/protoc.exe'
if (!(Test-Path $protoc) -and !(Get-Command protoc.exe -ErrorAction SilentlyContinue) -and !$env:PROTOC) {
    $cache = Join-Path $PSScriptRoot '.build-tools'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    Invoke-WebRequest -Uri 'https://github.com/protocolbuffers/protobuf/releases/download/v29.3/protoc-29.3-win64.zip' -OutFile (Join-Path $cache 'protoc.zip')
    Expand-Archive -LiteralPath (Join-Path $cache 'protoc.zip') -DestinationPath (Join-Path $cache 'protoc') -Force
}
if (Test-Path $protoc) { $env:PROTOC = (Resolve-Path $protoc).Path }
$env:CARGO_PROFILE_RELEASE_LTO = 'thin'
$env:CARGO_PROFILE_RELEASE_CODEGEN_UNITS = '16'
Push-Location $PSScriptRoot
try {
    & cargo +1.95.0 build -p easytier-material-native --target $target --release
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
    $destination = Join-Path $PSScriptRoot "app/src/main/jniLibs/$abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath "target/$target/release/libeasytier_mobile.so" -Destination $destination
} finally { Pop-Location }
