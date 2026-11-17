# Starts the backend again after a reboot or INICIAR/DETENER (no installation, no prompts).
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
  $dd = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"; if (Test-Path $dd) { Start-Process $dd }
  for ($i = 0; $i -lt 60; $i++) { Start-Sleep -Seconds 5; & docker info *> $null; if ($LASTEXITCODE -eq 0) { break } }
}
& docker compose up -d
& tailscale funnel --bg 8080
if (Test-Path 'copia-sellos.txt') {
  $dest = (Get-Content 'copia-sellos.txt' -TotalCount 1).Trim()
  if ($dest) { & robocopy 'seals' $dest /E /XO /R:1 /W:1 /NFL /NDL /NJH /NJS | Out-Null }
}
Write-Host ''; Write-Host ('URL publica: ' + (Get-Content 'URL-PUBLICA.txt' -ErrorAction SilentlyContinue))
Read-Host 'Enter para cerrar'
