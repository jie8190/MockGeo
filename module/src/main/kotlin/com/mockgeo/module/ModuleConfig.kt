package com.mockgeo.module

/**
 * 模块侧配置快照（不可变快照 + 同步发布）。
 *
 * 设计依据：docs/06 §4「单一真相」——hook 侧持有 enabled，App 只是投影。
 * system_server 重启后 enabled 默认 false（fail-safe：宁可少模拟一秒）。
 */
class ModuleConfig private constructor(
    val enabled: Boolean,
    val revision: Long,
    val lat: Double,
    val lng: Double,
    val altitude: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float,
    val satellites: Int,
    val jitterEnabled: Boolean,
    val jitterMeters: Double,
    val reportIntervalMs: Long,
    val reportMock: Boolean = true,
) {

    fun with(
        revision: Long = this.revision,
        enabled: Boolean = this.enabled,
        lat: Double = this.lat,
        lng: Double = this.lng,
        altitude: Double = this.altitude,
        accuracyM: Float = this.accuracyM,
        speedMps: Float = this.speedMps,
        bearingDeg: Float = this.bearingDeg,
        satellites: Int = this.satellites,
        jitterEnabled: Boolean = this.jitterEnabled,
        jitterMeters: Double = this.jitterMeters,
        reportIntervalMs: Long = this.reportIntervalMs,
        reportMock: Boolean = this.reportMock,
    ): ModuleConfig = ModuleConfig(
        enabled = enabled,
        revision = revision,
        lat = lat,
        lng = lng,
        altitude = altitude,
        accuracyM = accuracyM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        satellites = satellites,
        jitterEnabled = jitterEnabled,
        jitterMeters = jitterMeters,
        reportIntervalMs = reportIntervalMs,
        reportMock = reportMock,
    )

    companion object {
        @Volatile
        private var current: ModuleConfig = ModuleConfig(
            enabled = false,
            revision = 0,
            lat = 0.0,
            lng = 0.0,
            altitude = 0.0,
            accuracyM = 5f,
            speedMps = 0f,
            bearingDeg = 0f,
            satellites = 12,
            jitterEnabled = false,
            jitterMeters = 0.0,
            reportIntervalMs = 1000L,
        )

        /** 模块加载时刻（uptime 统计用）。 */
        @Volatile
        var loadedAtMs: Long = 0L

        /** hook 安装成功数（诊断回执）。 */
        @Volatile
        var hooksInstalled: Int = 0
        @Volatile var hooksReady: Boolean = false
        @Volatile var hookDetail: String = "not installed"
        @Volatile var leaseExpirations: Long = 0L
            private set

        const val LEASE_TIMEOUT_MS = 15_000L
        private var leaseExpiresAtMs = 0L

        @Synchronized
        fun snapshot(nowMs: Long = android.os.SystemClock.elapsedRealtime()): ModuleConfig {
            if (current.enabled && nowMs >= leaseExpiresAtMs) {
                current = current.with(enabled = false)
                leaseExpirations++
                ModuleLog.w("controller lease expired; simulation disabled")
            }
            return current
        }

        @Synchronized
        fun leaseRemainingMs(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Long =
            if (current.enabled) (leaseExpiresAtMs - nowMs).coerceAtLeast(0L) else 0L

        /**
         * 应用新配置并盖章 revision。revision 单调：只接受更新的 revision
         * （防乱序回写，HLocation applied_revision 语义）。
         * 返回应用后的快照。
         */
        @Synchronized
        fun apply(revision: Long, nowMs: Long = android.os.SystemClock.elapsedRealtime(),
                  mutate: (ModuleConfig) -> ModuleConfig): ModuleConfig {
            val old = current
            if (revision <= old.revision) {
                ModuleLog.d("reject stale revision=$revision <= ${old.revision}")
                return old
            }
            val next = mutate(old).with(revision = revision)
            require(next.lat.isFinite() && next.lat in -90.0..90.0 &&
                next.lng.isFinite() && next.lng in -180.0..180.0)
            require(next.altitude.isFinite() && next.accuracyM.isFinite() && next.accuracyM > 0)
            require(next.speedMps.isFinite() && next.speedMps >= 0 && next.bearingDeg.isFinite())
            require(next.satellites in 0..64 && next.jitterMeters.isFinite() && next.jitterMeters >= 0)
            require(next.reportIntervalMs in 200L..5000L)
            current = next
            leaseExpiresAtMs = if (next.enabled) nowMs + LEASE_TIMEOUT_MS else 0L
            return next
        }

        /** 紧急 STOP 翻转 enabled 并推进 revision，拒绝旧命令重放。 */
        @Synchronized
        fun setEnabled(enabled: Boolean): ModuleConfig {
            val old = current
            if (old.enabled == enabled) return old
            val next = old.with(enabled = enabled, revision = old.revision + 1)
            current = next
            leaseExpiresAtMs = if (enabled) android.os.SystemClock.elapsedRealtime() + LEASE_TIMEOUT_MS else 0L
            return next
        }

        /** 测试/自检用：直接替换。 */
        @Synchronized
        fun replace(cfg: ModuleConfig) {
            current = cfg
            leaseExpiresAtMs = android.os.SystemClock.elapsedRealtime() + LEASE_TIMEOUT_MS
        }

        /** 测试隔离用：恢复出厂默认（enabled=false）。 */
        @Synchronized
        fun resetForTest() {
            leaseExpiresAtMs = 0L
            current = ModuleConfig(
                enabled = false,
                revision = 0,
                lat = 0.0,
                lng = 0.0,
                altitude = 0.0,
                accuracyM = 5f,
                speedMps = 0f,
                bearingDeg = 0f,
                satellites = 12,
                jitterEnabled = false,
                jitterMeters = 0.0,
                reportIntervalMs = 1000L,
            )
            loadedAtMs = 0L
            hooksInstalled = 0
            hooksReady = false
            hookDetail = "not installed"
            leaseExpirations = 0L
        }
    }
}

/** Startup messages and failures also reach LSPosed; per-frame debug stays in logcat. */
object ModuleLog {
    private const val TAG = "MockGeoModule"
    @Volatile private var framework: ((Int, String, Throwable?) -> Unit)? = null

    fun bindFramework(sink: ((Int, String, Throwable?) -> Unit)?) { framework = sink }

    fun i(msg: String) {
        android.util.Log.i(TAG, msg)
        runCatching { framework?.invoke(android.util.Log.INFO, msg, null) }
    }

    fun d(msg: String) {
        android.util.Log.d(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        android.util.Log.w(TAG, msg, t)
        runCatching { framework?.invoke(android.util.Log.WARN, msg, t) }
    }

    fun e(msg: String, t: Throwable? = null) {
        android.util.Log.e(TAG, msg, t)
        runCatching { framework?.invoke(android.util.Log.ERROR, msg, t) }
    }
}
