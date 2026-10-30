package com.mockgeo.core.engine

import android.os.IBinder
import android.util.Log
import dalvik.system.DexClassLoader
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bootstrap entry point for Root-mode system_server hook installation and on-device ART verification.
 *
 * Bridges `system_server`'s `services.jar` classloader + modular `/apex/com.android.wifi/javalib/service-wifi.jar`
 * classloader into `RootXposedBridge` (`libmockgeo_art.so`) and invokes `:module`'s `HookInstaller.install`.
 */
object RootSystemBootstrap {
    private const val TAG = "MockGeoRootBoot"
    private const val DEFAULT_SO_PATH = "/data/misc/mockgeo/lib/libmockgeo_art.so"

    private val installedHooksCount = AtomicInteger(0)

    @Volatile
    private var activeBridge: RootXposedBridge? = null

    @JvmStatic
    fun bootstrapFromNative(payloadDexPath: String): Int {
        return try {
            val current = installedHooksCount.get()
            if (current > 0) {
                Log.d(TAG, "Hooks already installed in this process: $current")
                return current
            }
            NativeArtBridge.unlockHiddenApis()
            val soPath = if (File(DEFAULT_SO_PATH).canRead()) DEFAULT_SO_PATH else null
            if (!NativeArtBridge.ensureInitialized(soPath)) {
                Log.e(TAG, "NativeArtBridge failed to initialize")
                return -10
            }

            val systemLoader = resolveCompositeSystemClassLoader()
            val bridge = RootXposedBridge(nativeInstallEnabled = true)
            activeBridge = bridge

            val payloadLoader = if (payloadDexPath.isNotBlank()) {
                val optDir = File("/data/misc/mockgeo/oat").apply { mkdirs() }
                DexClassLoader(
                    payloadDexPath,
                    optDir.absolutePath,
                    "/data/misc/mockgeo/lib",
                    RootSystemBootstrap::class.java.classLoader ?: systemLoader,
                )
            } else {
                RootSystemBootstrap::class.java.classLoader ?: systemLoader
            }

            val installerClass = Class.forName("com.mockgeo.module.HookInstaller", true, payloadLoader)
            val installerInstance = installerClass.getDeclaredField("INSTANCE").get(null)
            val installMethod = installerClass.getDeclaredMethod(
                "install",
                XposedInterface::class.java,
                ClassLoader::class.java,
            )
            val count = (installMethod.invoke(installerInstance, bridge, systemLoader) as? Int) ?: 0
            if (count > 0) {
                installedHooksCount.set(count)
            }
            Log.d(TAG, "RootSystemBootstrap installed $count hooks (${NativeArtBridge.diagnostics()})")
            count
        } catch (t: Throwable) {
            Log.e(TAG, "bootstrapFromNative failed", t)
            -99
        }
    }

    /**
     * Combines `services.jar` (`LocationManagerService`, `AppOpsService`) and modular
     * `service-wifi.jar` (`WifiServiceImpl` on Android 11+) into a single ClassLoader.
     */
    fun resolveCompositeSystemClassLoader(): ClassLoader {
        val base = ClassLoader.getSystemClassLoader()
        val locBinder = getServiceBinder("location")
        val wifiBinder = getServiceBinder("wifi")

        val servicesLoader = locBinder
            ?.takeIf { it.javaClass.name.startsWith("com.android.server.") }
            ?.javaClass?.classLoader
            ?: runCatching {
                val atClass = Class.forName("android.app.ActivityThread")
                val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
                val sysCtx = atClass.getDeclaredMethod("getSystemContext").invoke(at)
                sysCtx?.javaClass?.getMethod("getClassLoader")?.invoke(sysCtx) as? ClassLoader
            }.getOrNull()?.takeIf {
                runCatching { Class.forName("com.android.server.location.LocationManagerService", false, it) }.isSuccess
            }
            ?: runCatching {
                if (File("/system/framework/services.jar").canRead()) {
                    PathClassLoader("/system/framework/services.jar", base)
                } else null
            }.getOrNull()
            ?: base

        val wifiLoader = wifiBinder
            ?.takeIf { it.javaClass.name.startsWith("com.android.server.") }
            ?.javaClass?.classLoader
            ?: runCatching {
                val apexWifi = File("/apex/com.android.wifi/javalib/service-wifi.jar")
                if (apexWifi.canRead()) {
                    PathClassLoader(apexWifi.absolutePath, servicesLoader)
                } else null
            }.getOrNull()

        return if (wifiLoader != null && wifiLoader !== servicesLoader) {
            CompositeClassLoader(servicesLoader, listOf(wifiLoader))
        } else {
            servicesLoader
        }
    }

