# ==============================================================================
# Payment Ledger Platform - Flyway Migration Integrity Validator (PowerShell)
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

$migrationsDir = Join-Path $RepoRoot "src\main\resources\db\migration"

Write-Host "==> Validating Flyway migrations in: $migrationsDir" -ForegroundColor Cyan

if (-not (Test-Path $migrationsDir)) {
    Write-Host "ERROR: Migrations directory not found: $migrationsDir" -ForegroundColor Red
    exit 1
}

$files = Get-ChildItem -Path $migrationsDir -Filter "*.sql" -File | Sort-Object Name
$errors = 0
$versions = @()

foreach ($file in $files) {
    if ($file.Name -notmatch "^V(\d+)__([a-zA-Z0-9_]+)\.sql$") {
        Write-Host " [ERROR] Invalid migration filename format: $($file.Name)" -ForegroundColor Red
        $errors++
        continue
    }

    $ver = [int]$Matches[1]
    
    # Check non-empty
    if ($file.Length -eq 0) {
        Write-Host " [ERROR] Migration file is empty: $($file.Name)" -ForegroundColor Red
        $errors++
    }

    # Check forbidden statements
    $content = Get-Content $file.FullName -Raw
    if ($content -match "(?i)DROP\s+DATABASE") {
        Write-Host " [ERROR] Forbidden 'DROP DATABASE' in: $($file.Name)" -ForegroundColor Red
        $errors++
    }

    $versions += $ver
}

$sortedVersions = $versions | Sort-Object
$prev = 0

foreach ($v in $sortedVersions) {
    if ($v -eq $prev) {
        Write-Host " [ERROR] Duplicate migration version: V$v" -ForegroundColor Red
        $errors++
    } elseif ($v -ne ($prev + 1)) {
        Write-Host " [ERROR] Non-consecutive migration: expected V$($prev + 1), got V$v" -ForegroundColor Red
        $errors++
    }
    $prev = $v
}

Write-Host "Validated $($sortedVersions.Count) Flyway migrations (V1 through V$prev)." -ForegroundColor Cyan

if ($errors -gt 0) {
    Write-Host "==> Migration validation FAILED: $errors issues found." -ForegroundColor Red
    exit 1
} else {
    Write-Host "==> Migration validation PASSED: All migrations comply." -ForegroundColor Green
    exit 0
}
