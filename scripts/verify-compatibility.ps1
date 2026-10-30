[CmdletBinding()]
param(
    [string]$Serial = '9d89d59d',
    [string]$RequiredModule = '0.3.10',
    [ValidateSet('current', 'standard', 'compatibility')][string]$Format = 'current',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [switch]$Extended,
    [switch]$RequireSensors
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
# Keep instrumentation short. The actual cross-UID probe runs against the normal
# foreground service, whose lease is checked throughout. The UI setting is not changed.
$normalArgs = @((Join-Path $workspace '_tools\run_normal_probe.py'), '--adb', $AdbPath, '--serial', $Serial,
    '--module', $RequiredModule, '--format', $Format)
if ([version]$RequiredModule -ge [version]'0.3.8') { $normalArgs += '--require-flush' }
python @normalArgs
if ($LASTEXITCODE -ne 0) { throw 'Device compatibility checks failed; see _tools/compat-*-device.' }
if ($Extended -or $RequireSensors) {
    $extendedArgs = @((Join-Path $workspace '_tools\run_extended_probe.py'), '--adb', $AdbPath, '--serial', $Serial, '--module', $RequiredModule)
    if ($RequireSensors) { $extendedArgs += '--require-sensors' }
    python @extendedArgs
    if ($LASTEXITCODE -ne 0) { throw 'GNSS/NMEA/policy checks failed; see _tools/compat-038-device/extended.' }
}
