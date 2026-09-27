# ==============================================================================
# Payment Ledger Platform - Production Configuration & Container Validator (PS)
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

Write-Host "==> Validating Production Configurations and Container Definitions in: $RepoRoot" -ForegroundColor Cyan

$errors = 0

# 1. Validate application-prod.yml
$prodYml = Join-Path $RepoRoot "src\main\resources\application-prod.yml"
if (-not (Test-Path $prodYml)) {
    Write-Host " [ERROR] Missing src\main\resources\application-prod.yml" -ForegroundColor Red
    $errors++
} else {
    $content = Get-Content $prodYml -Raw
    if ($content -match "show-sql:\s*true") {
        Write-Host " [ERROR] application-prod.yml must have show-sql: false" -ForegroundColor Red
        $errors++
    }
    if ($content -notmatch "shutdown:\s*graceful") {
        Write-Host " [ERROR] application-prod.yml must enable server.shutdown: graceful" -ForegroundColor Red
        $errors++
    }
    if ($content -match "include:\s*['`"]?\*['`"]?") {
        Write-Host " [ERROR] application-prod.yml must not expose wildcard actuator endpoints ('*')" -ForegroundColor Red
        $errors++
    }
}

# 2. Validate docker/Dockerfile
$dockerfile = Join-Path $RepoRoot "docker\Dockerfile"
if (-not (Test-Path $dockerfile)) {
    Write-Host " [ERROR] Missing docker\Dockerfile" -ForegroundColor Red
    $errors++
} else {
    $dfContent = Get-Content $dockerfile -Raw
    if ($dfContent -notmatch "(?m)^USER\s+appuser") {
        Write-Host " [ERROR] docker\Dockerfile must run as non-root user (USER appuser)" -ForegroundColor Red
        $errors++
    }
    if ($dfContent -notmatch "(?m)^HEALTHCHECK") {
        Write-Host " [ERROR] docker\Dockerfile must define a HEALTHCHECK instruction" -ForegroundColor Red
        $errors++
    }
}

# 3. Validate docker-compose.yml
$composeFile = Join-Path $RepoRoot "docker-compose.yml"
if (-not (Test-Path $composeFile)) {
    Write-Host " [ERROR] Missing docker-compose.yml" -ForegroundColor Red
    $errors++
} else {
    $dcContent = Get-Content $composeFile -Raw
    if ($dcContent -notmatch "postgres:[\s\S]*?healthcheck:") {
        Write-Host " [ERROR] docker-compose.yml missing healthcheck for postgres" -ForegroundColor Red
        $errors++
    }
    if ($dcContent -notmatch "kafka:[\s\S]*?healthcheck:") {
        Write-Host " [ERROR] docker-compose.yml missing healthcheck for kafka" -ForegroundColor Red
        $errors++
    }
    if ($dcContent -notmatch "redis:[\s\S]*?healthcheck:") {
        Write-Host " [ERROR] docker-compose.yml missing healthcheck for redis" -ForegroundColor Red
        $errors++
    }
}

if ($errors -gt 0) {
    Write-Host "==> Configuration validation FAILED: $errors issues found." -ForegroundColor Red
    exit 1
} else {
    Write-Host "==> Configuration validation PASSED: Production configurations and containers verified." -ForegroundColor Green
    exit 0
}
