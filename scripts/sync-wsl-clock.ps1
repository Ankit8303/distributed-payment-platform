wsl -d Ubuntu -u root systemctl stop systemd-timesyncd
$utc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd HH:mm:ss")
wsl -d Ubuntu -u root date -u -s "$utc"
Write-Host "Windows UTC:" (Get-Date).ToUniversalTime().ToString("o")
Write-Host "WSL Date:   " (wsl -d Ubuntu -e date -u "+%Y-%m-%dT%H:%M:%SZ")
