# Starts the Stock Research Cockpit and its monitor in Docker, building the image from java\ when the code changed, and
# opens http://localhost:8099 in the browser. Starts Docker Desktop first if it is not running.
# First run: creates secrets.env from secrets.env.example (edit SEC_USER_AGENT there, then run again).
# Usage: double-click start.cmd, or from the project folder:  powershell -ExecutionPolicy Bypass -File scripts\start.ps1 [-NoBrowser]

param([switch]$NoBrowser)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'docker-helpers.ps1')
Use-Docker

$Secrets = Join-Path $Root 'secrets.env'
if (-not (Test-Path $Secrets)) {
    Copy-Item (Join-Path $Root 'secrets.env.example') $Secrets
    Write-Host 'Created secrets.env from secrets.env.example.' -ForegroundColor Yellow
    Write-Host 'Open secrets.env and put your name and email in SEC_USER_AGENT (the SEC requires it), then run this again.'
    exit 1
}
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'backups') | Out-Null

Start-DockerEngine
Write-Host 'Starting the Stock Research Cockpit and the monitor (the first start builds the image: about 5 minutes)...'
$ErrorActionPreference = 'Continue'
docker compose -f (Join-Path $Root 'docker-compose.yml') up -d --build --remove-orphans
if ($LASTEXITCODE -ne 0) { Write-Host 'docker compose up failed (see above).' -ForegroundColor Red; exit 1 }
$ErrorActionPreference = 'Stop'

$deadline = (Get-Date).AddMinutes(2)
while ($true) {
    try { if ((Invoke-WebRequest -Uri 'http://localhost:8099/health' -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200) { break } } catch { }
    if ((Get-Date) -gt $deadline) {
        Write-Host 'The Stock Research Cockpit did not answer within 2 minutes. See: docker compose logs research-cockpit' -ForegroundColor Red
        exit 1
    }
    Start-Sleep -Seconds 3
}
Write-Host 'Stock Research Cockpit: http://localhost:8099' -ForegroundColor Green
if (-not $NoBrowser) { Start-Process 'http://localhost:8099/' }
