param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
# Ignore a stale SDK path left by an older portable SDK, only in this build process.
if ($env:MSBuildSDKsPath -and -not (Test-Path -LiteralPath $env:MSBuildSDKsPath)) { Remove-Item Env:MSBuildSDKsPath }
$releaseFolder = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $projectRoot 'dist\windows' }
New-Item -ItemType Directory -Force -Path $releaseFolder | Out-Null
Push-Location (Join-Path $projectRoot 'server')
try {
    & go build -trimpath '-ldflags=-s -w' -o (Join-Path $releaseFolder 'outpost-server.exe') .
    if ($LASTEXITCODE -ne 0) { throw 'Windows backend build failed.' }
} finally { Pop-Location }
& dotnet publish (Join-Path $projectRoot 'desktop\Outpost.Host.csproj') -c Release -r win-x64 --self-contained true -o $releaseFolder
if ($LASTEXITCODE -ne 0) { throw 'Desktop host build failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'setup-windows.ps1') -Destination $releaseFolder -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'allow-phone-windows.ps1') -Destination $releaseFolder -Force
$readme = [IO.File]::ReadAllText((Join-Path $projectRoot 'docs\WINDOWS.md')).Replace('../relay/README.md', 'RELAY.md')
[IO.File]::WriteAllText((Join-Path $releaseFolder 'README.md'), $readme, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $projectRoot 'relay\README.md') -Destination (Join-Path $releaseFolder 'RELAY.md') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\PAIRING.md') -Destination $releaseFolder -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\ASSISTANTS.md') -Destination $releaseFolder -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\THIRD-PARTY-NOTICES.txt') -Destination $releaseFolder -Force
# Select package members explicitly so old binaries and setup helpers cannot leak into an update.
$packageFiles = @('Outpost.exe', 'outpost-server.exe', 'setup-windows.ps1', 'allow-phone-windows.ps1', 'README.md', 'RELAY.md', 'PAIRING.md', 'ASSISTANTS.md', 'THIRD-PARTY-NOTICES.txt') | ForEach-Object { Join-Path $releaseFolder $_ }
Compress-Archive -LiteralPath $packageFiles -DestinationPath (Join-Path $projectRoot 'dist\outpost-windows.zip') -Force
Write-Host 'Windows release: dist\outpost-windows.zip'
