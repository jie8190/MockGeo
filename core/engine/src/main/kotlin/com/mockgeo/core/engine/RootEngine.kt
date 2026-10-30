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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Root-only 注入引擎（对标 FakeLocation 1.5.2 Root 模式 + 2.1.4 双状态三通道 + HLocation applied_revision）。
 *
 * 特点：
 * - 不需要用户在 LSPosed 中启用模块（requiresExternalModule = false）；
 * - 启动前先校验 `su -c id` 确认 `uid=0`（应对 KernelSU 临时授权过期退回 shell 的情况）；
 * - 自动部署 `/data/misc/mockgeo/lib/libmockgeo_art.so` 与豁免配置 `/data/misc/mockgeo/exempt.txt`；
 * - 复用 `sendExtraCommand("mockgeo", CMD_SET/CMD_STOP/CMD_PING, extras)` 控制面与 `StatusProvider` 回执。
 */
class RootEngine(
    private val context: Context,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val log: SimLogger,
    private val shellRunner: RootShellRunner = DefaultRootShellRunner,
) : InjectionEngine {

    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val statusUri: Uri = Uri.parse(ControlPlane.STATUS_URI)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override fun state(): StateFlow<EngineState> = _state

    private val revision = AtomicLong(0L)
    @Volatile private var lastPublished: SimConfig? = null
    @Volatile private var desiredEnabled: Boolean = false
    @Volatile private var artifactsDeployed: Boolean = false

    private var watchdog: Job? = null
    private val publishMutex = Mutex()

    override suspend fun capability(): EngineCapability = EngineCapability(
        engineId = ENGINE_ID,
        displayName = "Root 直连注入（免 LSPosed）",
        requiresMockAppOp = false,
        requiresExternalModule = false,
        supportsNetworkProvider = true,
        supportsStreamingUpdate = true,
    )

    override suspend fun selfCheck(): List<CheckResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<CheckResult>()

        // 1. Root 权限与已驻留环境检查（应对 KernelSU 临时授权过期或已由 root 部署的场景）
        val rootCheck = shellRunner.exec("id", timeoutMs = 3_000L)
        val isRoot = rootCheck.exitCode == 0 && rootCheck.stdout.contains("uid=0")
        if (isRoot && !artifactsDeployed) {
            artifactsDeployed = deployRootEnvironment()
        }
        val reachable = pingModule()
        val status = readStatus()
        val hooksReady = reachable && status?.hooksInstalled == true
        val rootOrHookReady = isRoot || hooksReady
        out += CheckResult(
            name = "Root 权限",
            ok = rootOrHookReady,
            message = when {
                isRoot -> "已获取 Root (uid=0)"
                hooksReady -> "系统级定位钩子已驻留生效（免重复 su 提权）"
                else -> "未获取 Root 权限（请在 KernelSU/Magisk 中授予超级用户权限）"
            },
            actionKey = "NONE",
        )

        // 2. Native ART 引擎自检
        val nativeInited = NativeArtBridge.ensureInitialized()
        out += CheckResult(
            name = "Native ART 引擎",
            ok = nativeInited,
            message = if (nativeInited) NativeArtBridge.diagnostics() else "libmockgeo_art.so 未就绪",
            actionKey = "NONE",
        )

        // 3. 系统控制通道与钩子检查
        out += CheckResult(
            name = "系统控制通道",
            ok = reachable,
            message = if (reachable) null else "system_server 尚未挂载定位钩子",
            actionKey = "NONE",
        )
        out += CheckResult(
            name = "系统定位钩子",
            ok = hooksReady,
            message = if (hooksReady) "系统钩子已就绪" else "系统定位钩子未挂载",
            actionKey = "NONE",
        )
        out
    }

    override suspend fun start(cfg: SimConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val rootId = shellRunner.exec("id", timeoutMs = 3_000L)
        val isRoot = rootId.exitCode == 0 && rootId.stdout.contains("uid=0")
        if (isRoot && !artifactsDeployed) {
            artifactsDeployed = deployRootEnvironment()
        }
        if (!isRoot && !pingModule()) {
            val detail = "Root 授权无效或已过期，且系统定位钩子尚未驻留，请在 KernelSU/Magisk 重新授权"
            _state.value = EngineState.Failed(SimError.PERMISSION_DENIED, retryable = true, detail = detail)
            return@withContext Result.failure(IllegalStateException(detail))
        }

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
        r
    }

    override suspend fun update(cfg: SimConfig): Result<Unit> {
        if (!desiredEnabled) return Result.failure(IllegalStateException("engine not started"))
        return publish(cfg, enabled = true)
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        watchdog?.cancel()
        watchdog = null
        desiredEnabled = false
        val cfg = lastPublished
        val r = publish(cfg, enabled = false)
        if (r.isFailure) {
            _state.value = EngineState.Failed(
                r.exceptionOrNull().toSimError(),
                retryable = true,
                detail = r.exceptionOrNull()?.message,
            )
            return@withContext r
        }
        val confirmed = confirmDisabled()
        if (!confirmed) {
            val err = "stop broadcast ok but system hooks still report enabled=true or stale"
            _state.value = EngineState.Failed(SimError.ACK_TIMEOUT, retryable = true, detail = err)
            return@withContext Result.failure(IllegalStateException(err))
        }
        lastPublished = null
        _state.value = EngineState.Idle
        Result.success(Unit)
    }

    suspend fun pingModule(): Boolean {
        val requestedAt = clock.currentTimeMillis()
        val cmdOk = runCatching {
            val extras = Bundle()
            checkNotNull(lm).sendExtraCommand(ControlPlane.PROVIDER, ControlPlane.CMD_PING, extras)
            extras.containsKey(ControlPlane.KEY_PONG)
        }.isSuccess
        if (!cmdOk) return false
        val st = readStatus()
        return st != null && st.updatedAt >= requestedAt && st.isFresh(clock.currentTimeMillis())
    }

    fun statusSnapshot(): XposedEngine.ModuleStatus? = readStatus()

    internal fun readStatus(): XposedEngine.ModuleStatus? = runCatching {
        context.contentResolver.query(statusUri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val idx = { name: String -> c.getColumnIndex(name) }
            XposedEngine.ModuleStatus(
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

    internal fun deployRootEnvironment(): Boolean {
        val nativeDir = context.applicationInfo?.nativeLibraryDir
        val soFile = if (!nativeDir.isNullOrBlank()) File(nativeDir, "libmockgeo_art.so") else null
        val copySoCmd = if (soFile != null && soFile.canRead()) {
            "cp '${soFile.absolutePath}' /data/misc/mockgeo/lib/libmockgeo_art.so && " +
                "chmod 755 /data/misc/mockgeo/lib/libmockgeo_art.so && " +
                "chown system:system /data/misc/mockgeo/lib/libmockgeo_art.so && " +
                "chcon u:object_r:system_file:s0 /data/misc/mockgeo/lib/libmockgeo_art.so 2>/dev/null || true"
        } else {
            "true"
        }
        val script = listOf(
            "mkdir -p /data/misc/mockgeo /data/misc/mockgeo/lib /data/misc/mockgeo/oat",
            "chown system:system /data/misc/mockgeo /data/misc/mockgeo/lib /data/misc/mockgeo/oat",
            "chmod 750 /data/misc/mockgeo /data/misc/mockgeo/lib /data/misc/mockgeo/oat",
            "chcon -R u:object_r:system_data_file:s0 /data/misc/mockgeo 2>/dev/null || true",
            "if [ ! -f /data/misc/mockgeo/exempt.txt ]; then touch /data/misc/mockgeo/exempt.txt; fi",
            "chown system:system /data/misc/mockgeo/exempt.txt",
            "chmod 640 /data/misc/mockgeo/exempt.txt",
            "chcon u:object_r:system_data_file:s0 /data/misc/mockgeo/exempt.txt 2>/dev/null || true",
            "settings delete global hidden_api_policy_pre_p_apps >/dev/null 2>&1 || true",
            "settings delete global hidden_api_policy_p_apps >/dev/null 2>&1 || true",
            "settings delete global hidden_api_policy >/dev/null 2>&1 || true",
            copySoCmd,
        ).joinToString(" && ")
        val res = shellRunner.exec(script, timeoutMs = 5_000L)
        return res.exitCode == 0
    }

    private suspend fun publish(cfg: SimConfig?, enabled: Boolean): Result<Unit> = publishMutex.withLock {
        if (enabled && !desiredEnabled) return@withLock Result.failure(IllegalStateException("stop requested"))
        publishLocked(cfg, enabled)
    }

    private suspend fun publishLocked(cfg: SimConfig?, enabled: Boolean): Result<Unit> {
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
                if (extras.containsKey(ControlPlane.KEY_ACK_SUCCESS) &&
                    !extras.getBoolean(ControlPlane.KEY_ACK_SUCCESS)
                ) {
                    throw IllegalStateException("system rejected: ${extras.getString(ControlPlane.KEY_ACK_ERROR)}")
                }
                val st = readStatus()
                    ?: throw IllegalStateException("no status receipt from system hooks")
                if (!st.isFresh(clock.currentTimeMillis())) {
                    throw IllegalStateException("status receipt stale")
                }
                if (st.enabled != enabled) {
                    throw IllegalStateException("state divergence: enabled=${st.enabled} expected=$enabled")
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

    private suspend fun confirmDisabled(): Boolean {
        repeat(ControlPlane.STOP_CONFIRM_RETRIES) {
            val st = readStatus()
            if (st != null && st.isFresh(clock.currentTimeMillis()) && !st.enabled) return true
            delay(ControlPlane.STOP_CONFIRM_INTERVAL_MS)
        }
        return false
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (desiredEnabled) {
                delay(ControlPlane.WATCHDOG_PERIOD_MS)
                if (!desiredEnabled) break
                val result = publishMutex.withLock {
                    if (!desiredEnabled) return@withLock Result.success(Unit)
                    publishLocked(lastPublished, enabled = true)
                }
                if (result.isFailure && desiredEnabled) {
                    desiredEnabled = false
                    _state.value = EngineState.Failed(
                        SimError.MODULE_UNREACHABLE,
                        retryable = true,
                        detail = result.exceptionOrNull()?.message ?: "root engine heartbeat failed",
                    )
                    break
                }
            }
        }
    }

    private fun Throwable?.toSimError(): SimError = when (this) {
        null -> SimError.ENGINE_CRASH
        is SecurityException -> SimError.PERMISSION_DENIED
        is IllegalArgumentException -> SimError.MODULE_UNREACHABLE
        else -> SimError.MODULE_UNREACHABLE
    }

    companion object {
        private const val TAG = "RootEngine"
        const val ENGINE_ID = "root"
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface RootShellRunner {
    fun exec(command: String, timeoutMs: Long): ShellResult
}

object DefaultRootShellRunner : RootShellRunner {
    override fun exec(command: String, timeoutMs: Long): ShellResult {
        return try {
            val proc = ProcessBuilder("su", "-c", command).start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return ShellResult(-1, "", "timeout")
            }
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            ShellResult(proc.exitValue(), out, err)
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message.orEmpty())
        }
    }
}
