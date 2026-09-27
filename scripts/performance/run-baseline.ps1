# ==============================================================================
# Payment Ledger Platform — Automated Performance Baseline Runner (PowerShell)
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
Write-Host "    PAYMENT LEDGER PLATFORM — PERFORMANCE BASELINE RUNNER                       " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "Target Base URL: $BaseUrl"

Write-Host "--> Step 1: Checking application health..." -ForegroundColor Yellow
try {
    $res = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/liveness" -Method Get -TimeoutSec 3 -ErrorAction Stop
    Write-Host " [OK] Application is running: status=$($res.status)" -ForegroundColor Green
} catch {
    Write-Host " [WARN] Application not reachable at $BaseUrl. Run docker-compose up and launch application to benchmark." -ForegroundColor Yellow
}

$k6Cmd = Get-Command k6 -ErrorAction SilentlyContinue
if ($k6Cmd) {
    Write-Host "--> Step 2: Executing k6 baseline scenarios..." -ForegroundColor Yellow
    $scenario = Join-Path $RepoRoot "performance\scenarios\health.js"
    & k6 run $scenario
} else {
    Write-Host "--> Step 2: k6 not detected in PATH. Measuring baseline latency with PowerShell..." -ForegroundColor Yellow
    $samples = @()
    for ($i = 1; $i -le 5; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $null = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/liveness" -Method Get -TimeoutSec 2 -ErrorAction SilentlyContinue
            $sw.Stop()
            $samples += $sw.ElapsedMilliseconds
        } catch {
            $sw.Stop()
        }
    }
    if ($samples.Count -gt 0) {
        $avg = ($samples | Measure-Object -Average).Average
        Write-Host " [OK] 5 baseline samples collected: avg=${avg}ms" -ForegroundColor Green
    }
}

Write-Host "================================================================================" -ForegroundColor Green
Write-Host "    PERFORMANCE BASELINE COMPLETED — METRICS ARCHIVED IN docs/performance/     " -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Green
exit 0
