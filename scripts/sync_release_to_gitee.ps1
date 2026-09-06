# Syncs the freshly published GitHub release to the Gitee mirror so the
# in-app updater can use Gitee as its primary line on mainland networks.
# GitHub stays the source of truth: the tag, the artifact names and the
# SHA256SUMS.txt content are mirrored byte-for-byte (same local files).
#
# Required env:
#   GITEE_TOKEN - Gitee personal access token (projects scope)
#   GITEE_REPO  - "owner/name" of the Gitee mirror repo
#   TAG         - tag to publish (v<revision>, already exported by release.yml)
#   GH_TOKEN    - used by `gh` to read the GitHub release notes
#
# Idempotent: re-running skips an existing tag/release and already-attached
# assets. Non-blocking in CI: the workflow step runs with continue-on-error;
# this script exits 1 on any problem so the runner surfaces a warning.

param([string]$ReleaseDir = "release")

$ErrorActionPreference = 'Stop'
# PowerShell's progress rendering measurably slows large Invoke-RestMethod
# transfers; the CI log has no use for it either.
$ProgressPreference = 'SilentlyContinue'

$token = $env:GITEE_TOKEN
$repo = $env:GITEE_REPO
$tag = $env:TAG

if (-not $token) { Write-Warning "GITEE_TOKEN is not set - skipping Gitee sync"; exit 1 }
if (-not $repo) { Write-Warning "GITEE_REPO is not set - skipping Gitee sync"; exit 1 }
if (-not $tag) { Write-Warning "TAG is not set - skipping Gitee sync"; exit 1 }

$api = "https://gitee.com/api/v5/repos/$repo"
$rev = $tag.Substring(1)
$assets = @(
    "PPoEDialer-$rev-windows.zip",
    "PPoEDialer-$rev-windows.msi",
    "SHA256SUMS.txt"
)

# 1. Wait for the Gitee webhook to mirror the tag (it races this workflow).
$tagFound = $false
for ($i = 1; $i -le 24; $i++) {
    try {
        $tags = Invoke-RestMethod -Uri "$api/tags?access_token=$($token)&per_page=100" `
            -Method Get -TimeoutSec 60
        if ($tags -and @($tags | Where-Object { $_.name -eq $tag }).Count -gt 0) {
            $tagFound = $true
            break
        }
    } catch {
        Write-Warning "Gitee tag poll failed: $($_.Exception.Message)"
    }
    Write-Host "tag $tag not on Gitee yet (poll $i/24) - waiting for the webhook..."
    Start-Sleep -Seconds 15
}
if (-not $tagFound) {
    Write-Warning "tag $tag did not reach Gitee within 6 minutes"
    exit 1
}

# 2. Find the release by tag; create it when absent (idempotent on re-runs).
$release = $null
try {
    $release = Invoke-RestMethod -Uri "$api/releases/tags/$($tag)?access_token=$($token)" `
        -Method Get -TimeoutSec 60
} catch { }

if (-not $release) {
    $notes = ""
    try {
        $ghBody = gh release view $tag --json body -q .body 2>$null
        if ($LASTEXITCODE -eq 0 -and $ghBody) { $notes = [string]$ghBody }
    } catch { }
    $link = "GitHub Release: https://github.com/$env:GITHUB_REPOSITORY/releases/tag/$tag"
    $payload = @{
        tag_name         = $tag
        name             = "PPoEDialer $tag"
        body             = ($notes + "`n`n" + $link).Trim()
        target_commitish = "main"
        prerelease       = $false
    } | ConvertTo-Json
    Write-Host "Creating Gitee release $tag..."
    $release = Invoke-RestMethod -Uri "$api/releases?access_token=$($token)" `
        -Method Post -Body $payload -ContentType "application/json" -TimeoutSec 60
}

# 3. Attach the artifacts (Gitee caps a single file at 100 MB).
$existing = @()
if ($release.assets) { $existing = @($release.assets | ForEach-Object { $_.name }) }

$failed = $false
foreach ($name in $assets) {
    $path = Join-Path $ReleaseDir $name
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Warning "release asset missing: $path"
        $failed = $true
        continue
    }
    if ($existing -contains $name) {
        Write-Host "$name already attached - skipping"
        continue
    }
    $size = (Get-Item -LiteralPath $path).Length
    if ($size -gt 100MB) {
        Write-Warning "$name exceeds the Gitee 100MB per-file cap and was not uploaded"
        $failed = $true
        continue
    }
    Write-Host ("Uploading {0} ({1:N1} MB) - the cross-border link can take several minutes..." -f $name, ($size / 1MB))
    $uploaded = $false
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        # curl.exe streams multipart uploads with a hard ceiling; PowerShell's
        # Invoke-RestMethod -Form had no timeout and hung the whole job when
        # Gitee stalled mid-upload.
        $curlOut = curl.exe -sS --fail-with-body --connect-timeout 30 --max-time 1800 `
            -X POST "$api/releases/$($release.id)/attach_files?access_token=$($token)" `
            -F "file=@$path" 2>&1
        if ($LASTEXITCODE -eq 0) {
            $uploaded = $true
            break
        }
        Write-Warning "upload attempt $attempt for $name failed (curl exit $LASTEXITCODE): $curlOut"
        Start-Sleep -Seconds 5
    }
    if (-not $uploaded) { $failed = $true }
}

# 4. Verify the release now lists every artifact.
try {
    $check = Invoke-RestMethod -Uri "$api/releases/tags/$($tag)?access_token=$($token)" `
        -Method Get -TimeoutSec 60
    $names = @($check.assets | ForEach-Object { $_.name })
    foreach ($name in $assets) {
        if ($names -notcontains $name) {
            Write-Warning "Gitee release is missing asset: $name"
            $failed = $true
        }
    }
} catch {
    Write-Warning "could not re-read the Gitee release: $($_.Exception.Message)"
    $failed = $true
}

if ($failed) {
    Write-Warning "Gitee sync finished with problems - the updater will use the GitHub backup line until this is fixed"
    exit 1
}
Write-Host "Gitee release $tag is in sync (zip + msi + SHA256SUMS.txt)."
