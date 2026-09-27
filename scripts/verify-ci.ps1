# ==============================================================================
# Payment Ledger Platform - Master Local CI Quality Gate Runner (PowerShell)
# ==============================================================================
[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [switch]$SkipMavenTest
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
    $RepoRoot = (Get-Item $scriptDir).Parent.FullName
} else {
    $scriptDir = Join-Path $RepoRoot "scripts"
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "    PAYMENT LEDGER PLATFORM - LOCAL CI QUALITY GATE RUNNER (PHASE 18)           " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

# Gate 1: Check .env
Write-Host "--> Gate 1: Checking for uncommitted .env files..." -ForegroundColor Yellow
if (Test-Path (Join-Path $RepoRoot ".env")) {
    Write-Host "[FAIL] .env file detected in repository root. Remove before committing." -ForegroundColor Red
    exit 1
}
Write-Host "[PASS] No .env file present." -ForegroundColor Green

# Gate 2: Secret scanning
Write-Host "--> Gate 2: Running secret scanning..." -ForegroundColor Yellow
& "$scriptDir\scan-secrets.ps1" -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Gate 3: Migration validation
Write-Host "--> Gate 3: Validating Flyway migrations..." -ForegroundColor Yellow
& "$scriptDir\validate-migrations.ps1" -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Gate 4: Production configuration validation
Write-Host "--> Gate 4: Validating configuration and container hardening..." -ForegroundColor Yellow
& "$scriptDir\validate-configs.ps1" -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Gate 5: Reproducible build verification
Write-Host "--> Gate 5: Checking reproducible build configuration..." -ForegroundColor Yellow
& "$scriptDir\verify-reproducibility.ps1" -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Gate 6: Maven compile and full verification suite
if (-not $SkipMavenTest) {
    Write-Host "--> Gate 6: Synchronizing container clock and executing Maven verify..." -ForegroundColor Yellow
    if (Get-Command "wsl" -ErrorAction SilentlyContinue) {
        $syncScript = Join-Path $scriptDir "sync-wsl-clock.ps1"
        if (Test-Path $syncScript) {
            & $syncScript
        }
    }
    $mvnw = Join-Path $RepoRoot "mvnw.cmd"
    & $mvnw -B verify
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "================================================================================" -ForegroundColor Green
Write-Host "    ALL CI QUALITY GATES PASSED (100 PERCENT) - READY FOR PUSH / PR             " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Green
exit 0
