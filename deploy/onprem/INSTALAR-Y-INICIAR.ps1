# HandWash backend on this Windows PC: installs what is missing, configures and starts everything.
# Safe to run again: it never regenerates secrets that already exist and never touches the database volume.
param([switch]$Elevated)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

function Say($m)  { Write-Host "[HandWash] $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "[HandWash] ERROR: $m" -ForegroundColor Red; Read-Host 'Enter para cerrar'; exit 1 }
function Have($c) { return [bool](Get-Command $c -ErrorAction SilentlyContinue) }
function RefreshPath { $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User') }
function RandomHex($bytes) { $b = New-Object byte[] $bytes; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); return ($b | ForEach-Object { $_.ToString('x2') }) -join '' }
function RandomB64($bytes) { $b = New-Object byte[] $bytes; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); return [Convert]::ToBase64String($b) }

# 1. Administrator rights (needed once, to install Docker Desktop and Tailscale).
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin -and -not $Elevated) {
  Say 'Pidiendo permisos de administrador...'
  Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Elevated"
  exit 0
}

# 2. Docker Desktop and Tailscale.
RefreshPath
if (-not (Have winget)) { Fail 'Falta winget (App Installer). Actualiza Windows o instala "App Installer" desde Microsoft Store y vuelve a ejecutar.' }
if (-not (Have docker)) {
  Say 'Instalando Docker Desktop (puede tardar varios minutos)...'
  winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
  RefreshPath
  if (-not (Have docker)) {
    Write-Host '[HandWash] Docker Desktop quedo instalado. Reinicia el PC (si Windows lo pide), abre Docker Desktop una vez, acepta los terminos y vuelve a ejecutar este script.' -ForegroundColor Yellow
    Read-Host 'Enter para cerrar'; exit 0
  }
}
if (-not (Have tailscale)) {
  Say 'Instalando Tailscale...'
  winget install -e --id Tailscale.Tailscale --accept-package-agreements --accept-source-agreements
  RefreshPath
  if (-not (Have tailscale)) { $ts = "$env:ProgramFiles\Tailscale\tailscale.exe"; if (Test-Path $ts) { $env:Path += ";$env:ProgramFiles\Tailscale" } }
  if (-not (Have tailscale)) { Fail 'Tailscale no quedo disponible. Reinicia la terminal y vuelve a ejecutar.' }
}

# 3. Docker engine running.
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
  Say 'Iniciando Docker Desktop...'
  $dd = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
  if (Test-Path $dd) { Start-Process $dd }
  $ok = $false
  for ($i = 0; $i -lt 60; $i++) { Start-Sleep -Seconds 5; & docker info *> $null; if ($LASTEXITCODE -eq 0) { $ok = $true; break } }
  if (-not $ok) { Fail 'Docker no arranco en 5 minutos. Abre Docker Desktop manualmente, espera a que diga "Running" y reintenta.' }
}

# 4. Configuration (.env): secrets are generated once and kept.
$envFile = Join-Path $here '.env'
if (-not (Test-Path $envFile)) {
  Say 'Creando configuracion nueva...'
  $user = Read-Host 'Usuario ADMIN (ej. admin)'
  if ($user -notmatch '^[A-Za-z0-9._-]{3,40}$') { Fail 'El usuario solo admite letras, numeros, punto, guion y guion bajo (3-40).' }
  $sec = Read-Host 'Contrasena ADMIN (minimo 12 caracteres)' -AsSecureString
  $pass = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
  if ($pass.Length -lt 12) { Fail 'La contrasena debe tener al menos 12 caracteres.' }
  $origin = Read-Host 'URL del frontend en Vercel [https://frontend-proyecto-handwash.vercel.app]'
  if ([string]::IsNullOrWhiteSpace($origin)) { $origin = 'https://frontend-proyecto-handwash.vercel.app' }
  $lines = @(
    "HANDWASH_OWNER_PASSWORD=$(RandomHex 24)",
    "HANDWASH_DB_PASSWORD=$(RandomHex 24)",
    "HANDWASH_MIGRATION_DB_PASSWORD=$(RandomHex 24)",
    "HANDWASH_AUDIT_CHAIN_SECRET=$(RandomHex 32)",
    "HANDWASH_ADMIN_TOTP_KEY=$(RandomB64 32)",
    "HANDWASH_CORS_ALLOWED_ORIGINS=$($origin.TrimEnd('/'))",
    "HANDWASH_BOOTSTRAP_ADMIN_USERNAME=$user",
    "HANDWASH_BOOTSTRAP_ADMIN_PASSWORD=$pass"
  )
  [IO.File]::WriteAllLines($envFile, $lines, (New-Object Text.UTF8Encoding($false)))
  Say 'IMPORTANTE: guarda una copia de .env en un lugar seguro. Sin ese archivo no puedes recuperar los datos del volumen.'
}
New-Item -ItemType Directory -Force -Path (Join-Path $here 'seals') | Out-Null

# 5. Build and start.
Say 'Construyendo y arrancando el backend (la primera vez tarda 5-10 minutos)...'
& docker compose up -d --build
if ($LASTEXITCODE -ne 0) { Fail 'docker compose fallo. Revisa el mensaje de arriba.' }
Say 'Esperando a que el backend este sano...'
$healthy = $false
for ($i = 0; $i -lt 60; $i++) {
  try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5; if ($r.StatusCode -eq 200) { $healthy = $true; break } } catch { }
  Start-Sleep -Seconds 5
}
if (-not $healthy) { & docker compose logs --tail 60 backend; Fail 'El backend no respondio. Revisa los registros de arriba.' }

# The first ADMIN exists now: drop the bootstrap password from disk.
$kept = Get-Content $envFile | Where-Object { $_ -notmatch '^HANDWASH_BOOTSTRAP_ADMIN_(USERNAME|PASSWORD)=' }
[IO.File]::WriteAllLines($envFile, $kept, (New-Object Text.UTF8Encoding($false)))

# 6. Public HTTPS address with Tailscale Funnel.
& tailscale status *> $null
if ($LASTEXITCODE -ne 0) {
  Say 'Inicia sesion en Tailscale en la ventana del navegador que se abre...'
  & tailscale up
}
Say 'Publicando el backend con Tailscale Funnel...'
& tailscale funnel --bg 8080
if ($LASTEXITCODE -ne 0) {
  Write-Host '[HandWash] Funnel no esta habilitado en tu cuenta de Tailscale. Abre https://login.tailscale.com/admin/dns , activa "HTTPS Certificates", y luego sigue el enlace que Tailscale mostro arriba para permitir Funnel. Despues vuelve a ejecutar este script.' -ForegroundColor Yellow
  Read-Host 'Enter para cerrar'; exit 1
}
$dns = ((& tailscale status --json | ConvertFrom-Json).Self.DNSName).TrimEnd('.')
$url = "https://$dns"
[IO.File]::WriteAllText((Join-Path $here 'URL-PUBLICA.txt'), $url + "`r`n")

Write-Host ''
Write-Host '=============================================================' -ForegroundColor Green
Write-Host " Backend funcionando.  URL publica: $url" -ForegroundColor Green
Write-Host ' (guardada en URL-PUBLICA.txt)' -ForegroundColor Green
Write-Host ' Primer ingreso ADMIN: en el frontend, usuario + contrasena;' -ForegroundColor Green
Write-Host ' te mostrara el secreto TOTP para tu app autenticadora.' -ForegroundColor Green
Write-Host '=============================================================' -ForegroundColor Green
Read-Host 'Enter para cerrar'
