param(
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$CoreVersion,
    [Parameter(Mandatory)][string]$NotesFile,
    [string]$Directory = (Join-Path $PSScriptRoot 'dist'),
    [string]$BaseUrl = '',
    [string]$Repository = 'sw1313/easytier-android',
    [string]$SourceUrl = '',
    [Parameter(Mandatory)][string]$SigningDirectory
)
$ErrorActionPreference = 'Stop'
if (!$BaseUrl) { $BaseUrl = "https://github.com/$Repository/releases/download/core-v$Version" }
$assets = [ordered]@{}
foreach ($abi in @('arm64-v8a', 'x86_64')) {
    $name = "EasyTier-core-$Version-$abi.zip"
    $file = Join-Path $Directory $name
    if (!(Test-Path -LiteralPath $file)) { throw "Missing core package $name" }
    $assets[$abi] = [ordered]@{
        url = "$BaseUrl/$name"
        size = (Get-Item -LiteralPath $file).Length
        sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$payload = [ordered]@{
    schema = 1
    channel = 'stable'
    releases = @([ordered]@{
        version = $Version
        coreVersion = $CoreVersion
        api = 1
        minAppVersionCode = 3
        minSdk = 26
        publishedAt = [DateTime]::UtcNow.ToString('o')
        notes = [IO.File]::ReadAllText((Resolve-Path $NotesFile))
        sourceUrl = $SourceUrl
        assets = $assets
    })
} | ConvertTo-Json -Depth 10 -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($payload)
$rsa = [Security.Cryptography.RSA]::Create()
try {
    $rsa.ImportFromPem([IO.File]::ReadAllText((Join-Path $SigningDirectory 'core-signing-private.pem')))
    $signature = $rsa.SignData($bytes, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
} finally { $rsa.Dispose() }
$catalog = [ordered]@{ payload = [Convert]::ToBase64String($bytes); signature = [Convert]::ToBase64String($signature) } | ConvertTo-Json -Compress
[IO.File]::WriteAllText((Join-Path $Directory 'stable.json'), $catalog)
Write-Output (Join-Path $Directory 'stable.json')
