function Get-CoreWorkspace([string]$Channel) {
    if ($Channel -eq 'stable') { return '.' }
    return $Channel
}

function Get-CoreAdapterRevision([string]$Channel, [string]$Commit = 'HEAD') {
    $paths = switch ($Channel) {
        stable { @('native', 'Cargo.toml', 'Cargo.lock', 'build-native.ps1') }
        nightly { @('nightly/native', 'nightly/Cargo.toml', 'nightly/Cargo.lock', 'build-native.ps1') }
        custom { @('nightly/native', 'custom/native', 'custom/Cargo.toml', 'custom/Cargo.lock', 'build-native.ps1') }
        default { throw 'Unknown core channel' }
    }
    $references = $paths | ForEach-Object { "${Commit}:$_" }
    $trees = & git rev-parse @references
    if ($LASTEXITCODE) { throw 'Cannot identify adapter revision' }
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($trees -join "`n"))).ToLowerInvariant()
}
