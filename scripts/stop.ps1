# Stops the Stock Research Cockpit and the monitor. Your data stays in the Docker volume (and in backups\).
# Usage: double-click stop.cmd, or:  powershell -ExecutionPolicy Bypass -File scripts\stop.ps1

$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'docker-helpers.ps1')
Use-Docker
if (-not (Test-DockerEngine)) { Write-Host 'Docker is not running, so nothing is running.'; exit 0 }
docker compose -f (Join-Path $Root 'docker-compose.yml') stop
if ($LASTEXITCODE -eq 0) { Write-Host 'Stopped. Data kept.' -ForegroundColor Green }
