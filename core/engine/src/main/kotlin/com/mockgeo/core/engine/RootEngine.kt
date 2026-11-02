package com.mockgeo.core.engine

import android.content.Context
import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.ControlPlane
import com.mockgeo.core.model.EngineCapability
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.InjectionEngine
import com.mockgeo.core.model.SimConfig
import com.mockgeo.core.model.SimError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent Root loader plus a backend-specific, nonce-checked system channel. */
class RootEngine(
    private val environment: RootEnvironment,
    private val channel: SystemHookControl,
    private val legacy: SystemHookControl,
    scope: CoroutineScope,
) : InjectionEngine {
    constructor(context: Context, clock: Clock, scope: CoroutineScope, log: SimLogger,
                suPath: suspend () -> String = { "" }) : this(
        RootDeployment(context, suPath),
        XposedEngine(context, clock, scope, log, ControlPlane.ROOT_PROVIDER, "root"),
        XposedEngine(context, clock, scope, log),
        scope,
    )

    private val current = MutableStateFlow<EngineState>(EngineState.Idle)
    private val operations = Mutex()
    @Volatile private var ownsSession = false
    init { scope.launch { channel.state().collect { if (ownsSession) current.value = it } } }
    override fun state(): StateFlow<EngineState> = current
    override suspend fun capability() = EngineCapability(ENGINE_ID, "Root 直连（系统定位）", false, false, true, true,
        startupTimeoutMs = 90_000)

    override suspend fun selfCheck(): List<CheckResult> {
        if (legacy.pingModule()) return listOf(CheckResult(CHECK_NAME, false, CONFLICT_REASON))
        if (channel.pingModule()) {
            val status = channel.statusSnapshot()
            val valid = status != null && status.processId > 1 && status.moduleVersion == environment.version &&
                RootReceipt(status.processId, status.instanceNonce, environment.version).matches(status)
            return listOf(CheckResult(CHECK_NAME, valid,
                if (valid) "Root 系统定位钩子已加载" else "Root 载荷版本或回执不匹配，请手动重启后重试"))
        }
        return environment.readiness() + CheckResult("Root 首次加载", true, "组件通过检查后，开始模拟时加载系统钩子")
    }

    override suspend fun start(cfg: SimConfig): Result<Unit> = operations.withLock {
        current.value = EngineState.Starting(System.nanoTime())
        val result = runCatching {
            check(!legacy.pingModule()) { CONFLICT_REASON }
            val receipt = if (channel.pingModule()) {
                val status = checkNotNull(channel.statusSnapshot())
                check(status.moduleVersion == environment.version) { "已加载的 Root 载荷需手动重启后更新" }
                RootReceipt(status.processId, status.instanceNonce, environment.version)
            } else environment.load()
            var confirmed = false
            repeat(25) {
                if (!confirmed) {
                    confirmed = channel.pingModule() && receipt.matches(channel.statusSnapshot())
                    if (!confirmed) delay(200)
                }
            }
            check(confirmed) { "Root 载荷未收到实际 system_server 的匹配回执" }
            ownsSession = true
            channel.start(cfg).getOrThrow()
            current.value = channel.state().value
        }
        if (result.isFailure) {
            // Once a SET may have reached the service, retain responsibility for
            // cleanup even when its receipt is lost.
            current.value = EngineState.Failed(SimError.MODULE_UNREACHABLE, retryable = true,
                detail = result.exceptionOrNull()?.message)
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
        result
    }

    override suspend fun update(cfg: SimConfig): Result<Unit> =
        if (ownsSession) channel.update(cfg) else Result.failure(IllegalStateException("Root engine not started"))

    override suspend fun stop(): Result<Unit> = operations.withLock {
        val result = if (channel.pingModule()) channel.stop()
            else if (ownsSession) Result.failure(IllegalStateException("Root 系统通道不可达，尚未确认停止"))
            else Result.success(Unit)
        if (result.isSuccess) { ownsSession = false; current.value = EngineState.Idle }
        else current.value = EngineState.Failed(SimError.MODULE_UNREACHABLE, true, result.exceptionOrNull()?.message)
        result
    }
    suspend fun pingModule(): Boolean = channel.pingModule()
    fun statusSnapshot() = channel.statusSnapshot()

    companion object {
        const val ENGINE_ID = "root"
        const val CHECK_NAME = "Root 独立注入"
        const val CONFLICT_REASON = "系统进程仍驻留 LSPosed。免框架 Root 直连需先停用 LSPosed 注入并手动重启，或继续选择 LSPosed 模式"
    }
}
