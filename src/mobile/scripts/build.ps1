param([switch]$Connected)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
    if ($Connected) { $tasks += ':app:connectedDebugAndroidTest' }
    & ./gradlew.bat @tasks --console plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with code $LASTEXITCODE" }
    $output = Join-Path (Get-Location) 'dist'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    Copy-Item -LiteralPath (Join-Path $env:NEUROSCRIBE_BUILD_DIR 'outputs\apk\debug\app-debug.apk') -Destination (Join-Path $output 'Sayline.apk') -Force
    Copy-Item -LiteralPath (Join-Path $env:NEUROSCRIBE_BUILD_DIR 'outputs\apk\androidTest\debug\app-debug-androidTest.apk') -Destination (Join-Path $output 'Sayline-tests.apk') -Force
    Get-FileHash -LiteralPath (Join-Path $output 'Sayline.apk') -Algorithm SHA256
} finally { Pop-Location }
