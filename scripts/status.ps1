# Shows whether Docker, the containers, the Stock Research Cockpit and the monitor are running.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\status.ps1

$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'docker-helpers.ps1')
Use-Docker
if (-not (Test-DockerEngine)) { Write-Host 'Docker: installed, NOT running (start.cmd starts it)' -ForegroundColor Red; exit 1 }
Write-Host 'Docker: running' -ForegroundColor Green
docker compose -f (Join-Path $Root 'docker-compose.yml') ps --format 'table {{.Name}}\t{{.Status}}'
try {
    $null = Invoke-WebRequest -Uri 'http://localhost:8099/health' -UseBasicParsing -TimeoutSec 5
    Write-Host 'Stock Research Cockpit: up at http://localhost:8099' -ForegroundColor Green
} catch {
    Write-Host 'Stock Research Cockpit: not answering (start.cmd starts it)' -ForegroundColor Red
}
Write-Host 'Monitor, last lines of its log:'
docker logs --tail 3 research-monitor 2>&1 | ForEach-Object { "  $_" }
