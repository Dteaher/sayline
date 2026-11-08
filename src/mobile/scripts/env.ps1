# Dot-source this file to select the existing official portable toolchain.
$cachedTools = Join-Path $env:USERPROFILE '.cache\tailtown-tools'
$portableTools = Join-Path $env:LOCALAPPDATA 'NeuroScribe\tools'
if ($env:JAVA_HOME -and $env:ANDROID_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe") -and (Test-Path -LiteralPath "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    # Respect a working developer-selected installation.
} elseif (Test-Path -LiteralPath (Join-Path $cachedTools 'jdk')) {
    $env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $cachedTools 'jdk') -Directory | Select-Object -First 1).FullName
    $env:ANDROID_HOME = Join-Path $cachedTools 'android-sdk'
} elseif (Test-Path -LiteralPath (Join-Path $portableTools 'jdk')) {
    $env:JAVA_HOME = Join-Path $portableTools 'jdk'
    $env:ANDROID_HOME = Join-Path $portableTools 'android-sdk'
} else {
    throw 'JDK/SDK not found. Set JAVA_HOME and ANDROID_HOME or run python scripts/bootstrap.py tools.'
}
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
$env:NEUROSCRIBE_BUILD_DIR = Join-Path $env:LOCALAPPDATA 'NeuroScribe\build\app'
Set-Content -LiteralPath (Join-Path $PSScriptRoot '..\local.properties') -Value ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')) -Encoding ascii