    private fun getServiceBinder(name: String): IBinder? = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        val getService = sm.getDeclaredMethod("getService", String::class.java)
        getService.isAccessible = true
        getService.invoke(null, name) as? IBinder
    }.getOrNull()

    class CompositeClassLoader(
        primary: ClassLoader,
        private val fallbacks: List<ClassLoader>,
    ) : ClassLoader(primary) {
        override fun findClass(name: String): Class<*> {
            for (loader in fallbacks) {
                try {
                    return loader.loadClass(name)
                } catch (_: ClassNotFoundException) {
                }
            }
            throw ClassNotFoundException(name)
        }
    }

    /**
     * Target class for standalone `app_process` on-device ART hook self-test.
     */
    internal class ProbeTarget {
        fun computeLocationTag(provider: String, uid: Int): Boolean {
            return provider == "gps" && uid > 0
        }

        fun transformLabel(prefix: String, suffix: String): String {
            return "$prefix:$suffix"
        }
    }

    /**
     * Runs an in-process live ART hook self-test on [ProbeTarget] using [RootXposedBridge].
     */
    fun runLiveHookSelfTest(): Boolean {
        if (!NativeArtBridge.ensureInitialized()) return false
        val bridge = RootXposedBridge(nativeInstallEnabled = true)
        val target = ProbeTarget()
        val m1 = ProbeTarget::class.java.getDeclaredMethod(
            "computeLocationTag",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val m2 = ProbeTarget::class.java.getDeclaredMethod(
            "transformLabel",
            String::class.java,
            String::class.java,
        )
        val before1 = target.computeLocationTag("network", 1000)
        val before2 = target.transformLabel("orig", "val")
        bridge.hook(m1).intercept { chain ->
            val orig = chain.proceed() as Boolean
            if (chain.getArg(0) == "network") true else orig
        }
        bridge.hook(m2).intercept { chain ->
            val orig = chain.proceed(arrayOf("hooked", chain.getArg(1))) as String
            "$orig#ok"
        }
        val after1 = target.computeLocationTag("network", 1000)
        val after1Gps = target.computeLocationTag("gps", 1000)
        val after2 = target.transformLabel("orig", "val")
        return !before1 && after1 && after1Gps && before2 == "orig:val" && after2 == "hooked:val#ok"
    }

    /**
     * Standalone CLI entry point (`app_process`) to verify `libmockgeo_art.so` + `RootXposedBridge`
     * on the real device without touching `system_server` or active LSPosed modules.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val soPath = args.firstOrNull { it.startsWith("--so=") }?.removePrefix("--so=")
        val unlocked = NativeArtBridge.unlockHiddenApis()
        val inited = NativeArtBridge.ensureInitialized(soPath)
        val diag = NativeArtBridge.diagnostics()
        println("ART_UNLOCK=$unlocked")
        println("ART_INIT=$inited")
        println("ART_DIAG=$diag")
        if (!inited) {
            System.exit(2)
        }

        val bridge = RootXposedBridge(nativeInstallEnabled = true)
        val target = ProbeTarget()

        val m1 = ProbeTarget::class.java.getDeclaredMethod(
            "computeLocationTag",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val m2 = ProbeTarget::class.java.getDeclaredMethod(
            "transformLabel",
            String::class.java,
            String::class.java,
        )

        val before1 = target.computeLocationTag("network", 1000)
        val before2 = target.transformLabel("orig", "val")

        bridge.hook(m1).intercept { chain ->
            val orig = chain.proceed() as Boolean
            // Invert or override when provider == "network"
            if (chain.getArg(0) == "network") true else orig
        }

        bridge.hook(m2).intercept { chain ->
            val orig = chain.proceed(arrayOf("hooked", chain.getArg(1))) as String
            "$orig#ok"
        }

        val after1 = target.computeLocationTag("network", 1000)
        val after1Gps = target.computeLocationTag("gps", 1000)
        val after2 = target.transformLabel("orig", "val")

        println("BEFORE_M1=$before1 AFTER_M1=$after1 AFTER_M1_GPS=$after1Gps")
        println("BEFORE_M2=$before2 AFTER_M2=$after2")

        var sysHooksPass = true
        if (args.contains("--verify-system-hooks")) {
            val sysHooks = bootstrapFromNative("")
            println("SYSTEM_HOOKS_INSTALLED=$sysHooks")
            sysHooksPass = sysHooks > 0
        }

        val pass = (!before1 && after1 && after1Gps && before2 == "orig:val" && after2 == "hooked:val#ok" && sysHooksPass)
        println("SELF_TEST_RESULT=${if (pass) "PASS" else "FAIL"}")
        System.exit(if (pass) 0 else 1)
    }
}
