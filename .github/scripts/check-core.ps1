param([ValidateSet('stable', 'nightly', 'custom')][string]$Channel = 'custom')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/core-source.ps1"
if ($Channel -eq 'custom') {
    $runs = & gh api 'repos/225284228a-droid/EasyTier-Custom/actions/workflows/build.yml/runs?branch=main&status=success&per_page=1' | ConvertFrom-Json
    if ($LASTEXITCODE -or !$runs.workflow_runs.Count) { throw 'Cannot find a successful upstream build' }
    $commit = $runs.workflow_runs[0].head_sha
    $reference = [string]$runs.workflow_runs[0].id
} else {
    $endpoint = if ($Channel -eq 'stable') { 'latest' } else { 'tags/nightly' }
    $release = & gh api "repos/EasyTier/EasyTier/releases/$endpoint" | ConvertFrom-Json
    if ($LASTEXITCODE -or $release.draft) { throw 'Cannot find the official release' }
    $reference = $release.tag_name
    if ($Channel -eq 'stable') {
        $source = & gh api "repos/EasyTier/EasyTier/commits/$reference" | ConvertFrom-Json
        if ($LASTEXITCODE) { throw 'Cannot resolve stable release source' }
        $commit = $source.sha
    } else {
        $commit = [regex]::Match($release.body, 'Commit:\s*\[?([0-9a-f]{40})').Groups[1].Value
        if (!$commit) { $commit = $release.target_commitish }
    }
    if (!($release.assets | Where-Object name -Match '^easytier-linux-x86_64-.*\.zip$')) { throw 'Official Linux reference peer is unavailable' }
}
if ($commit -notmatch '^[0-9a-f]{40}$') { throw 'Invalid upstream commit' }
$revision = Get-CoreAdapterRevision $Channel
$remote = & gh api "repos/$($env:GITHUB_REPOSITORY)/contents/updates/$Channel.json?ref=main&check=$($env:GITHUB_RUN_ID)" | ConvertFrom-Json
if ($LASTEXITCODE) { throw 'Cannot read the current published catalog' }
$envelope = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($remote.content)) | ConvertFrom-Json
$catalog = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($envelope.payload)) | ConvertFrom-Json
$current = $catalog.releases[0]
$needed = $env:FORCE_CORE -eq 'true' -or $current.upstreamCommit -ne $commit -or $current.adapterRevision -ne $revision
$adapterBuild = switch ($Channel) { custom { 6 } nightly { 5 } stable { 4 } }
if ($Channel -eq 'stable' -and $needed) {
    $coreVersion = $reference -replace '^v', ''
    $tags = @($catalog.releases.version | ForEach-Object { "core-v$_" })
    $published = & gh api "repos/$($env:GITHUB_REPOSITORY)/releases?per_page=100" | ConvertFrom-Json
    if ($LASTEXITCODE) { throw 'Cannot identify the next stable adapter build' }
    $tags += @($published.tag_name)
    $pattern = '^core-v' + [regex]::Escape($coreVersion) + '-md(\d+)$'
    foreach ($tag in $tags) {
        $match = [regex]::Match($tag, $pattern)
        if ($match.Success) { $adapterBuild = [Math]::Max($adapterBuild, [int]$match.Groups[1].Value + 1) }
    }
}
"needed=$($needed.ToString().ToLowerInvariant())" >> $env:GITHUB_OUTPUT
"commit=$commit" >> $env:GITHUB_OUTPUT
"reference=$reference" >> $env:GITHUB_OUTPUT
"revision=$revision" >> $env:GITHUB_OUTPUT
"adapter_build=$adapterBuild" >> $env:GITHUB_OUTPUT
"$Channel upstream $commit, build needed = $needed" >> $env:GITHUB_STEP_SUMMARY
