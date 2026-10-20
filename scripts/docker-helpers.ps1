# Shared by the scripts in this folder (dot-source it): makes sure the docker command is found and Docker is running.
#   . (Join-Path $PSScriptRoot 'docker-helpers.ps1'); Use-Docker            # finds docker.exe, exits with a message if missing
#   Start-DockerEngine                                                     # starts Docker Desktop if needed, waits up to 3 minutes

function Use-Docker {
    if (Get-Command docker -ErrorAction SilentlyContinue) { return }
    # A window opened before Docker Desktop was installed keeps the old PATH: reload it, then try the default location.
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
    if (Get-Command docker -ErrorAction SilentlyContinue) { return }
    $bin = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin'
    if (Test-Path (Join-Path $bin 'docker.exe')) {
        $env:Path = "$bin;$env:Path"
        return
    }
    Write-Host 'Docker is not installed. Install Docker Desktop (https://www.docker.com/products/docker-desktop/, or: winget install Docker.DockerDesktop), start it once, then run this again.' -ForegroundColor Red
    exit 1
}

function Test-DockerEngine {
    $ErrorActionPreference = 'Continue'
    docker info --format '{{.ServerVersion}}' *> $null
    return ($LASTEXITCODE -eq 0)
}

function Start-DockerEngine {
    if (Test-DockerEngine) { return }
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path $desktop) {
        Write-Host 'Starting Docker Desktop (this takes up to a minute)...'
        Start-Process $desktop
    }
    $deadline = (Get-Date).AddMinutes(3)
    while (-not (Test-DockerEngine)) {
        if ((Get-Date) -gt $deadline) {
            Write-Host 'Docker is not running. Start Docker Desktop, wait until it says "Engine running", then run this again.' -ForegroundColor Red
            exit 1
        }
        Start-Sleep -Seconds 5
    }
}
