$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousOS = $env:GOOS
$previousArch = $env:GOARCH
$previousCgo = $env:CGO_ENABLED
Push-Location (Join-Path $projectRoot 'relay')
try {
    $env:GOOS = 'linux'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
    & go build -trimpath '-ldflags=-s -w' -o (Join-Path $projectRoot 'dist\outpost-relay') .
    if ($LASTEXITCODE -ne 0) { throw 'Relay build failed.' }
} finally {
    $env:GOOS = $previousOS; $env:GOARCH = $previousArch; $env:CGO_ENABLED = $previousCgo
    Pop-Location
}
Write-Host 'Linux x64 relay built: dist\outpost-relay'
