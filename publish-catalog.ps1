param(
    [string]$Version,
    [string]$CoreVersion,
    [string]$NotesFile,
    [string]$Directory = (Join-Path $PSScriptRoot 'dist'),
    [string]$BaseUrl = '',
    [string]$Repository = 'sw1313/easytier-android',
    [string]$SourceUrl = '',
    [Parameter(Mandatory)][string]$SigningDirectory,
    [ValidateSet('stable', 'nightly')][string]$Channel = 'stable',
    [string]$UpstreamCommit = '',
    [string]$PreviousCatalog = '',
    [switch]$Empty
)
$ErrorActionPreference = 'Stop'
$releases = @()
if (!$Empty) {
    if (!$Version -or !$CoreVersion -or !$NotesFile) { throw 'Version, CoreVersion, and NotesFile are required for a release' }
    if (($Channel -eq 'nightly') -ne $Version.Contains('-nightly.')) { throw 'Core version and channel must agree' }
    if ($Channel -eq 'nightly' -and $UpstreamCommit -notmatch '^[0-9a-f]{40}$') { throw 'Nightly requires the full official source commit' }
    if (!$BaseUrl) { $BaseUrl = "https://github.com/$Repository/releases/download/core-v$Version" }
    $assets = [ordered]@{}
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
        $name = "EasyTier-core-$Version-$abi.zip"
        $file = Join-Path $Directory $name
        if (!(Test-Path -LiteralPath $file)) { throw "Missing core package $name" }
        $assets[$abi] = [ordered]@{
            url = "$BaseUrl/$name"
            size = (Get-Item -LiteralPath $file).Length
            sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $release = [ordered]@{
        version = $Version
        coreVersion = $CoreVersion
        api = 1
        minAppVersionCode = if ($Channel -eq 'nightly') { 10 } else { 3 }
        minSdk = 26
        publishedAt = [DateTime]::UtcNow.ToString('o')
        notes = [IO.File]::ReadAllText((Resolve-Path $NotesFile))
        sourceUrl = $SourceUrl
        upstreamCommit = $UpstreamCommit
        assets = $assets
    }
    $releases = @($release)
    if (!$PreviousCatalog) { $PreviousCatalog = Join-Path $PSScriptRoot "updates/$Channel.json" }
    if (Test-Path -LiteralPath $PreviousCatalog) {
        $previous = Get-Content -LiteralPath $PreviousCatalog -Raw | ConvertFrom-Json
        $previousBytes = [Convert]::FromBase64String($previous.payload)
        $verifier = [Security.Cryptography.RSA]::Create()
        try {
            $verifier.ImportFromPem([IO.File]::ReadAllText((Join-Path $SigningDirectory 'core-signing-public.pem')))
            if (!$verifier.VerifyData($previousBytes, [Convert]::FromBase64String($previous.signature), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) {
                throw 'Previous catalog signature is invalid'
            }
        } finally { $verifier.Dispose() }
        $history = [Text.Encoding]::UTF8.GetString($previousBytes) | ConvertFrom-Json
        if ($history.schema -ne 1 -or $history.channel -ne $Channel) { throw 'Previous catalog channel or schema does not match' }
        $releases += @($history.releases | Where-Object version -NE $Version)
    }
}
$payload = [ordered]@{
    schema = 1
    channel = $Channel
    releases = $releases
} | ConvertTo-Json -Depth 10 -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($payload)
if ($bytes.Length -gt 256 * 1024) { throw 'Catalog exceeds the application size limit' }
$rsa = [Security.Cryptography.RSA]::Create()
try {
    $rsa.ImportFromPem([IO.File]::ReadAllText((Join-Path $SigningDirectory 'core-signing-private.pem')))
    $signature = $rsa.SignData($bytes, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
} finally { $rsa.Dispose() }
$catalog = [ordered]@{ payload = [Convert]::ToBase64String($bytes); signature = [Convert]::ToBase64String($signature) } | ConvertTo-Json -Compress
[IO.File]::WriteAllText((Join-Path $Directory "$Channel.json"), $catalog)
Write-Output (Join-Path $Directory "$Channel.json")
