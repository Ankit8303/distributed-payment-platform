# ==============================================================================
# Payment Ledger Platform - Automated Secret & Credential Scanner (PowerShell)
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

Write-Host "==> Running Secret Scanner on: $RepoRoot" -ForegroundColor Cyan

$patterns = @(
    @{ Name = "AWS Access Key ID"; Pattern = "AKIA[0-9A-Z]{16}" },
    @{ Name = "PEM Private Key"; Pattern = "-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----" },
    @{ Name = "Slack Webhook URL"; Pattern = "https://hooks\.slack\.com/services/T[0-9A-Za-z_]+/B[0-9A-Za-z_]+/[0-9A-Za-z_]+" },
    @{ Name = "GitHub Personal Access Token"; Pattern = "gh[pousr]_[0-9a-zA-Z]{36}" },
    @{ Name = "Stripe Live Secret Key"; Pattern = "sk_live_[0-9a-zA-Z]{24,}" },
    @{ Name = "Slack Bot/User Token"; Pattern = "xox[baprs]-[0-9a-zA-Z]{10,48}" }
)

$excludeExtensions = @(".class", ".jar", ".png", ".svg", ".log", ".md")
# Exclude git, build output, IDE folders, and test directories containing intentional masking fixtures
$excludeDirs = @("\.git\", "\target\", "\.idea\", "\.vscode\", "\node_modules\", "\src\test\")

$files = Get-ChildItem -Path $RepoRoot -Recurse -File | Where-Object {
    $filePath = $_.FullName
    $ext = $_.Extension
    if ($excludeExtensions -contains $ext) { return $false }
    foreach ($dir in $excludeDirs) {
        if ($filePath -match [regex]::Escape($dir)) { return $false }
    }
    if ($_.Name -like "scan-secrets.*") { return $false }
    return $true
}

$findings = 0

foreach ($p in $patterns) {
    Write-Host "Scanning pattern: $($p.Name)"
    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw -ErrorAction SilentlyContinue
        if ($null -ne $content -and $content -match $p.Pattern) {
            Write-Host " [ALERT] Found potential $($p.Name) in $($file.FullName)" -ForegroundColor Red
            $findings++
        }
    }
}

# Verify application-prod.yml does not have hardcoded plain password
$prodYml = Join-Path $RepoRoot "src\main\resources\application-prod.yml"
if (Test-Path $prodYml) {
    $prodContent = Get-Content $prodYml -Raw
    if ($prodContent -match "(?m)^\s*(password|secret):\s*['`"][^\$\{\}]+['`"]") {
        Write-Host " [ALERT] Hardcoded plain password/secret in application-prod.yml" -ForegroundColor Red
        $findings++
    }
}

if ($findings -gt 0) {
    Write-Host "==> Secret scan FAILED: $findings secret leaks detected." -ForegroundColor Red
    exit 1
} else {
    Write-Host "==> Secret scan PASSED: 0 secrets detected." -ForegroundColor Green
    exit 0
}
