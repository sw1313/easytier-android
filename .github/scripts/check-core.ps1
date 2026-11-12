$ErrorActionPreference = 'Stop'
$runs = & gh api 'repos/225284228a-droid/EasyTier-Custom/actions/workflows/build.yml/runs?branch=main&status=success&per_page=1' | ConvertFrom-Json
if ($LASTEXITCODE -or !$runs.workflow_runs.Count) { throw 'Cannot find a successful upstream build' }
$upstream = $runs.workflow_runs[0]
if ($upstream.head_sha -notmatch '^[0-9a-f]{40}$') { throw 'Invalid upstream commit' }
$trees = & git rev-parse HEAD:nightly/native HEAD:custom/native HEAD:custom/Cargo.toml HEAD:custom/Cargo.lock HEAD:build-native.ps1
if ($LASTEXITCODE) { throw 'Cannot identify adapter revision' }
$revision = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($trees -join "`n"))).ToLowerInvariant()
$envelope = Get-Content updates/custom.json -Raw | ConvertFrom-Json
$catalog = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($envelope.payload)) | ConvertFrom-Json
$current = $catalog.releases[0]
$needed = $env:FORCE_CORE -eq 'true' -or $current.upstreamCommit -ne $upstream.head_sha -or $current.adapterRevision -ne $revision
"needed=$($needed.ToString().ToLowerInvariant())" >> $env:GITHUB_OUTPUT
"commit=$($upstream.head_sha)" >> $env:GITHUB_OUTPUT
"upstream_run=$($upstream.id)" >> $env:GITHUB_OUTPUT
"revision=$revision" >> $env:GITHUB_OUTPUT
"Upstream $($upstream.head_sha), build needed = $needed" >> $env:GITHUB_STEP_SUMMARY
