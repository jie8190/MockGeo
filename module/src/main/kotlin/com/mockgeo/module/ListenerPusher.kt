package com.mockgeo.module

import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听器注册表 + 推送线程（对应 2.1.4 的 xq0+st、HLocation 的 a2）。
 *
 * 作用：enabled=true 时按 reportInterval 向所有已注册的 ILocationListener
 * 主动推送假 Location——覆盖"室内无真实定位上报"的场景（真实 provider 不报帧，
 * listener 就收不到任何东西）。
 *
 * 与 2.1.4 的 st 线程的差异（docs/06 §3.2 R4 的修复）：
 * - 读取原子配置快照（单一真相），enabled=false 即退出；
 * - 投递用反射多路径（onLocationChanged(LocationResult) / (Location)），
 *   连续失败 5 次的 listener 从注册表剔除（不无限重试坏 binder）。
 */
object ListenerPusher {

    private const val MAX_FAILURES = 5

    private data class Entry(
        val listener: Any,
        val provider: String,
        val budget: DeliveryBudget,
        val onComplete: () -> Unit,
        val ownerUid: Int,
        @Volatile var lastLocation: Location? = null,
        @Volatile var failures: Int = 0,
    )

    /** key = 监听器对象（代理对象或原始 binder，均为引用相等语义）。 */
    private val registry = ConcurrentHashMap<Pair<Any, String>, Entry>()

    @Volatile
    private var pusherThread: Thread? = null

    private val iLocationListenerClass: Class<*>? by lazy {
        runCatching { Class.forName("android.location.ILocationListener") }.getOrNull()
    }

    /** 由 HookInstaller 在订阅/注销 hook 里调用。 */
    internal fun register(arg: Any?, provider: String = LocationManager.GPS_PROVIDER, policy: RequestPolicy = RequestPolicy(), onComplete: () -> Unit = {}, ownerUid: Int = android.os.Binder.getCallingUid()) {
        try {
            val target = asListener(arg) ?: return
            val key = binderKey(target) to provider
            registry[key] = Entry(target, provider, DeliveryBudget(policy, android.os.SystemClock.elapsedRealtime()), onComplete, ownerUid)
            ModuleLog.d("listener registered (${registry.size})")
        } catch (t: Throwable) {
            ModuleLog.w("register failed", t)
        }
    }

    fun unregister(arg: Any?) {
        try {
            val target = asListener(arg) ?: return
            val binder = binderKey(target)
            if (registry.keys.removeAll { it.first == binder }) {
                ModuleLog.d("listener unregistered (${registry.size})")
            }
        } catch (t: Throwable) {
            ModuleLog.w("unregister failed", t)
        }
    }

    private fun binderKey(target: Any): Any = if (target is IBinder) target else
        runCatching { iLocationListenerClass?.getMethod("asBinder")?.invoke(target) }.getOrNull() ?: target

    /**
     * 参数 → ILocationListener 目标对象。
     * 服务端 Java 方法收到的是 ILocationListener 代理（实现 IInterface）；
     * 防御性兼容原始 IBinder（descriptor 校验）。
     */
    private fun asListener(arg: Any?): Any? {
        if (arg == null) return null
        val iface = iLocationListenerClass ?: return null
        if (iface.isInstance(arg)) return arg
        if (arg is IBinder) {
            val desc = runCatching { arg.interfaceDescriptor }.getOrNull()
            if (desc == "android.location.ILocationListener") return arg
        }
        return null
    }

    /** 配置变化（命令应用后调用）：确保线程在跑。 */
    fun onConfigChanged(cfg: ModuleConfig) {
        if (cfg.enabled) {
            startIfNeeded()
        } else {
            pusherThread?.interrupt()
            GnssPusher.onStopped()
            // Subscriptions belong to clients; STOP suspends synthesis, not their real subscriptions.
        }
    }

    @Synchronized
    private fun startIfNeeded() {
        if (pusherThread?.isAlive == true) return
        val t = Thread({ pushLoop() }, "MockGeo-Pusher")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY + 1
        pusherThread = t
        t.start()
    }

    private fun pushLoop() {
        ModuleLog.d("pusher started")
        try {
            while (!Thread.currentThread().isInterrupted) {
                val cfg = ModuleConfig.snapshot()
                if (!cfg.enabled) break
                try { pushOnce(cfg) } catch (t: Throwable) { ModuleLog.w("push cycle failed", t) }
                Thread.sleep(cfg.reportIntervalMs.coerceIn(200L, 1000L))
            }
        } catch (_: InterruptedException) {
            // Stop wakes the thread immediately.
        } finally {
            val finalConfig = ModuleConfig.snapshot()
            if (!finalConfig.enabled) { GnssPusher.onStopped(); StatusWriter.publishScopeState(finalConfig) }
            synchronized(this) {
                if (pusherThread === Thread.currentThread()) pusherThread = null
                // A new start may have raced with the retiring thread.
                if (ModuleConfig.snapshot().enabled) startIfNeeded()
            }
            ModuleLog.d("pusher exited")
        }
    }

