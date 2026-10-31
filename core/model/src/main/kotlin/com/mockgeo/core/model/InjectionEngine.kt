package com.mockgeo.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * 引擎能力声明。启动自检与 UI 引擎切换依据它。
 */
data class EngineCapability(
    /** 引擎标识（provider / xposed）。 */
    val engineId: String,
    /** 人类可读名称。 */
    val displayName: String,
    /** 是否需要开发者选项 mock AppOps（provider 引擎需要）。 */
    val requiresMockAppOp: Boolean,
    /** 是否需要外部依赖（LSPosed 模块激活）。 */
    val requiresExternalModule: Boolean,
    /** 是否能同时覆盖 network provider。 */
    val supportsNetworkProvider: Boolean,
    /** 是否支持逐 tick update（路线/摇杆推进）。 */
    val supportsStreamingUpdate: Boolean,
    /** Optional bounded first-start initialization window; ordinary engines use the session default. */
    val startupTimeoutMs: Long? = null,
)

/**
 * 注入引擎抽象。两个实现：
 * - ProviderEngine（路线 A：官方 test provider，App 进程内）
 * - XposedEngine（路线 B：通过控制面命令 LSPosed 模块）
 *
 * 契约（docs/08 §3 + docs/06 §4）：
 * - start/update/stop 都必须幂等；
 * - 返回 Result，禁止静默吞异常（catch 后必须落到 Result.failure）；
 * - stop 必须是事务：先断帧源，再关开关，最后确认（XposedEngine 内部实现）。
 */
interface InjectionEngine {
    suspend fun capability(): EngineCapability

    suspend fun start(cfg: SimConfig): Result<Unit>

    suspend fun update(cfg: SimConfig): Result<Unit>

    suspend fun stop(): Result<Unit>

    /** 引擎自检（不改变状态）：模块可达性 / AppOps / provider 状态。 */
    suspend fun selfCheck(): List<CheckResult>

    fun state(): StateFlow<EngineState>
}

/** 自检项。ok=false 时 actionKey 告诉 UI 跳哪个引导。 */
data class CheckResult(
    val name: String,
    val ok: Boolean,
    /** 失败时的用户可读说明。 */
    val message: String? = null,
    /** 引导动作标识：DEV_OPTIONS / APP_DETAILS / NOTIFICATION / OVERLAY / NONE。 */
    val actionKey: String = "NONE",
)
