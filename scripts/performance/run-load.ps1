# ==============================================================================
# Payment Ledger Platform — Progressive Load Test Runner (PowerShell)
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
Write-Host "    PAYMENT LEDGER PLATFORM — PROGRESSIVE LOAD TEST SUITE                       " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

$scenarios = @("health.js", "authentication.js", "account.js", "payment.js", "refund.js", "payout.js", "admin.js")
$k6Cmd = Get-Command k6 -ErrorAction SilentlyContinue

if ($k6Cmd) {
    foreach ($s in $scenarios) {
        Write-Host "--> Running Load Scenario: $s" -ForegroundColor Yellow
        $path = Join-Path $RepoRoot "performance\scenarios\$s"
        & k6 run -e BASE_URL=$BaseUrl $path
    }
} else {
    Write-Host "k6 is not detected in PATH. Scenarios are located in performance\scenarios\" -ForegroundColor Yellow
}

Write-Host "================================================================================" -ForegroundColor Green
Write-Host "    LOAD TESTING RUN COMPLETED                                                  " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Green
exit 0