    private fun pushOnce(cfg: ModuleConfig) {
        // GNSS/NMEA 监听器推送（system_server 侧，覆盖所有 App）
        runCatching { GnssPusher.pushOnce(cfg) }
            .onFailure { ModuleLog.w("gnss push cycle failed", it) }
        // 先推 PendingIntent 订阅：即使没有任何 ILocationListener，也要保持合成节奏
        if (registry.isEmpty()) {
            runCatching { PendingIntentPusher.pushOnce(cfg) }
                .onFailure { ModuleLog.w("pending push cycle failed", it) }
            return
        }
        val locations = mutableMapOf<String, Location>()
        val it = registry.entries.iterator()
        while (it.hasNext()) {
            val current = ModuleConfig.snapshot()
            if (!current.enabled) return
            val item = it.next()
            val e = item.value
            if (LocationDelivery.config(e.ownerUid) == null) continue
            val now = android.os.SystemClock.elapsedRealtime()
            if (e.budget.exhausted(now)) { if (registry.remove(item.key, e)) e.onComplete(); continue }
            val loc = locations.getOrPut(e.provider) { FakeLocationFactory.create(e.provider, current) }
            if (!e.budget.ready(now, e.lastLocation?.distanceTo(loc))) continue
            if (registry[item.key] !== e) continue
            val ok = runCatching { deliver(e.listener, loc) }.getOrDefault(false)
            if (ok) {
                HookDiagnostics.pushes.incrementAndGet()
                e.failures = 0
                e.budget.delivered(now)
                e.lastLocation = loc
                if (e.budget.exhausted(now)) {
                    if (registry.remove(item.key, e)) e.onComplete()
                    ModuleLog.d("one-shot listener delivered and released (${registry.size} left)")
                }
            } else if (++e.failures >= MAX_FAILURES) {
                it.remove()
                ModuleLog.d("listener dropped after $MAX_FAILURES failures (${registry.size} left)")
            }
        }
        // PendingIntent 订阅与监听器共用同一推送节奏
        runCatching { PendingIntentPusher.pushOnce(cfg) }
            .onFailure { ModuleLog.w("pending push cycle failed", it) }
    }

    /** 反射多路径投递（2.1.4 xq0 的做法，收敛为两条主路径）。 */
    private fun deliver(listenerObj: Any, loc: Location): Boolean {
        // 原始 binder → asInterface 包装；代理对象直接用
        val target = runCatching {
            if (listenerObj is IBinder) {
                val stub = Class.forName("android.location.ILocationListener\$Stub")
                stub.getMethod("asInterface", IBinder::class.java).invoke(null, listenerObj)
            } else {
                listenerObj
            }
        }.getOrNull() ?: listenerObj

        // Standard Android 12+ AIDL uses List<Location>, IRemoteCallback.
        val methods = iLocationListenerClass?.methods ?: target.javaClass.methods
        for (m in methods) {
            if (m.name != "onLocationChanged") continue
            val params = m.parameterTypes
            if (params.isNotEmpty() && List::class.java.isAssignableFrom(params[0])) {
                val delivered = runCatching {
                    when (params.size) {
                        1 -> m.invoke(target, listOf(Location(loc)))
                        2 -> m.invoke(target, listOf(Location(loc)), null)
                        else -> return@runCatching false
                    }
                    true
                }.getOrDefault(false)
                if (delivered) return true
            }
        }
        // ROM variants may use LocationResult[, callback].
        val result = buildLocationResult(loc)
        if (result != null) {
            for (m in methods) {
                if (m.name != "onLocationChanged") continue
                val p = m.parameterTypes
                try {
                    when {
                        p.size == 1 && p[0].isInstance(result) -> {
                            m.invoke(target, result)
                            return true
                        }

                        p.size == 2 && p[0].isInstance(result) -> {
                            m.invoke(target, result, null)
                            return true
                        }
                    }
                } catch (t: Throwable) {
                    // 尝试下一个签名
                }
            }
        }

        // 路径 2：legacy onLocationChanged(Location)
        for (m in methods) {
            if (m.name == "onLocationChanged" && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Location::class.java
            ) {
                return runCatching { m.invoke(target, loc); true }.getOrDefault(false)
            }
        }
        return false
    }

    /** 反射构造 LocationResult（API 26~30 为 @hide，编译期不可直连）。 */
    private fun buildLocationResult(loc: Location): Any? = runCatching {
        val cls = Class.forName("android.location.LocationResult")
        val create: Method = try {
            cls.getMethod("create", List::class.java)
        } catch (e: NoSuchMethodException) {
            cls.getMethod("wrap", List::class.java)
        }
        create.invoke(null, listOf(loc))
    }.getOrNull()
}
