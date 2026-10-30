[CmdletBinding()]
param(
    [ValidateSet('engine', 'map', 'data', 'all')][string]$Mode = 'all',
    [string]$Serial = '',
    [string]$AdbPath = '',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) {
    $sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $AdbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
}
if (-not (Test-Path -LiteralPath $AdbPath)) { throw 'Set -AdbPath or ANDROID_HOME to your Android SDK.' }
$deviceLines = & $AdbPath devices
if ($LASTEXITCODE -ne 0) { throw 'ADB could not list devices.' }
$readySerials = @($deviceLines | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $Serial) {
    if ($readySerials.Count -ne 1) { throw 'Connect one authorized USB device or provide -Serial. No retry loop is started.' }
    $Serial = $readySerials[0]
}
if ($readySerials -notcontains $Serial) { throw 'The selected device is not connected and authorized.' }
if ($Install) {
    foreach ($artifact in @('app\build\outputs\apk\debug\app-debug.apk', 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
        $artifactPath = Join-Path $projectPath $artifact
        if (-not (Test-Path -LiteralPath $artifactPath)) { throw "Build the APK first: $artifact" }
        & $AdbPath -s $Serial install -r $artifactPath
        if ($LASTEXITCODE -ne 0) { throw "Install failed: $artifact" }
    }
}
$modes = if ($Mode -eq 'all') { @('data', 'map', 'engine') } else { @($Mode) }
$reportPath = Join-Path $projectPath 'build\device-reports'
New-Item -ItemType Directory -Path $reportPath -Force | Out-Null
foreach ($currentMode in $modes) {
    $argument = if ($currentMode -eq 'engine') { 'smoke' } else { $currentMode }
    Write-Output "Running $currentMode checks on $Serial. Map checks require prior consent in the app."
    $result = & $AdbPath -s $Serial shell am instrument -w -r -e mode $argument com.mockgeo.app.test/com.mockgeo.app.DeviceSmokeInstrumentation 2>&1
    $exitCode = $LASTEXITCODE
    $result | Tee-Object -FilePath (Join-Path $reportPath "$currentMode.log")
    if ($exitCode -ne 0 -or ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1') {
        throw "$currentMode checks did not pass. See build/device-reports/$currentMode.log."
    }
}
Write-Output 'Selected device checks passed. No network setting or restart was performed.'
