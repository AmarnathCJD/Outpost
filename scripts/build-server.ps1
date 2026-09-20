$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousOS = $env:GOOS
$previousArch = $env:GOARCH
$previousCgo = $env:CGO_ENABLED
Push-Location (Join-Path $projectRoot 'server')
try {
    $env:GOOS = 'linux'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
    & go build -trimpath '-ldflags=-s -w' -o (Join-Path $projectRoot 'dist\outpost-server-linux-amd64') .
    if ($LASTEXITCODE -ne 0) { throw 'Linux backend build failed.' }
} finally {
    $env:GOOS = $previousOS; $env:GOARCH = $previousArch; $env:CGO_ENABLED = $previousCgo
    Pop-Location
}
Write-Host 'Linux x64 backend built: dist\outpost-server-linux-amd64'
