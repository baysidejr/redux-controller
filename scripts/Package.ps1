$ErrorActionPreference = 'Stop'
$reduxRoot = Split-Path $PSScriptRoot -Parent
$reduxDist = Join-Path $reduxRoot 'dist'
New-Item -ItemType Directory -Force $reduxDist | Out-Null
Copy-Item -LiteralPath (Join-Path $reduxRoot 'mobile\app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $reduxDist 'ReduxController-0.2-debug.apk')
foreach ($reduxDocument in @('QUICKSTART.md', 'THIRD-PARTY-NOTICES.md', 'LICENSE', 'COPYING.GPL3')) {
    Copy-Item -LiteralPath (Join-Path $reduxRoot $reduxDocument) -Destination (Join-Path $reduxDist "windows\$reduxDocument")
}
Copy-Item -LiteralPath (Join-Path $reduxRoot 'windows\Allow-Private-Network.ps1') -Destination (Join-Path $reduxDist 'windows\Allow-Private-Network.ps1')
Compress-Archive -Path (Join-Path $reduxDist 'windows\*') -DestinationPath (Join-Path $reduxDist 'ReduxReceiver-0.3-win-x64.zip') -Force

$reduxSource = Join-Path $reduxRoot '.tools\source-package'
New-Item -ItemType Directory -Force $reduxSource | Out-Null
$reduxFiles = @(Get-ChildItem -LiteralPath $reduxRoot -File -Force | Where-Object { $_.Name -match '\.md$|^LICENSE$|^COPYING.GPL3$|^\.gitignore$' })
foreach ($reduxDirectory in @('mobile', 'windows', 'scripts')) {
    $reduxFiles += Get-ChildItem -LiteralPath (Join-Path $reduxRoot $reduxDirectory) -Recurse -File -Force | Where-Object { $_.FullName -notmatch '\\(build|bin|obj|\.gradle)\\' -and $_.Name -ne 'local.properties' }
}
foreach ($reduxFile in $reduxFiles) {
    $reduxRelative = $reduxFile.FullName.Substring($reduxRoot.Length + 1)
    $reduxTarget = Join-Path $reduxSource $reduxRelative
    New-Item -ItemType Directory -Force (Split-Path $reduxTarget -Parent) | Out-Null
    Copy-Item -LiteralPath $reduxFile.FullName -Destination $reduxTarget
}
Compress-Archive -Path (Join-Path $reduxSource '*') -DestinationPath (Join-Path $reduxDist 'ReduxController-0.3-source.zip') -Force
Get-FileHash -LiteralPath (Join-Path $reduxDist 'ReduxController-0.2-debug.apk'), (Join-Path $reduxDist 'ReduxReceiver-0.3-win-x64.zip'), (Join-Path $reduxDist 'ReduxController-0.3-source.zip') |
    ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" } | Set-Content -LiteralPath (Join-Path $reduxDist 'SHA256SUMS.txt')
Write-Host "Packaged APK, Windows receiver and corresponding source in $reduxDist"
