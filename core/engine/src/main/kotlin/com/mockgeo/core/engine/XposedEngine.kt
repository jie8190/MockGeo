package com.mockgeo.core.engine

import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.ControlPlane
import com.mockgeo.core.model.EngineCapability
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.InjectionEngine
import com.mockgeo.core.model.SimConfig
import com.mockgeo.core.model.SimError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 路线 B 引擎（App 侧）：通过 sendExtraCommand 控制面驱动 LSPosed 模块。
 *
 * 与两个原 App 的差异（docs/06 §3.3 的修复全部落地）：
 * 1. 停止前**不**推 enabled=true（2.1.4 R1）；
 * 2. 每条命令后读状态回执（状态 provider 为主、Bundle 回写为辅），失败重试 3×200ms（2.1.4 R2）；
 * 3. 停止后确认 enabled=false 才算成功，否则如实报错（2.1.4 R3）；
 * 4. 运行中看门狗每 5s 对账，发现分叉立即重发期望状态（docs/06 §3.3 ③）；
 * 5. revision 单调：过期回执丢弃（HLocation applied_revision 语义）。
 */
class XposedEngine(
    private val context: Context,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val log: SimLogger,
) : InjectionEngine {

    private val lm = context.getSystemService(LocationManager::class.java)
    private val statusUri: Uri = Uri.parse(ControlPlane.STATUS_URI)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    /** 配置修订号：单调递增，模块侧只接受更新的。 */
    private val revision = java.util.concurrent.atomic.AtomicLong(0)

    /** 最近一次成功发布的配置（看门狗重发用）。 */
    @Volatile
    private var lastPublished: SimConfig? = null

    @Volatile
    private var desiredEnabled: Boolean = false

    private var watchdog: Job? = null
    private val publishMutex = Mutex()

    override fun state(): StateFlow<EngineState> = _state

    override suspend fun capability(): EngineCapability = EngineCapability(
        engineId = ENGINE_ID,
        displayName = "LSPosed 模块（系统级）",
        requiresMockAppOp = false,
        requiresExternalModule = true,
        supportsNetworkProvider = false,
        supportsStreamingUpdate = true,
    )

    override suspend fun selfCheck(): List<CheckResult> {
        val reachable = pingModule()
        val status = readStatus()
        val installedVersion = runCatching {
            context.packageManager.getPackageInfo("com.mockgeo.module", 0).versionName
        }.getOrNull()
        val loadedVersionReady = reachable && moduleVersionSupported(status?.moduleVersion) &&
            installedVersion == status?.moduleVersion
        return listOf(
            CheckResult(
                name = "系统控制通道",
                ok = reachable,
                message = if (reachable) null else "模块未激活或未安装（需在 LSPosed 中启用并勾选 system 作用域）",
                actionKey = "NONE",
            ),
            CheckResult("系统定位钩子", reachable && status?.hooksInstalled == true,
                if (reachable && status?.hooksInstalled == true) "模块已在系统中加载" else "未确认系统钩子，请检查模块作用域及加载状态"),
            CheckResult("模块版本", loadedVersionReady,
                if (!reachable) "尚未收到加载版本"
                else if (loadedVersionReady) "已安装并加载 ${status?.moduleVersion}"
                else "已安装 ${installedVersion ?: "未知"}，系统加载 ${status?.moduleVersion}；需 $MIN_MODULE_VERSION 或更高版本，请在方便时手动重启"),
        )
    }

    override suspend fun start(cfg: SimConfig): Result<Unit> {
        desiredEnabled = true
        val r = publish(cfg, enabled = true)
        if (r.isSuccess) {
            lastPublished = cfg
            desiredEnabled = true
            startWatchdog()
            _state.value = EngineState.Running(clock.elapsedRealtime(), cfg)
        } else {
            desiredEnabled = false
            _state.value = EngineState.Failed(
                r.exceptionOrNull().toSimError(),
                retryable = true,
                detail = r.exceptionOrNull()?.message,
            )
        }
        return r
    }

    override suspend fun update(cfg: SimConfig): Result<Unit> {
        if (!desiredEnabled) return Result.failure(IllegalStateException("engine not started"))
        // tick 更新：失败不致命（下一帧会重试），但要看门狗兜底
        return publish(cfg, enabled = true)
    }

    override suspend fun stop(): Result<Unit> {
        // 事务化停止（docs/06 §3.3 ②）：
        // (1) 先停看门狗（断源：不再有新帧发布）
        watchdog?.cancel()
        watchdog = null
        desiredEnabled = false
        // (2) 广播 enabled=false，带重试与回执校验
        val cfg = lastPublished
        val r = publish(cfg, enabled = false)
        if (r.isFailure) {
            _state.value = EngineState.Failed(
                r.exceptionOrNull().toSimError(),
                retryable = true,
                detail = r.exceptionOrNull()?.message,
            )
            return r
        }
        // (3) 确认（3×200ms）：模块侧 enabled=false 且状态新鲜
        val confirmed = confirmDisabled()
        if (!confirmed) {
            // 下降沿 fail-safe 失败：如实报错，不假装停止
            val err = "stop broadcast ok but module still reports enabled=true or stale"
            _state.value = EngineState.Failed(SimError.ACK_TIMEOUT, retryable = true, detail = err)
            return Result.failure(IllegalStateException(err))
        }
        lastPublished = null
        _state.value = EngineState.Idle
        return Result.success(Unit)
    }

    // ---- 控制面原语 ----

    /**
     * PING：模块是否接管了控制面。
     * 判定：sendExtraCommand 不抛（未知 provider 会抛 IllegalArgumentException），
     * 且状态 provider 数据新鲜（模块活着且写过状态）。
     */
    suspend fun pingModule(): Boolean {
        val requestedAt = clock.currentTimeMillis()
        val cmdOk = runCatching {
            val extras = Bundle()
            checkNotNull(lm).sendExtraCommand(ControlPlane.PROVIDER, ControlPlane.CMD_PING, extras)
            // inout 时还能拿到 pong；仅作加分项，不作必要条件
            extras.containsKey(ControlPlane.KEY_PONG)
        }.isSuccess
        if (!cmdOk) return false
        // 命令没抛异常 ≠ 模块在：用状态新鲜度二次确认
        val st = readStatus()
        return st != null && st.updatedAt >= requestedAt && st.isFresh(clock.currentTimeMillis())
    }

    fun statusSnapshot(): ModuleStatus? = readStatus()

    /** 读模块状态（App 自己的 provider，本地读）。 */
    internal fun readStatus(): ModuleStatus? = runCatching {
        context.contentResolver.query(
            statusUri,
            null,
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val idx = { name: String -> c.getColumnIndex(name) }
            ModuleStatus(
                enabled = c.getInt(idx(ControlPlane.COL_ENABLED)) != 0,
                revision = c.getLong(idx(ControlPlane.COL_REVISION)),
                lat = c.getDouble(idx(ControlPlane.COL_LAT)),
                lng = c.getDouble(idx(ControlPlane.COL_LNG)),
                hooksInstalled = c.getInt(idx(ControlPlane.COL_HOOKS)) != 0,
                uptimeMs = c.getLong(idx(ControlPlane.COL_UPTIME_MS)),
                updatedAt = c.getLong(idx(ControlPlane.COL_UPDATED_AT)),
                moduleVersion = c.getString(idx(ControlPlane.COL_MODULE_VERSION)) ?: "",
                lastCalls = idx(ControlPlane.COL_LAST_CALLS).let { if (it >= 0) c.getLong(it) else 0 },
                currentCalls = idx(ControlPlane.COL_CURRENT_CALLS).let { if (it >= 0) c.getLong(it) else 0 },
                listenerCalls = idx(ControlPlane.COL_LISTENER_CALLS).let { if (it >= 0) c.getLong(it) else 0 },
                pushes = idx(ControlPlane.COL_PUSHES).let { if (it >= 0) c.getLong(it) else 0 },
                reportMock = idx(ControlPlane.COL_REPORT_MOCK).let { it < 0 || c.getInt(it) != 0 },
            )
        }
    }.getOrNull()

    /**
     * 发布配置 + 开关。带重试与回执校验。
     * 成功判据：命令未抛异常 + 状态 provider 显示 enabled 已翻转且 revision ≥ 本次发布值。
     */
    private suspend fun publish(cfg: SimConfig?, enabled: Boolean): Result<Unit> = publishMutex.withLock {
        if (enabled && !desiredEnabled) return@withLock Result.failure(IllegalStateException("stop requested"))
        publishLocked(cfg, enabled)
    }

    private suspend fun publishLocked(cfg: SimConfig?, enabled: Boolean): Result<Unit> {
        // 跨进程重启对齐：App 重启后本地 revision 从头计数，会被模块侧旧 revision 拒绝；
        // 先读模块当前 revision，把本地基线顶到它之上。
        readStatus()?.takeIf { it.isFresh(clock.currentTimeMillis()) }?.let { st ->
            revision.updateAndGet { old -> maxOf(old, st.revision) }
        }
        val rev = revision.incrementAndGet()
        var lastError: Throwable? = null
        repeat(ControlPlane.CMD_RETRIES) { attempt ->
            val r = runCatching {
                val l = checkNotNull(lm) { "LocationManager unavailable" }
                val extras = Bundle().apply {
                    putLong(ControlPlane.KEY_REVISION, rev)
                    putBoolean(ControlPlane.KEY_ENABLED, enabled)
                    if (cfg != null) {
                        putDouble(ControlPlane.KEY_LAT, cfg.position.lat)
                        putDouble(ControlPlane.KEY_LNG, cfg.position.lng)
                        putDouble(ControlPlane.KEY_ALT, cfg.altitude)
                        putFloat(ControlPlane.KEY_ACCURACY, cfg.accuracyM)
                        putFloat(ControlPlane.KEY_SPEED, cfg.speedMps)
                        putFloat(ControlPlane.KEY_BEARING, cfg.bearingDeg)
                        putInt(ControlPlane.KEY_SATellites, cfg.satellites)
                        putBoolean(ControlPlane.KEY_JITTER_ENABLED, cfg.jitterEnabled)
                        putDouble(ControlPlane.KEY_JITTER_METERS, cfg.jitterMeters)
                        putLong(ControlPlane.KEY_REPORT_INTERVAL_MS, 1000L)
                        putBoolean(ControlPlane.KEY_REPORT_MOCK, cfg.reportMock)
                    }
                }
                l.sendExtraCommand(ControlPlane.PROVIDER, ControlPlane.CMD_SET, extras)
                // 辅助回执：inout 时模块已写入 ack_*；缺失不视为失败（主判据在状态 provider）
                if (extras.containsKey(ControlPlane.KEY_ACK_SUCCESS) &&
                    !extras.getBoolean(ControlPlane.KEY_ACK_SUCCESS)
                ) {
                    throw IllegalStateException(
                        "module rejected: ${extras.getString(ControlPlane.KEY_ACK_ERROR)}",
                    )
                }
                // 主回执：状态 provider 确认
                val st = readStatus()
                    ?: throw IllegalStateException("no status receipt from module (module not active?)")
                if (!st.isFresh(clock.currentTimeMillis())) {
                    throw IllegalStateException("status receipt stale (module restarted?)")
                }
                if (st.enabled != enabled) {
                    throw IllegalStateException("module state divergence: enabled=${st.enabled} expected=$enabled")
                }
                if (st.revision < rev) {
                    throw IllegalStateException("stale receipt applied=${st.revision} < rev=$rev")
                }
            }
            if (r.isSuccess) {
                log.log(rev, SimLogger.INFO, TAG, "published enabled=$enabled rev=$rev attempt=$attempt")
                if (enabled) lastPublished = cfg
                return r
            }
            lastError = r.exceptionOrNull()
            log.log(rev, SimLogger.WARN, TAG, "publish attempt $attempt failed", lastError)
            delay(ControlPlane.CMD_RETRY_INTERVAL_MS)
        }
        return Result.failure(lastError ?: IllegalStateException("publish failed"))
    }

    /** 停止确认：轮询状态直到 enabled=false 且新鲜。 */
    private suspend fun confirmDisabled(): Boolean {
        repeat(ControlPlane.STOP_CONFIRM_RETRIES) {
            val st = readStatus()
            if (st != null && st.isFresh(clock.currentTimeMillis()) && !st.enabled) return true
            delay(ControlPlane.STOP_CONFIRM_INTERVAL_MS)
        }
        return false
    }

    /** 运行中看门狗：对账 + 分叉自愈。 */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (desiredEnabled) {
                delay(ControlPlane.WATCHDOG_PERIOD_MS)
                if (!desiredEnabled) break
                // Renew the module lease even when the UI has paused route movement.
                val result = publishMutex.withLock {
                    if (!desiredEnabled) return@withLock Result.success(Unit)
                    publishLocked(lastPublished, enabled = true)
                }
                if (result.isFailure && desiredEnabled) {
                    desiredEnabled = false
                    _state.value = EngineState.Failed(
                        SimError.MODULE_UNREACHABLE, true,
                        result.exceptionOrNull()?.message ?: "module heartbeat failed",
                    )
                    break
                }
            }
        }
    }

    private fun Throwable?.toSimError(): SimError = when (this) {
        null -> SimError.ENGINE_CRASH
        is SecurityException -> SimError.PERMISSION_DENIED
        is IllegalArgumentException -> SimError.MODULE_UNREACHABLE // 未知 provider：模块未接管
        else -> SimError.MODULE_UNREACHABLE
    }

    data class ModuleStatus(
        val enabled: Boolean,
        val revision: Long,
        val lat: Double,
        val lng: Double,
        val hooksInstalled: Boolean,
        val uptimeMs: Long,
        val updatedAt: Long,
        val moduleVersion: String,
        val lastCalls: Long = 0,
        val currentCalls: Long = 0,
        val listenerCalls: Long = 0,
        val pushes: Long = 0,
        val reportMock: Boolean = true,
    ) {
        fun isFresh(nowMs: Long): Boolean =
            updatedAt > 0 && nowMs - updatedAt in 0..ControlPlane.STATUS_FRESH_MS
    }

    companion object {
        private const val TAG = "XposedEngine"
        const val ENGINE_ID = "xposed"
        const val MIN_MODULE_VERSION = "0.3.1"
        internal fun moduleVersionSupported(version: String?): Boolean {
            val current = version?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
            val minimum = listOf(0, 3, 1)
            for (index in minimum.indices) {
                val part = current.getOrElse(index) { 0 }
                if (part != minimum[index]) return part > minimum[index]
            }
            return true
        }
    }
}
