param([switch]$Core, [switch]$AI, [switch]$Sql, [switch]$Vpn)
$ErrorActionPreference = 'Stop'
if (-not ($Core -or $AI -or $Sql -or $Vpn)) { $Core = $true }
$wingetCommand = Get-Command winget.exe -ErrorAction SilentlyContinue
$wingetExe = if ($wingetCommand) { $wingetCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\winget.exe' }

function Install-WingetPackage([string]$PackageId) {
    Write-Host "Installing $PackageId..." -ForegroundColor Cyan
    & $wingetExe install --id $PackageId --exact --source winget --accept-source-agreements --accept-package-agreements
    # Winget reports an already-current installation as a nonzero result.
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) { throw "Installer failed for $PackageId (exit $LASTEXITCODE). See its output above." }
}
function Update-ToolPath {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + $env:Path + ';' + (Join-Path $env:APPDATA 'npm')
}
if ($Core -or $Sql -or $Vpn) {
    if (-not (Test-Path -LiteralPath $wingetExe)) { throw 'Install App Installer from Microsoft Store to get winget, then rerun setup.' }
}
if ($Core) {
    foreach ($package in @('Git.Git', 'GoLang.Go', 'GitHub.cli', 'BurntSushi.ripgrep.MSVC', 'OpenJS.NodeJS.LTS', 'Microsoft.PowerShell')) { Install-WingetPackage $package }
    Update-ToolPath
}
if ($AI) {
    Update-ToolPath
    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Install core tools first, then rerun AI setup.' }
    & npm.cmd install --global '@openai/codex@0.155.1' '@anthropic-ai/claude-code@2.1.278'
    if ($LASTEXITCODE -ne 0) { throw 'AI CLI installation failed. See npm output above.' }
    Write-Host 'Log in from Outpost Developer tools, or from an Android session.'
}
if ($Sql) {
    Install-WingetPackage 'Microsoft.SQLServer.2022.Express'
    Write-Host 'Complete SQL Server setup. Enable TCP/IP in SQL Server Configuration Manager and restart the instance.'
    Write-Host 'Configure your actual host, instance/port, database and credentials under Outpost Git & environment.'
    Write-Host 'Windows SQL Server Express is separate from the SQL Server database on your Ubuntu server.'
}
if ($Vpn) {
    Install-WingetPackage 'Tailscale.Tailscale'
    Write-Host 'Sign in to Tailscale on this laptop and install/sign in to Tailscale on your phone.'
    Write-Host 'Use the laptop Tailscale IP in Outpost Phone access. Windows OpenSSH Server still needs to be enabled.'
    Write-Host 'The laptop hosts your workspace. No Ubuntu server is involved. Tailscale may relay encrypted traffic if a direct peer connection is unavailable.'
}
Write-Host 'Setup finished. Restart hosting to pick up new tools.' -ForegroundColor Green
