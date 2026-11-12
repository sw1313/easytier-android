param(
    [ValidateSet('stable', 'nightly', 'custom')][string]$Channel = 'custom',
    [ValidateSet('aarch64', 'armv7', 'x86_64')][string]$Architecture = 'aarch64',
    [string]$UpstreamRef = ''
)
$ErrorActionPreference = 'Stop'
$source = if ($Channel -eq 'stable') { 'core' } else { "$Channel/core" }
if ($UpstreamRef) {
    if ($UpstreamRef.StartsWith('-') -or $UpstreamRef -notmatch '^[A-Za-z0-9._/-]+$') {
        throw 'Invalid upstream reference'
    }
    & git -C $source fetch --depth=1 origin $UpstreamRef
    if ($LASTEXITCODE) { throw 'Upstream fetch failed' }
    & git -C $source checkout --detach FETCH_HEAD
    if ($LASTEXITCODE) { throw 'Upstream checkout failed' }
    & git -C $source submodule update --init --recursive
    if ($LASTEXITCODE) { throw 'Upstream submodules failed' }
}
$commit = (& git -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot identify upstream source' }
$env:EASYTIER_UPSTREAM_COMMIT = $commit
& ./build-native.ps1 -Channel $Channel -Architecture $Architecture
$abi = @{ aarch64 = 'arm64-v8a'; armv7 = 'armeabi-v7a'; x86_64 = 'x86_64' }[$Architecture]
$library = if ($Channel -eq 'stable') { "app/src/main/jniLibs/$abi/libeasytier_mobile.so" } else { "dist/$Channel-libs/$abi/libeasytier_mobile.so" }
$tools = "$env:ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
$exports = & "$tools/llvm-nm.exe" --dynamic --defined-only $library
if ($LASTEXITCODE -or !($exports -match 'Java_com_hfzy_easytier_NativeCore_call')) {
    throw 'JNI entry point is missing'
}
$destination = "ci-artifacts/$Channel/$Architecture"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Copy-Item -LiteralPath $library -Destination "$destination/libeasytier_mobile.so"
$workspace = if ($Channel -eq 'stable') { '.' } else { $Channel }
Copy-Item -LiteralPath "$workspace/Cargo.lock" -Destination "$destination/Cargo.lock"
& "$tools/llvm-readelf.exe" --file-header --program-headers $library > "$destination/elf.txt"
if ($LASTEXITCODE) { throw 'ELF inspection failed' }
[ordered]@{
    api = 1
    channel = $Channel
    abi = $abi
    upstreamCommit = $commit
    adapterCommit = (& git rev-parse HEAD).Trim()
    lockSha256 = (Get-FileHash -LiteralPath "$workspace/Cargo.lock" -Algorithm SHA256).Hash.ToLowerInvariant()
    sha256 = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json | Set-Content -LiteralPath "$destination/build-info.json" -Encoding utf8
