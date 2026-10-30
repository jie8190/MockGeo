[CmdletBinding()]
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $project
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    $env:JAVA_HOME = Join-Path $workspace '_tools\jdk-17.0.20.1+1'
}
if (-not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$ready = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($ready.Count -ne 1) { throw 'Install requires one authorized USB device; no reconnect or reboot is attempted.' }
if ($Serial -and $Serial -ne $ready[0]) { throw 'Selected device is not the connected device.' }
$Serial = $ready[0]
$env:ANDROID_SERIAL = $Serial
Push-Location $project
try {
    # Full APK deployment. Gradle installDebug avoids Studio Apply Changes/deployment optimization.
    # An ordinary full adb install is also valid; it cannot reload an already running system_server.
    & .\gradlew.bat :module:installDebug --offline --no-daemon --max-workers=4 '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Module installation failed.' }
    & $adb -s $Serial push (Join-Path $PSScriptRoot 'prepare-module-config.sh') /data/local/tmp/mockgeo-prepare-config.sh
    if ($LASTEXITCODE -ne 0) { throw 'Configuration installer copy failed.' }
    & $adb -s $Serial shell "su -c 'sh /data/local/tmp/mockgeo-prepare-config.sh'"
    if ($LASTEXITCODE -ne 0) { throw 'Root-managed exemption configuration setup failed.' }
    & $adb -s $Serial shell dumpsys package com.mockgeo.module | Select-String 'versionCode=|versionName='
    Write-Output 'Installed. Manually restart when convenient, then verify the loaded module version. No reboot/network changes performed.'
} finally { Pop-Location }
