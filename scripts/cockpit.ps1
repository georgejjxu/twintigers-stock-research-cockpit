# Stock Research Cockpit: open it, or restart / stop just the web app (the monitor keeps running).
#   powershell -ExecutionPolicy Bypass -File scripts\cockpit.ps1            open http://localhost:8099 (starting it if needed)
#   powershell -ExecutionPolicy Bypass -File scripts\cockpit.ps1 -Restart   restart it (after editing secrets.env or java\config)
#   powershell -ExecutionPolicy Bypass -File scripts\cockpit.ps1 -Stop      stop it
# Log: docker compose logs research-cockpit

param([switch]$Stop, [switch]$Restart)

$Root = Split-Path -Parent $PSScriptRoot
$Compose = Join-Path $Root 'docker-compose.yml'
. (Join-Path $PSScriptRoot 'docker-helpers.ps1')
Use-Docker

if ($Stop) {
    if (Test-DockerEngine) { docker compose -f $Compose stop research-cockpit }
    Write-Host 'Stock Research Cockpit stopped.' -ForegroundColor Green
    exit 0
}
Start-DockerEngine
if ($Restart) { docker compose -f $Compose restart research-cockpit } else { docker compose -f $Compose up -d research-cockpit }
$deadline = (Get-Date).AddSeconds(90)
while ($true) {
    try { if ((Invoke-WebRequest -Uri 'http://localhost:8099/health' -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) { break } } catch { }
    if ((Get-Date) -gt $deadline) { Write-Host 'The Cockpit did not answer within 90 s. See: docker compose logs research-cockpit' -ForegroundColor Red; exit 1 }
    Start-Sleep -Seconds 2
}
Write-Host 'Stock Research Cockpit: http://localhost:8099' -ForegroundColor Green
if (-not $Restart) { Start-Process 'http://localhost:8099/' }
