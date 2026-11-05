param(
    [Parameter(Mandatory=$true)][string]$ServerPath,
    [string]$RuntimeJar = (Join-Path $PSScriptRoot 'server\plugins\CampfireCoop\runtime.jar')
)
$ErrorActionPreference = 'Stop'
$server = (Resolve-Path -LiteralPath $ServerPath).Path
$module = (Resolve-Path -LiteralPath $RuntimeJar).Path
$updates = Join-Path $server 'plugins\CampfireCoop\updates'
New-Item -ItemType Directory -Force -Path $updates | Out-Null
$pendingJar = Join-Path $updates 'update.jar'
$pendingHash = Join-Path $updates 'update.sha256'
Copy-Item -LiteralPath $module -Destination (Join-Path $updates 'update.jar.tmp') -Force
$checksum = (Get-FileHash -LiteralPath (Join-Path $updates 'update.jar.tmp') -Algorithm SHA256).Hash.ToLowerInvariant()
# Publish the checksum last, so the running loader never reads a half-copied module.
if (Test-Path -LiteralPath $pendingHash) { Remove-Item -LiteralPath $pendingHash -Force }
Move-Item -LiteralPath (Join-Path $updates 'update.jar.tmp') -Destination $pendingJar -Force
Set-Content -LiteralPath (Join-Path $updates 'update.sha256.tmp') -Value $checksum -Encoding ascii
Move-Item -LiteralPath (Join-Path $updates 'update.sha256.tmp') -Destination $pendingHash -Force
Write-Host 'Update staged. A running Campfire loader applies it within 5 seconds.' -ForegroundColor Green
Write-Host (Join-Path $updates 'result.properties')
