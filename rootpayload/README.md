# Independent Root payload

This APK is an internal Dex payload embedded in the main app. It is not an LSPosed,
Magisk, or KernelSU module, and is not installed as a separate Android app.

The loader is restricted to `system_server` or the disposable `mockgeo-root-probe`
process. It restores the traced thread's registers and detaches after starting a
native worker. The worker loads this APK, initializes LSPlant and installs the
shared recipient-specific location hooks. A Root-specific command provider and
process/version/nonce checks keep LSPosed receipts from satisfying Root startup.

The first implementation targets ARM64. Do not inject alongside an existing
LSPosed ART runtime. Payload 0.1.0 passed system acceptance on Xiaomi 15 / Android 16 / KernelSU with LSPosed absent.
App 0.4.8 embeds payload 0.1.4. Its DEX stays unminified, redundant JNI packaging is removed, and native distribution copies have debug data stripped after validating all runtime sections and program headers.

Payload 0.1.4 adds a complete command receipt and shared Java hook stability changes. The native loader is unchanged. Its pure-Root system acceptance remains pending; see [0.4.8 verification](../docs/VERIFICATION_0.4.8.md).
The exact packaged payload passed loading into a disposable process and eight ART checks on Xiaomi 15 / Android 16. Full pure-Root system_server acceptance with LSPosed absent remains pending; do not equate the isolated result with system acceptance.
Other ROMs and app-process sensor hooks are outside the verified scope.

## Build

Requires JDK 17, Android SDK 36, NDK `27.0.12077973`, CMake >= 3.28 and Ninja >= 1.11.
The script uses `ROOT_CMAKE` / `ROOT_NINJA`, the tools in the workspace
`_tools/root-build-python/`, or executables on PATH. No network downloads occur in
the app's Root deployment path.

```powershell
./gradlew.bat :app:assembleRelease -PpublicBuild=true
python scripts/verify-root-probe.py --adb <path-to-adb> --serial <device-serial> --app-apk app/build/outputs/apk/release/app-release.apk
```

The independent probe validates cross-process loading, real Java method
replacement, mixed primitive arguments, original method calls, exceptions,
concurrent calls, deoptimization, removal and continued execution of the original
caller. It does not invoke the real system's location hooks.

## Third-party sources and licenses

Exact commits are recorded in `src/main/cpp/vendor/SOURCES.json`.

| Component | License | Local source |
|---|---|---|
| LSPlant | LGPL-3.0 | `vendor/lsplant` |
| DexBuilder | Apache-2.0 | `vendor/lsplant/external/dex_builder` |
| parallel-hashmap | Apache-2.0 | `vendor/lsplant/external/dex_builder/external/parallel_hashmap` |
| Dobby | Apache-2.0 | `vendor/dobby` |
| xDL | MIT; its LZMA implementation retains its own notices | `vendor/xdl` |

The linked native component contains LGPL code; the project's general MIT license
does not replace these licenses. Corresponding sources, CMake configuration and
rebuild instructions are provided here. Rebuild the native component and main APK
to relink a modified version; no signature check restricts a locally rebuilt APK.
The app embeds the license texts and source revision list under `assets/root/`.

Local xDL addition: `mockgeo_xdl_prefix` exposes bounded symbol-prefix lookup for
LSPlant. No hiding, remapping, downloading or persistence code is added.

Build compatibility patches: LSPlant's compile-time signature sentinels are guarded
for Clang 18; these sentinel branches are discarded for real hook callbacks.
Dobby is pinned to its complete Android implementation at `9c85e74f`.

## Current verification

Published distribution: main app 0.4.8 / Root payload 0.1.4. Shared hooks were tested through
LSPosed module 0.3.15 on the Android 16 device, and the exact 0.1.4 payload passed isolated-process checks; full pure-Root system verification remains pending.
See [the compatibility report](../docs/VERIFICATION_0.4.8.md). The following is historical 0.1.0 evidence.

Main app 0.4.3 / Root payload 0.1.0: build, 166 JVM/debug tests and Lint pass.
Two disposable-VM runs on the Android 16 ARM64 device passed real remote loading,
method replacement and restoration. The updated app is installed. Before framework disable, it correctly rejected
the resident LSPosed runtime. Live `system_server` Root acceptance has now passed after the user disabled LSPosed
and manually rebooted: eight engine checks, separate-UID public location/WebView
checks, PendingIntent/cancellation/removal/flush, GNSS/NMEA and live exemption plus
stop/restart tests. The system PID stayed unchanged, SELinux stayed Enforcing, and
simulation was confirmed disabled after cleanup. Reports are in the workspace
`build/reports/device/root-010-device/` when using the repository runner.
See [the release verification summary](../docs/VERIFICATION_0.4.3.md). No automatic reboot or network
recovery was performed.
