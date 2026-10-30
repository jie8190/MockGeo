package com.mockgeo.module

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import java.util.concurrent.ConcurrentHashMap

/**
 * `requestLocationUpdates(..., PendingIntent)` 的主动投递。
 *
 * 框架语义（LMS#requestLocationUpdates 的 PendingIntent 分支）：
 * 每次定位事件都会 `PendingIntent.send(ctx, 0, new Intent().putExtra(KEY_LOCATION_CHANGED, location))`，
 * 因此只要真实 provider 不产帧（室内、模拟器、GPS 关闭），订阅方就一直收不到东西。
 * 这里补齐"合成节奏"：按 reportInterval 主动 send 假位置，直到 enabled=false 或订阅被注销。
 *
 * 与 ListenerPusher 的差异：
 * - 目标不是 ILocationListener 代理，而是 PendingIntent（可能是 Broadcast/Service/Activity）；
 * - PendingIntent 被创建方取消时 send 抛 CanceledException → 自动剔除，避免无限失败；
 * - 一次性请求（getCurrentLocation + PendingIntent）走 [deliverOnce]，不进注册表。
 */
internal object PendingIntentPusher {

    private const val MAX_FAILURES = 3

    private data class Entry(
        val intent: PendingIntent,
        val provider: String,
        val budget: DeliveryBudget,
        val onComplete: () -> Unit,
        @Volatile var lastLocation: Location? = null,
        @Volatile var failures: Int = 0,
    )

    /** key = PendingIntent（equals/hashCode 按底层 Intent 过滤条件比较，注销时能命中）。 */
    private val registry = ConcurrentHashMap<PendingIntent, Entry>()

    fun register(intent: PendingIntent?, provider: String, policy: RequestPolicy = RequestPolicy(), onComplete: () -> Unit = {}) {
        if (intent == null) return
        val p = ProviderResolver.normalize(provider) ?: ProviderResolver.GPS
        if (registry.put(intent, Entry(intent, p, DeliveryBudget(policy, android.os.SystemClock.elapsedRealtime()), onComplete)) == null) {
            ModuleLog.d("pending-intent registered (${registry.size}) provider=$p")
        }
    }

    fun unregister(intent: PendingIntent?) {
        if (intent == null) return
        if (registry.remove(intent) != null) {
            ModuleLog.d("pending-intent unregistered (${registry.size})")
        }
    }

    /** 一次性投递（getCurrentLocation + PendingIntent）：只发一帧，不进注册表。 */
    fun deliverOnce(intent: PendingIntent?, provider: String): Boolean {
        if (intent == null) return false
        val cfg = ModuleConfig.snapshot()
        if (!cfg.enabled) return false
        val ctx = StatusWriter.systemContextOrNull() ?: return false
        val loc = FakeLocationFactory.create(ProviderResolver.normalize(provider) ?: ProviderResolver.GPS, cfg)
        val ok = send(ctx, intent, loc)
        if (ok) HookDiagnostics.pendingPushes.incrementAndGet()
        return ok
    }

    /** 推送一轮（由 ListenerPusher 的推送线程调用，保证与监听器同一节奏）。 */
    fun pushOnce(cfg: ModuleConfig) {
        if (registry.isEmpty()) return
        val ctx = StatusWriter.systemContextOrNull() ?: return
        val identity = Binder.clearCallingIdentity()
        try {
            val iterator = registry.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                val current = ModuleConfig.snapshot()
                // 配置变更/停止：立即收敛，避免用旧快照继续推
                if (!current.enabled) return
                if (LocationDelivery.config(entry.intent.creatorUid) == null) continue
                val now = android.os.SystemClock.elapsedRealtime()
                if (entry.budget.exhausted(now)) { if (registry.remove(entry.intent, entry)) entry.onComplete(); continue }
                val location = runCatching {
                    FakeLocationFactory.create(entry.provider, current)
                }.getOrNull() ?: continue
                if (!entry.budget.ready(now, entry.lastLocation?.distanceTo(location))) continue
                if (registry[entry.intent] !== entry) continue
                if (send(ctx, entry.intent, location)) {
                    HookDiagnostics.pendingPushes.incrementAndGet()
                    entry.failures = 0
                    entry.budget.delivered(now)
                    entry.lastLocation = location
                    if (entry.budget.exhausted(now) && registry.remove(entry.intent, entry)) entry.onComplete()
                } else if (++entry.failures >= MAX_FAILURES) {
                    iterator.remove()
                    ModuleLog.d("pending-intent dropped after $MAX_FAILURES failures (${registry.size} left)")
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** 清空注册表（STOP 时调用，避免停止后残留条目）。 */
    fun clear() {
        if (registry.isNotEmpty()) {
            registry.clear()
            ModuleLog.d("pending-intent registry cleared")
        }
    }

    fun size(): Int = registry.size

    private fun send(ctx: Context, intent: PendingIntent, location: Location): Boolean = try {
        intent.send(ctx, 0, Intent().putExtra(LocationManager.KEY_LOCATION_CHANGED, location))
        true
    } catch (canceled: PendingIntent.CanceledException) {
        false
    } catch (t: Throwable) {
        ModuleLog.w("pending-intent send failed", t)
        false
    }
}
