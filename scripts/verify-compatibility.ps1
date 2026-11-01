[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$RequiredModule = '0.3.13',
    [ValidateSet('xposed', 'root')][string]$Engine = 'xposed',
    [ValidateSet('current', 'standard', 'compatibility')][string]$Format = 'current',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [ValidateRange(20, 180)][int]$DurationSeconds = 60,
    [switch]$Extended,
    [switch]$RequireSensors
)
$ErrorActionPreference = 'Stop'
$deviceScripts = Join-Path $PSScriptRoot 'device'
if (-not $Serial) {
    $ready = @(& $AdbPath devices | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($ready.Count -ne 1) { throw 'Specify -Serial, or connect exactly one authorized device.' }
    $Serial = $ready[0]
}
if ($Engine -eq 'root') {
    if ($RequireSensors) { throw 'Root mode does not include app-process sensor hooks.' }
    if (-not $PSBoundParameters.ContainsKey('RequiredModule')) { $RequiredModule = '0.1.1' }
}
# Keep instrumentation short. The actual cross-UID probe runs against the normal
# foreground service, whose lease is checked throughout. The UI setting is not changed.
$normalArgs = @((Join-Path $deviceScripts 'run_normal_probe.py'), '--adb', $AdbPath, '--serial', $Serial,
    '--module', $RequiredModule, '--format', $Format, '--engine', $Engine, '--duration-seconds', $DurationSeconds)
if ($Engine -eq 'root' -or [version]$RequiredModule -ge [version]'0.3.8') { $normalArgs += '--require-flush' }
python @normalArgs
if ($LASTEXITCODE -ne 0) { throw 'Device compatibility checks failed; see build/reports/device/*-device.' }
if ($Extended -or $RequireSensors) {
    $extendedArgs = @((Join-Path $deviceScripts 'run_extended_probe.py'), '--adb', $AdbPath, '--serial', $Serial, '--module', $RequiredModule, '--engine', $Engine)
    if ($RequireSensors) { $extendedArgs += '--require-sensors' }
    python @extendedArgs
    if ($LASTEXITCODE -ne 0) { throw 'GNSS/NMEA/policy checks failed; see build/reports/device/*-device/extended.' }
}
