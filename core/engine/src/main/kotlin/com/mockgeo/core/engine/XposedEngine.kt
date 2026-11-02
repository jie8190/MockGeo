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
    private val controlProvider: String = ControlPlane.PROVIDER,
    private val expectedBackend: String = "xposed",
) : SystemHookControl {

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
    private val probeMutex = Mutex()
    @Volatile private var directStatus: ModuleStatus? = null
    private val probe = ControlChannelProbe(clock, expectedBackend, log, send = { nonce ->
        val extras = Bundle().apply { putString(ControlPlane.KEY_REQUEST_NONCE, nonce) }
        sendCommand(ControlPlane.CMD_PING, extras)
    }, read = { queryStatus() })

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
        val checked = checkChannel()
        val reachable = checked.reachable
        val status = checked.status
        val installedVersion = runCatching {
            context.packageManager.getPackageInfo("com.mockgeo.module", 0).versionName
        }.getOrNull()
        return listOf(
            CheckResult(
                name = "系统控制通道",
                ok = reachable,
                message = if (reachable) null else checked.detail,
                actionKey = "NONE",
            ),
            CheckResult("系统定位钩子", reachable && status?.hooksInstalled == true,
                if (reachable && status?.hooksInstalled == true) "模块已在系统中加载"
                else if (reachable && !status?.hookDetail.isNullOrBlank()) "系统定位入口未完整加载：${status?.hookDetail}"
                else "未确认系统钩子，请检查模块作用域及加载状态"),
        ) + ModuleReadiness.checks(installedVersion, reachable, status?.moduleVersion)
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
     * 判定：命令完成且收到本次检查之后的新鲜状态；短暂等待异步回执，
     * 将命令/读取异常及超时阶段记录到应用诊断，不能把缺少回执等同于未勾选模块。
     */
    override suspend fun pingModule(): Boolean = checkChannel().reachable

    private suspend fun checkChannel(): ControlChannelProbe.Result = probeMutex.withLock {
        probe.check()
    }

    override fun statusSnapshot(): ModuleStatus? = readStatus()

    /** 读模块状态（App 自己的 provider，本地读）。 */
    internal fun readStatus(): ModuleStatus? = runCatching { queryStatus() }
        .onFailure { log.log(0, SimLogger.WARN, TAG, "status query failed backend=$expectedBackend", it) }
        .getOrNull()?.takeIf { it.backend == expectedBackend || (expectedBackend == "xposed" && it.backend.isBlank()) }

    private fun sendCommand(command: String, extras: Bundle): ModuleStatus? {
        val nonce = extras.getString(ControlPlane.KEY_REQUEST_NONCE)
            ?: java.util.UUID.randomUUID().toString().replace("-", "").also {
                extras.putString(ControlPlane.KEY_REQUEST_NONCE, it)
            }
        checkNotNull(lm) { "LocationManager unavailable" }.sendExtraCommand(controlProvider, command, extras)
        val reply = extras.getBundle(ControlPlane.KEY_STATUS_SNAPSHOT) ?: return null
        @Suppress("DEPRECATION")
        val receipt = DirectStatusReceipt.decode(reply.keySet().associateWith { reply.get(it) },
            nonce, expectedBackend, clock.currentTimeMillis()) ?: return null
        directStatus = receipt
        return receipt
    }

    private fun queryStatus(): ModuleStatus? {
        val direct = directStatus?.takeIf { it.isFresh(clock.currentTimeMillis()) }
        val provider = try { queryProviderStatus() } catch (error: Exception) {
            if (direct == null) throw error
            return direct
        }
        return if (direct != null && (provider == null || provider.backend != expectedBackend ||
                direct.updatedAt >= provider.updatedAt)) direct else provider
    }

    private fun queryProviderStatus(): ModuleStatus? = context.contentResolver.query(
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
                hookDetail = idx(ControlPlane.COL_HOOK_DETAIL).let { if (it < 0) "" else c.getString(it).orEmpty() },
                leaseRemainingMs = idx(ControlPlane.COL_LEASE_REMAINING_MS).let { if (it < 0) 0 else c.getLong(it) },
                leaseExpirations = idx(ControlPlane.COL_LEASE_EXPIRATIONS).let { if (it < 0) 0 else c.getLong(it) },
                rewrittenCallbacks = idx(ControlPlane.COL_REWRITTEN_CALLBACKS).let { if (it < 0) 0 else c.getLong(it) },
                unmatchedCallbacks = idx(ControlPlane.COL_UNMATCHED_CALLBACKS).let { if (it < 0) 0 else c.getLong(it) },
                uptimeMs = c.getLong(idx(ControlPlane.COL_UPTIME_MS)),
                updatedAt = c.getLong(idx(ControlPlane.COL_UPDATED_AT)),
                moduleVersion = c.getString(idx(ControlPlane.COL_MODULE_VERSION)) ?: "",
                lastCalls = idx(ControlPlane.COL_LAST_CALLS).let { if (it >= 0) c.getLong(it) else 0 },
                currentCalls = idx(ControlPlane.COL_CURRENT_CALLS).let { if (it >= 0) c.getLong(it) else 0 },
                listenerCalls = idx(ControlPlane.COL_LISTENER_CALLS).let { if (it >= 0) c.getLong(it) else 0 },
                pushes = idx(ControlPlane.COL_PUSHES).let { if (it >= 0) c.getLong(it) else 0 },
                reportMock = idx(ControlPlane.COL_REPORT_MOCK).let { it < 0 || c.getInt(it) != 0 },
                backend = idx(ControlPlane.COL_BACKEND).let { if (it < 0) "" else c.getString(it).orEmpty() },
                processId = idx(ControlPlane.COL_PROCESS_ID).let { if (it < 0) 0 else c.getInt(it) },
                instanceNonce = idx(ControlPlane.COL_INSTANCE_NONCE).let { if (it < 0) "" else c.getString(it).orEmpty() },
                responseNonce = idx(ControlPlane.COL_RESPONSE_NONCE).let { if (it < 0) "" else c.getString(it).orEmpty() },
            )
        }

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
                val direct = sendCommand(ControlPlane.CMD_SET, extras)
                // 辅助回执：inout 时模块已写入 ack_*；缺失不视为失败（主判据在状态 provider）
                if (extras.containsKey(ControlPlane.KEY_ACK_SUCCESS) &&
                    !extras.getBoolean(ControlPlane.KEY_ACK_SUCCESS)
                ) {
                    throw IllegalStateException(
                        "module rejected: ${extras.getString(ControlPlane.KEY_ACK_ERROR)}",
                    )
                }
                // 主回执：状态 provider 确认
                val st = direct ?: readStatus()
                    ?: throw IllegalStateException("no status receipt from module (module not active?)")
                if (!st.isFresh(clock.currentTimeMillis())) {
                    throw IllegalStateException("status receipt stale (module restarted?)")
                }
                if (enabled && !st.hooksInstalled) {
                    throw IllegalStateException("系统定位入口未完整加载：${st.hookDetail}")
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
        val backend: String = "",
        val processId: Int = 0,
        val instanceNonce: String = "",
        val responseNonce: String = "",
        val hookDetail: String = "",
        val leaseRemainingMs: Long = 0L,
        val leaseExpirations: Long = 0L,
        val rewrittenCallbacks: Long = 0L,
        val unmatchedCallbacks: Long = 0L,
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
