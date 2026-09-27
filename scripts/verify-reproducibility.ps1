# ==============================================================================
# Payment Ledger Platform - Reproducible Build Validator (PowerShell)
# ==============================================================================
[CmdletBinding()]
param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
    $RepoRoot = (Get-Item $scriptDir).Parent.FullName
}

$pomFile = Join-Path $RepoRoot "pom.xml"

Write-Host "==> Verifying Reproducible Build Configuration in: $pomFile" -ForegroundColor Cyan

if (-not (Test-Path $pomFile)) {
    Write-Host "ERROR: pom.xml not found" -ForegroundColor Red
    exit 1
}

$pomContent = Get-Content $pomFile -Raw
if ($pomContent -notmatch "<project\.build\.outputTimestamp>([^<]+)</project\.build\.outputTimestamp>") {
    Write-Host " [ERROR] pom.xml is missing <project.build.outputTimestamp>" -ForegroundColor Red
    exit 1
}

$timestamp = $Matches[1].Trim()
Write-Host " [OK] Found project.build.outputTimestamp: $timestamp" -ForegroundColor Green

if ($timestamp -notmatch "^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})$") {
    Write-Host " [ERROR] Invalid ISO-8601 timestamp format: $timestamp" -ForegroundColor Red
    exit 1
}

$jars = Get-ChildItem -Path (Join-Path $RepoRoot "target") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch "^original-" }
if ($jars) {
    foreach ($jar in $jars) {
        $hash = (Get-FileHash -Path $jar.FullName -Algorithm SHA256).Hash.ToLower()
        Write-Host " [SHA-256] $($jar.Name): $hash" -ForegroundColor Yellow
        $hashFile = "$($jar.FullName).sha256"
        "$hash  $($jar.Name)" | Set-Content -Path $hashFile -Encoding utf8
    }
}

Write-Host "==> Reproducible build configuration validation PASSED." -ForegroundColor Green
exit 0
