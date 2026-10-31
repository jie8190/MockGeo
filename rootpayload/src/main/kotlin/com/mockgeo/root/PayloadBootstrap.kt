package com.mockgeo.root

import android.os.IBinder
import android.os.Process
import com.mockgeo.module.HookInstaller
import com.mockgeo.module.ModuleConfig
import com.mockgeo.module.ModuleRuntime
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PayloadBootstrap {
    private var bridge: PlantBridge? = null

    @JvmStatic fun start(mode: String, nonce: String, directory: String): Int {
        val report = JSONObject().put("backend", "root").put("mode", mode).put("nonce", nonce)
            .put("pid", Process.myPid()).put("uid", Process.myUid()).put("version", BuildConfig.VERSION_NAME)
            .put("startTicks", File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19])
        return try {
            require(nonce.matches(Regex("[0-9a-f]{32}")))
            val loader = if (mode == "system") liveSystemLoader() else {
                require(mode == "probe" && processName() == "mockgeo-root-probe")
                null
            }
            check(NativeHooks.initialize()) { "ART hook initialization failed" }
            val api = PlantBridge().also { bridge = it }
            if (mode == "probe") {
                verifyProbe(api)
                report.put("checks", 8)
            } else {
                ModuleRuntime.activateRoot(BuildConfig.VERSION_NAME, nonce)
                ModuleConfig.loadedAtMs = System.currentTimeMillis()
                HookInstaller.install(api, checkNotNull(loader))
                val required = setOf("sendExtraCommand", "getLastLocation", "getCurrentLocation",
                    "registerLocationListener", "unregisterLocationListener", "onLocationChanged")
                val installed = api.installedMethods.map { it.name }.toSet()
                check(installed.containsAll(required)) { "Missing system hooks: ${required - installed}" }
                report.put("hooks", api.installedMethods.size)
            }
            report.put("ready", true)
            writeReceipt(directory, report)
            0
        } catch (error: Throwable) {
            report.put("ready", false).put("error", error.toString())
            writeReceipt(directory, report)
            -1
        }
    }

    private fun processName() = File("/proc/self/cmdline").readText().substringBefore('\u0000')

    private fun liveSystemLoader(): ClassLoader {
        check(Process.myUid() == 1000 && processName() == "system_server") { "Not the live system_server" }
        val maps = File("/proc/self/maps").readText().lowercase()
        check(!maps.contains("lsposed") && !maps.contains("/lspd/")) { "An LSPosed runtime is already resident" }
        fun service(name: String): IBinder {
            val binder = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
                .invoke(null, name) as? IBinder
            check(binder != null && binder.javaClass.name.startsWith("com.android.server.")) {
                "$name is not a local system service"
            }
            return binder
        }
        val location = service("location").javaClass.classLoader ?: error("Missing system class loader")
        val wifi = runCatching { service("wifi").javaClass.classLoader }.getOrNull()
        // Never load a second copy of services.jar: it would not affect the live service.
        return if (wifi == null || wifi === location) location else object : ClassLoader(location) {
            override fun findClass(name: String): Class<*> = wifi.loadClass(name)
        }
    }

    private fun verifyProbe(api: PlantBridge) {
        val target = ProbeTarget()
        check(target.value(2, 7L, 1.5, "probe") == "probe:10.5")
        val method = ProbeTarget::class.java.getDeclaredMethod("value", Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Double::class.javaPrimitiveType, String::class.java)
        val handle = api.hook(method).intercept { call ->
            val args = call.args.toTypedArray().apply { this[0] = 3 }
            "root:${call.proceed(args)}"
        }
        check(method.invoke(target, 2, 7L, 1.5, "probe") == "root:probe:11.5")
        check(target.value(2, 7L, 1.5, "probe") == "root:probe:11.5")
        val throwing = ProbeTarget::class.java.getDeclaredMethod("denied")
        val other = api.hook(throwing).intercept { it.proceed() }
        try { throwing.invoke(target); error("Permission exception swallowed") }
        catch (expected: InvocationTargetException) { check(expected.targetException is SecurityException) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { pool.submit<Boolean> {
                repeat(100) { check(method.invoke(target, 2, 7L, 1.5, "probe") == "root:probe:11.5") }
                true
            } }
            futures.forEach { check(it.get(5, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
        check(api.deoptimize(method)) { "Real ART deoptimization failed" }
        check(target.value(2, 7L, 1.5, "probe") == "root:probe:11.5")
        handle.unhook(); other.unhook()
        check(target.value(2, 7L, 1.5, "probe") == "probe:10.5")
        check(method.invoke(target, 2, 7L, 1.5, "probe") == "probe:10.5")
    }

    private fun writeReceipt(directory: String, report: JSONObject) {
        val staging = File(directory, "receipt.tmp")
        staging.writeText(report.toString())
        check(staging.renameTo(File(directory, "receipt.json"))) { "Cannot publish root receipt" }
    }
}

class ProbeTarget {
    fun value(a: Int, b: Long, c: Double, label: String) = "$label:${a + b + c}"
    fun denied(): Nothing = throw SecurityException("probe permission check")
}

/** Disposable VM used to prove remote loading before touching the system process. */
object ProbeHost {
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args.single())
        File(directory, "host.pid").writeText(Process.myPid().toString())
        val started = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - started < 120_000) {
            File(directory, "heartbeat").writeText(android.os.SystemClock.elapsedRealtime().toString())
            if (File(directory, "receipt.json").isFile) {
                // A healthy original caller after hook removal is part of acceptance.
                check(ProbeTarget().value(2, 7L, 1.5, "host") == "host:10.5")
                File(directory, "host-complete").writeText("original call restored")
                return
            }
            Thread.sleep(100)
        }
        error("Root probe injection timed out")
    }
}
