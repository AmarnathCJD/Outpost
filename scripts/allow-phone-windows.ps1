#Requires -RunAsAdministrator
param(
    [ValidateRange(1, 65535)][int]$SshPort = 2222,
    [string]$ServerPath = (Join-Path $PSScriptRoot 'outpost-server.exe')
)
$ErrorActionPreference = 'Stop'
$logDirectory = Join-Path $env:LOCALAPPDATA 'Outpost'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
try {
    $resolvedServer = (Resolve-Path -LiteralPath $ServerPath).Path
    if ([IO.Path]::GetFileName($resolvedServer) -ne 'outpost-server.exe') { throw 'Select the Outpost backend executable.' }
    $firewallService = Get-Service -Name MpsSvc -ErrorAction Stop
    if ($firewallService.Status -ne 'Running') { throw 'Windows Firewall is stopped or disabled on this PC, so its rule cannot be added. Outpost may already be reachable. If you enable Windows Firewall later, run Allow phone connections again.' }
    $ruleName = 'Outpost-Embedded-SSH'
    $existing = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
    if ($existing) {
        $existing | Set-NetFirewallRule -Enabled True -Direction Inbound -Action Allow -Profile Any
        $existing | Get-NetFirewallPortFilter | Set-NetFirewallPortFilter -Protocol TCP -LocalPort $SshPort
        $existing | Get-NetFirewallApplicationFilter | Set-NetFirewallApplicationFilter -Program $resolvedServer
    } else {
        New-NetFirewallRule -Name $ruleName -DisplayName 'Outpost phone connection' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $SshPort -Program $resolvedServer -Profile Any | Out-Null
    }
    "Phone connection allowed on TCP $SshPort for $resolvedServer" | Set-Content -LiteralPath (Join-Path $logDirectory 'firewall-setup.log')
} catch {
    $_.Exception.Message | Set-Content -LiteralPath (Join-Path $logDirectory 'firewall-setup.log')
    exit 1
}
