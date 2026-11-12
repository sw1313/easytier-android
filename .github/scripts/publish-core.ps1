$ErrorActionPreference = 'Stop'
if ($env:CORE_COMMIT -notmatch '^[0-9a-f]{40}$' -or $env:ADAPTER_REVISION -notmatch '^[0-9a-f]{64}$') {
    throw 'Invalid source identity'
}
if (!$env:CORE_SIGNING_PRIVATE_KEY) { throw 'CORE_SIGNING_PRIVATE_KEY is missing' }
$signing = Join-Path $env:RUNNER_TEMP 'core-signing'
New-Item -ItemType Directory -Path $signing -Force | Out-Null
$privateFile = Join-Path $signing 'core-signing-private.pem'
try {
    [IO.File]::WriteAllText($privateFile, $env:CORE_SIGNING_PRIVATE_KEY)
    Remove-Item Env:CORE_SIGNING_PRIVATE_KEY
    $rsa = [Security.Cryptography.RSA]::Create()
    try {
        $rsa.ImportFromPem([IO.File]::ReadAllText($privateFile))
        [xml]$keyXml = Get-Content app/src/main/res/values/core_key.xml -Raw
        if ([Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()) -ne [string]$keyXml.resources.string.'#text') {
            throw 'Signing key does not match the application trust key'
        }
        [IO.File]::WriteAllText((Join-Path $signing 'core-signing-public.pem'), $rsa.ExportSubjectPublicKeyInfoPem())
    } finally { $rsa.Dispose() }

    git -C custom/core fetch --depth=1 origin $env:CORE_COMMIT
    if ($LASTEXITCODE) { throw 'Source fetch failed' }
    git -C custom/core checkout --detach FETCH_HEAD
    if ($LASTEXITCODE) { throw 'Source checkout failed' }
    git -C custom/core submodule update --init --recursive
    if ($LASTEXITCODE) { throw 'Source submodule checkout failed' }
    $sourceVersion = [regex]::Match([IO.File]::ReadAllText('custom/core/easytier/Cargo.toml'), '(?m)^version\s*=\s*"(\d+\.\d+\.\d+)[^"]*"').Groups[1].Value
    if (!$sourceVersion) { throw 'Cannot identify upstream core version' }
    $version = "$sourceVersion-custom.$([DateTime]::UtcNow.ToString('yyyyMMdd')).$($env:CORE_COMMIT.Substring(0, 8)).ci$($env:GITHUB_RUN_NUMBER).$($env:GITHUB_RUN_ATTEMPT)-md6"
    $lockHashes = @()
    $packages = foreach ($entry in @(
        @{ Architecture = 'aarch64'; Abi = 'arm64-v8a' },
        @{ Architecture = 'armv7'; Abi = 'armeabi-v7a' },
        @{ Architecture = 'x86_64'; Abi = 'x86_64' }
    )) {
        $artifact = "ci-artifacts/jni-custom-$($entry.Architecture)"
        $info = Get-Content "$artifact/build-info.json" -Raw | ConvertFrom-Json
        $library = "$artifact/libeasytier_mobile.so"
        if ($info.api -ne 1 -or $info.channel -ne 'custom' -or $info.abi -ne $entry.Abi -or $info.upstreamCommit -ne $env:CORE_COMMIT -or $info.adapterCommit -ne $env:GITHUB_SHA) {
            throw 'Build artifact source or ABI does not match this workflow'
        }
        if ((Get-FileHash $library -Algorithm SHA256).Hash.ToLowerInvariant() -ne $info.sha256) { throw 'Build artifact hash mismatch' }
        $lockHash = (Get-FileHash "$artifact/Cargo.lock" -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($lockHash -ne $info.lockSha256) { throw 'Build dependency lock hash mismatch' }
        $lockHashes += $lockHash
        $headers = & readelf --program-headers --wide $library
        if ($LASTEXITCODE) { throw 'ELF inspection failed' }
        $loads = @($headers | Where-Object { $_ -match '^\s+LOAD\s' })
        if (!$loads.Count) { throw 'ELF has no load segments' }
        foreach ($load in $loads) {
            $alignment = ($load.Trim() -split '\s+')[-1]
            if ([Convert]::ToUInt64($alignment.Substring(2), 16) -lt 16384) { throw 'ELF page alignment is below 16 KB' }
        }
        $destination = "dist/custom-libs/$($entry.Abi)"
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        Copy-Item -LiteralPath $library -Destination "$destination/libeasytier_mobile.so"
        ./package-core.ps1 -Channel custom -Abi $entry.Abi -Version $version -SigningDirectory $signing
    }
    if (@($lockHashes | Select-Object -Unique).Count -ne 1) { throw 'Architectures were built with different dependency locks' }
    Copy-Item ci-artifacts/jni-custom-aarch64/Cargo.lock custom/Cargo.lock

    $source = "dist/EasyTier-Android-$version-source.zip"
    python3 .github/scripts/archive-source.py $source "easytier-android-$version/"
    if ($LASTEXITCODE) { throw 'Corresponding source archive failed' }
    $notes = "dist/core-$version.md"
    @"
# Custom Android JNI $version

Upstream source $($env:CORE_COMMIT)

Adapter source $($env:GITHUB_SHA)

Built for ARM64, ARMv7 and x86_64. JNI API 1, Android 8.0 or later, application 1.2.10 or later.

Passed Android emulator JNI checks, MTU defaults, real VPN connect/disconnect, manual-start regression and exit-node GitHub update checks.

Download and apply the core in the Custom update channel, then connect manually.

[Build and test results](https://github.com/$($env:GITHUB_REPOSITORY)/actions/runs/$($env:GITHUB_RUN_ID))
"@ | Set-Content -LiteralPath $notes
    $tag = "core-v$version"
    & gh release create $tag --draft --prerelease --target $env:GITHUB_SHA --title "Custom core $version" --notes-file $notes
    if ($LASTEXITCODE) { throw 'Release creation failed' }
    & gh release upload $tag @packages $source
    if ($LASTEXITCODE) { throw 'Release asset upload failed' }
    & gh release edit $tag --draft=false --prerelease --latest=false
    if ($LASTEXITCODE) { throw 'Release publication failed' }

    $published = $false
    foreach ($attempt in 1..3) {
        $current = & gh api "repos/$($env:GITHUB_REPOSITORY)/contents/updates/custom.json?ref=main" | ConvertFrom-Json
        if ($LASTEXITCODE) { throw 'Current update catalog lookup failed' }
        $previous = Join-Path $env:RUNNER_TEMP 'previous-custom.json'
        [IO.File]::WriteAllBytes($previous, [Convert]::FromBase64String($current.content))
        ./publish-catalog.ps1 -Channel custom -Version $version -CoreVersion $sourceVersion -UpstreamCommit $env:CORE_COMMIT -AdapterRevision $env:ADAPTER_REVISION -MinAppVersionCode 13 -HistoryLimit 20 -PreviousCatalog $previous -NotesFile $notes -SigningDirectory $signing -Repository $env:GITHUB_REPOSITORY
        $request = Join-Path $env:RUNNER_TEMP 'catalog-request.json'
        @{
            message = "Publish verified Custom core $version"
            content = [Convert]::ToBase64String([IO.File]::ReadAllBytes('dist/custom.json'))
            sha = $current.sha
            branch = 'main'
        } | ConvertTo-Json | Set-Content -LiteralPath $request
        & gh api --method PUT "repos/$($env:GITHUB_REPOSITORY)/contents/updates/custom.json" --input $request --silent
        if ($LASTEXITCODE -eq 0) { $published = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$published) { throw 'Release uploaded but signed update catalog could not be committed' }
    "Published [$version](https://github.com/$($env:GITHUB_REPOSITORY)/releases/tag/$tag)" >> $env:GITHUB_STEP_SUMMARY
} finally {
    if (Test-Path -LiteralPath $privateFile) { Remove-Item -LiteralPath $privateFile }
    Remove-Item Env:CORE_SIGNING_PRIVATE_KEY -ErrorAction SilentlyContinue
}
