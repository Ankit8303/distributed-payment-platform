# ==============================================================================
# Payment Ledger Platform — Progressive Capacity Stepping Runner (PowerShell)
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
Write-Host "    PAYMENT LEDGER PLATFORM — PROGRESSIVE CAPACITY STEPPING                     " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

$steps = @(10, 25, 50, 100, 200, 400)
$k6Cmd = Get-Command k6 -ErrorAction SilentlyContinue

if ($k6Cmd) {
    foreach ($vu in $steps) {
        Write-Host "--> Testing Capacity Step: $vu Virtual Users..." -ForegroundColor Yellow
        $scenario = Join-Path $RepoRoot "performance\scenarios\payment.js"
        & k6 run --vus $vu --duration 20s -e BASE_URL=$BaseUrl $scenario
        Start-Sleep -Seconds 5
    }
} else {
    Write-Host "k6 not found. Stepping plan: 10 -> 25 -> 50 -> 100 -> 200 -> 400 VUs" -ForegroundColor Yellow
}

Write-Host "================================================================================" -ForegroundColor Green
Write-Host "    CAPACITY STEPPING COMPLETED                                                 " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Green
exit 0
