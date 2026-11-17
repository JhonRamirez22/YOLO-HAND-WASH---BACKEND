$ErrorActionPreference = 'Continue'
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
& tailscale funnel reset
& docker compose stop
Write-Host 'Backend detenido (los datos se conservan).'
Read-Host 'Enter para cerrar'
