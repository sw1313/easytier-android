param(
    [ValidateSet('arm64-v8a', 'x86_64')][string]$Abi = 'arm64-v8a',
    [string]$Version = '2.6.4-md2',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist'),
    [Parameter(Mandatory)][string]$SigningDirectory
)
$ErrorActionPreference = 'Stop'
$library = Join-Path $PSScriptRoot "app/src/main/jniLibs/$Abi/libeasytier_mobile.so"
if (!(Test-Path -LiteralPath $library)) { throw 'Build the native core first' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$manifest = [ordered]@{
    api = 1
    version = $Version
    abi = $Abi
    sha256 = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json -Compress
$manifestBytes = [Text.Encoding]::UTF8.GetBytes($manifest)
$rsa = [Security.Cryptography.RSA]::Create()
$rsa.ImportFromPem([IO.File]::ReadAllText((Join-Path $SigningDirectory 'core-signing-private.pem')))
$signature = $rsa.SignData($manifestBytes, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
$rsa.Dispose()
$destination = Join-Path $OutputDirectory "EasyTier-core-$Version-$Abi.zip"
$temporary = Join-Path $OutputDirectory ([guid]::NewGuid().ToString() + '.tmp')
$archive = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Create)
try {
    $entry = $archive.CreateEntry('manifest.json')
    $stream = $entry.Open()
    try { $stream.Write($manifestBytes) } finally { $stream.Dispose() }
    $entry = $archive.CreateEntry('manifest.sig')
    $stream = $entry.Open()
    try { $stream.Write($signature) } finally { $stream.Dispose() }
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $library, 'libeasytier_mobile.so') | Out-Null
} finally { $archive.Dispose() }
[IO.File]::Move($temporary, $destination, $true)
Write-Output $destination
