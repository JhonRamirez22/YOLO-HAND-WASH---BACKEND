$ErrorActionPreference = 'Continue'
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
& docker compose ps
try { Write-Host ('Salud local: ' + (Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5).Content) } catch { Write-Host 'Salud local: NO responde' }
$u = Get-Content 'URL-PUBLICA.txt' -ErrorAction SilentlyContinue
if ($u) { try { Write-Host ("Salud publica ($u): " + (Invoke-WebRequest -UseBasicParsing "$u/actuator/health" -TimeoutSec 10).Content) } catch { Write-Host "Salud publica ($u): NO responde" } }
Read-Host 'Enter para cerrar'
