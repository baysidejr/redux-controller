$ErrorActionPreference = 'Stop'
$reduxRoot = Split-Path $PSScriptRoot -Parent
$reduxDist = Join-Path $reduxRoot 'dist'
New-Item -ItemType Directory -Force $reduxDist | Out-Null

# Asset names carry the component versions: the receiver's self-updater reads its version from the
# ReduxReceiver-<version>-win-x64.zip name on the latest GitHub release, so keep this scheme.
function Short([string]$version) { $parts = $version.Split('.'); if ($parts.Length -ge 3 -and $parts[2] -ne '0') { $version } else { "$($parts[0]).$($parts[1])" } }
$receiverVersion = Short ([xml](Get-Content -LiteralPath (Join-Path $reduxRoot 'windows\ReduxReceiver.csproj'))).Project.PropertyGroup.Version
$appVersion = Short ([regex]::Match((Get-Content -LiteralPath (Join-Path $reduxRoot 'mobile\app\build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value)
$apkName = "ReduxController-$appVersion-debug.apk"
$receiverName = "ReduxReceiver-$receiverVersion-win-x64.zip"
$sourceName = "ReduxController-$appVersion-source.zip"

Copy-Item -LiteralPath (Join-Path $reduxRoot 'mobile\app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $reduxDist $apkName)
foreach ($reduxDocument in @('QUICKSTART.md', 'THIRD-PARTY-NOTICES.md', 'LICENSE', 'COPYING.GPL3')) {
    Copy-Item -LiteralPath (Join-Path $reduxRoot $reduxDocument) -Destination (Join-Path $reduxDist "windows\$reduxDocument")
}
Copy-Item -LiteralPath (Join-Path $reduxRoot 'windows\Allow-Private-Network.ps1') -Destination (Join-Path $reduxDist 'windows\Allow-Private-Network.ps1')
Compress-Archive -Path (Join-Path $reduxDist 'windows\*') -DestinationPath (Join-Path $reduxDist $receiverName) -Force

$reduxSource = Join-Path $reduxRoot '.tools\source-package'
if (Test-Path -LiteralPath $reduxSource) { Remove-Item -LiteralPath $reduxSource -Recurse -Force }
New-Item -ItemType Directory -Force $reduxSource | Out-Null
$reduxFiles = @(Get-ChildItem -LiteralPath $reduxRoot -File -Force | Where-Object { $_.Name -match '\.md$|^LICENSE$|^COPYING.GPL3$|^\.gitignore$' })
foreach ($reduxDirectory in @('mobile', 'windows', 'scripts')) {
    $reduxFiles += Get-ChildItem -LiteralPath (Join-Path $reduxRoot $reduxDirectory) -Recurse -File -Force | Where-Object { $_.FullName -notmatch '\\(build|bin|obj|\.gradle|\.kotlin)\\' -and $_.Name -ne 'local.properties' }
}
foreach ($reduxFile in $reduxFiles) {
    $reduxRelative = $reduxFile.FullName.Substring($reduxRoot.Length + 1)
    $reduxTarget = Join-Path $reduxSource $reduxRelative
    New-Item -ItemType Directory -Force (Split-Path $reduxTarget -Parent) | Out-Null
    Copy-Item -LiteralPath $reduxFile.FullName -Destination $reduxTarget
}
Compress-Archive -Path (Join-Path $reduxSource '*') -DestinationPath (Join-Path $reduxDist $sourceName) -Force
# sha256sum's binary format ("hash *name"), which the receiver's updater and Redux's updater both read.
Get-FileHash -LiteralPath (Join-Path $reduxDist $apkName), (Join-Path $reduxDist $receiverName), (Join-Path $reduxDist $sourceName) |
    ForEach-Object { "$($_.Hash.ToLower()) *$(Split-Path $_.Path -Leaf)" } | Set-Content -LiteralPath (Join-Path $reduxDist 'SHA256SUMS.txt')
Write-Host "Packaged $apkName, $receiverName and $sourceName in $reduxDist"
