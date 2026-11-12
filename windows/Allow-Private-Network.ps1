# Run from an administrator PowerShell after extracting the receiver package.
$ErrorActionPreference = 'Stop'
$reduxIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$reduxPrincipal = [Security.Principal.WindowsPrincipal]::new($reduxIdentity)
if (-not $reduxPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Open PowerShell as administrator and run this script again.'
}
$reduxExecutable = Join-Path $PSScriptRoot 'ReduxReceiver.exe'
if (-not (Test-Path -LiteralPath $reduxExecutable -PathType Leaf)) { throw 'Keep this script beside ReduxReceiver.exe.' }
$reduxRules = @(
    @{ Name = 'ReduxController-HTTPS'; Protocol = 'TCP'; Port = 19443 },
    @{ Name = 'ReduxController-Input'; Protocol = 'UDP'; Port = 19876 },
    @{ Name = 'ReduxController-Discovery'; Protocol = 'UDP'; Port = 19879 }
)
foreach ($reduxRule in $reduxRules) {
    Get-NetFirewallRule -Name $reduxRule.Name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -Name $reduxRule.Name -DisplayName $reduxRule.Name -Direction Inbound -Action Allow -Profile Private -RemoteAddress LocalSubnet -Program $reduxExecutable -Protocol $reduxRule.Protocol -LocalPort $reduxRule.Port | Out-Null
}
Write-Host 'Redux Controller is allowed from the local subnet on private networks.'
