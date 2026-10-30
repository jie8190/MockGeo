package com.mockgeo.core.model

/**
 * 引擎状态机状态。UI 只订阅它（单一真相），不做乐观更新。
 *
 * 设计依据：docs/06 §4「每个进行中状态都必须有 TTL」——
 * Starting/Stopping 由 SimulationSession 的看门狗保证不会无限停留。
 */
sealed interface EngineState {

    /** 空闲。 */
    data object Idle : EngineState

    /** 正在启动（等待引擎 ack）。requestId 用于乱序回执丢弃。 */
    data class Starting(val requestId: Long) : EngineState

    /** 正在运行。since 为会话时钟（测试可注入）。 */
    data class Running(val since: Long, val cfg: SimConfig) : EngineState

    /** 正在停止（等待引擎 ack）。超时不落 Idle，回滚 Running 并重试。 */
    data class Stopping(val requestId: Long, val cfg: SimConfig) : EngineState

    /** 终态失败。retryable=true 时 UI 显示"重试"。 */
    data class Failed(val reason: SimError, val retryable: Boolean, val detail: String? = null) : EngineState
}

/** 失败分类。UI 按 code 给出可操作的引导文案。 */
enum class SimError {
    /** AppOps mock_location 未允许（开发者选项没选本应用）。 */
    MOCK_APPOP_DENIED,

    /** 运行时定位权限缺失。 */
    PERMISSION_DENIED,

    /** Android 12+ 后台前台服务启动限制。 */
    FGS_RESTRICTED,

    /** provider 不可用 / 注册失败。 */
    PROVIDER_UNAVAILABLE,

    /** 命令发出后未在 TTL 内收到引擎 ack。 */
    ACK_TIMEOUT,

    /** 配置不合法（坐标越界等）。 */
    INVALID_CONFIG,

    /** 引擎内部异常。 */
    ENGINE_CRASH,

    /** LSPosed 模块未激活 / 控制面不可达。 */
    MODULE_UNREACHABLE,
}
