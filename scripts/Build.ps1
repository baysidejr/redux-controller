param([string]$AndroidSdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$reduxRoot = Split-Path $PSScriptRoot -Parent
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $reduxRoot '.tools\android-sdk' }
if (-not (Test-Path -LiteralPath $AndroidSdk)) { throw 'Set ANDROID_HOME or pass -AndroidSdk with the Android SDK path.' }
$reduxSdkProperty = $AndroidSdk.Replace('\', '/').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $reduxRoot 'mobile\local.properties') -Value "sdk.dir=$reduxSdkProperty"
Push-Location (Join-Path $reduxRoot 'mobile')
try {
    & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
} finally { Pop-Location }
Push-Location $reduxRoot
try {
    dotnet build windows\ReduxReceiver.csproj -c Release
    if ($LASTEXITCODE -ne 0) { throw 'Windows build failed.' }
    dotnet windows\bin\Release\net10.0-windows10.0.26100.0\ReduxReceiver.dll --self-test
    if ($LASTEXITCODE -ne 0) { throw 'Receiver self-tests failed.' }
    dotnet publish windows\ReduxReceiver.csproj -c Release -r win-x64 --self-contained true -o dist\windows
    if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }
    & (Join-Path $PSScriptRoot 'Package.ps1')
} finally { Pop-Location }
