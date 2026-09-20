$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDir = Join-Path $projectRoot '.signing'
New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
$keyPath = Join-Path $signingDir 'outpost-release.jks'
$passwordPath = Join-Path $signingDir 'password'
if (Test-Path -LiteralPath $keyPath) { Write-Output 'Existing release key retained.'; exit 0 }
$randomBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($randomBytes)
$rng.Dispose()
[System.IO.File]::WriteAllText($passwordPath, [Convert]::ToBase64String($randomBytes))
# Limit the signing material to this Windows account and SYSTEM.
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $signingDir /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
keytool -genkeypair -keystore $keyPath -alias outpost -storepass:file $passwordPath -keypass:file $passwordPath -dname 'CN=Outpost Personal' -keyalg RSA -keysize 3072 -validity 10000
if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
Write-Output 'Release signing key created. Back up .signing privately; updates require this same key.'
