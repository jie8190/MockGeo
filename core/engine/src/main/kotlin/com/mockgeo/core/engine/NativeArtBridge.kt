package com.mockgeo.core.engine

import android.os.Build
import android.util.Log
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Native ART Method Hook Bridge (`libmockgeo_art.so`).
 *
 * Calibrates ArtMethod size and entry-point offsets at runtime (Android 8.0 API 26 .. Android 16 API 36),
 * unlocks hidden API exemptions (`VMRuntime.setHiddenApiExemptions("L")`), and installs 32-byte ARM64
 * BTI-safe method trampolines for Root-only mode (`RootXposedBridge`).
 */
object NativeArtBridge {
    private const val TAG = "MockGeoArt"

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Volatile
    var isInitialized: Boolean = false
        private set

    @JvmStatic
    private external fun initNative(sdkInt: Int): Int

    @JvmStatic
    private external fun probeMethodA(a: Long, b: Long): Long

    @JvmStatic
    private external fun probeMethodB(a: Long, b: Long): Long

    @JvmStatic
    private external fun hookMethodNative(
        targetMethod: Executable,
        hookMethod: Method,
        backupMethod: Method,
        copyMethod: Method,
    ): Boolean

    @JvmStatic
    private external fun deoptimizeMethodNative(targetMethod: Executable): Boolean

    @JvmStatic
    private external fun getArtDiagnosticsNative(): String

    @Synchronized
    fun ensureInitialized(customSoPath: String? = null): Boolean {
        unlockHiddenApis()
        if (isInitialized) return true
        if (!isLoaded) {
            isLoaded = runCatching {
                if (!customSoPath.isNullOrBlank() && File(customSoPath).canRead()) {
                    System.load(customSoPath)
                } else {
                    System.loadLibrary("mockgeo_art")
                }
                true
            }.getOrElse { err ->
                Log.w(TAG, "Failed to load libmockgeo_art.so: ${err.message}")
                false
            }
        }
        if (!isLoaded) return false

        val effectiveSdk = runCatching {
            var sdk = Build.VERSION.SDK_INT
            if (sdk >= 23 && Build.VERSION.PREVIEW_SDK_INT > 0) {
                sdk += 1
            }
            sdk
        }.getOrDefault(36)

        isInitialized = runCatching { initNative(effectiveSdk) == 0 }.getOrDefault(false)
        return isInitialized
    }

    /**
     * Exempts all hidden framework APIs (`L`) via double-reflection on `dalvik.system.VMRuntime`.
     * Matches FakeLocation 1.5.2's `C0067.m233()` hidden API unlocker.
     */
    fun unlockHiddenApis(): Boolean {
        if (runCatching { Build.VERSION.SDK_INT }.getOrDefault(0) < 28) return true
        val javaUnlocked = runCatching {
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                emptyArray<Class<*>>().javaClass,
            )
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "getRuntime",
                null,
            ) as Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(emptyArray<String>().javaClass),
            ) as Method
            val runtime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(runtime, arrayOf("L") as Any)
            true
        }.getOrDefault(false)
        return javaUnlocked || diagnostics().contains("hiddenApiUnlocked=true")
    }

    fun hookMethod(
        target: Executable,
        hook: Method,
        backup: Method,
        copy: Method,
    ): Boolean {
        if (!ensureInitialized()) return false
        return runCatching {
            target.isAccessible = true
            hook.isAccessible = true
            backup.isAccessible = true
            copy.isAccessible = true
            hookMethodNative(target, hook, backup, copy)
        }.getOrElse { err ->
            Log.e(TAG, "hookMethod failed for $target", err)
            false
        }
    }

    fun deoptimizeMethod(target: Executable): Boolean {
        if (!ensureInitialized()) return false
        return runCatching {
            target.isAccessible = true
            deoptimizeMethodNative(target)
        }.getOrDefault(false)
    }

    fun diagnostics(): String {
        if (!isLoaded) return "loaded=false"
        return runCatching { getArtDiagnosticsNative() }.getOrElse { "loaded=true error=${it.message}" }
    }
}
