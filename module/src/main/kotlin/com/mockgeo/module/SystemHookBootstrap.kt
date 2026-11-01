package com.mockgeo.module

/** One bounded worker per process, independent of the framework's early service callback. */
internal class SystemHookBootstrap(
    private val localServiceLoader: () -> ClassLoader?,
    private val install: (ClassLoader) -> Boolean,
    private val schedule: (() -> Unit) -> Unit,
    private val report: (String, Throwable?) -> Unit,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val lock = Any()
    private var earlyLoader: ClassLoader? = null
    private var scheduled = false
    private var complete = false

    fun onSystemServerStarting(loader: ClassLoader) {
        synchronized(lock) { earlyLoader = loader }
        attempt()
        discoverWhenReady()
    }

    fun discoverWhenReady() {
        synchronized(lock) {
            if (scheduled || complete) return
            scheduled = true
        }
        schedule {
            for (delay in listOf(5_000L, 10_000L, 20_000L, 30_000L)) {
                try { pause(delay) } catch (_: InterruptedException) { return@schedule }
                if (attempt()) return@schedule
            }
            report("system hook discovery exhausted; local service or required hooks unavailable", null)
        }
    }

    private fun attempt(): Boolean = synchronized(lock) {
        if (complete) return true
        try {
            val local = localServiceLoader()
            // Only the explicit framework callback authorizes early class-name discovery.
            // Package-ready fallback must first find a real LOCAL ILocationManager Binder.
            val loader = local ?: earlyLoader ?: return false
            val ready = install(loader)
            complete = local != null && ready
        } catch (error: Throwable) {
            report("system hook installation failed", error)
        }
        complete
    }

    companion object {
        fun isSystemHostCandidate(systemServer: Boolean, uid: Int, packageName: String): Boolean =
            systemServer || (uid == 1000 && packageName in setOf(
                "android", "com.hihonor.systemserver", "com.huawei.systemserver",
            ))
    }
}
