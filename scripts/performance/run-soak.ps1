# ==============================================================================
# Payment Ledger Platform — Sustained Soak Test Runner (PowerShell)
# ==============================================================================
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Duration = "10m",
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
    $RepoRoot = (Get-Item $scriptDir).Parent.Parent.FullName
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "    PAYMENT LEDGER PLATFORM — SOAK TEST (Duration: $Duration)                   " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

$k6Cmd = Get-Command k6 -ErrorAction SilentlyContinue
if ($k6Cmd) {
    $scenario = Join-Path $RepoRoot "performance\scenarios\payment.js"
    & k6 run --vus 25 --duration $Duration -e BASE_URL=$BaseUrl $scenario
} else {
    Write-Host "k6 not found. Scenario at performance\scenarios\payment.js" -ForegroundColor Yellow
}

Write-Host "================================================================================" -ForegroundColor Green
Write-Host "    SOAK TEST EXECUTION COMPLETED                                               " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Green
exit 0
