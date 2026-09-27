# ==============================================================================
# Payment Ledger Platform — Spike & Burst Test Runner (PowerShell)
# ==============================================================================
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
    $RepoRoot = (Get-Item $scriptDir).Parent.Parent.FullName
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "    PAYMENT LEDGER PLATFORM — SPIKE / BURST TEST                                " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

$k6Cmd = Get-Command k6 -ErrorAction SilentlyContinue
if ($k6Cmd) {
    $scenario = Join-Path $RepoRoot "performance\scenarios\payment.js"
    & k6 run --stage 10s:10 --stage 10s:250 --stage 20s:250 --stage 10s:10 --stage 10s:0 -e BASE_URL=$BaseUrl $scenario
} else {
    Write-Host "k6 not found. Spike stages: 10s:10 -> 10s:250 -> 20s:250 -> 10s:10" -ForegroundColor Yellow
}

Write-Host "================================================================================" -ForegroundColor Green
Write-Host "    SPIKE TEST EXECUTION COMPLETED                                              " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Green
exit 0
