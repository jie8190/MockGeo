package com.mockgeo.core.model

/**
 * 控制面协议（App ↔ LSPosed 模块）。
 *
 * 命令通道：LocationManager.sendExtraCommand(PROVIDER, cmd, bundle)。
 * 回执通道（主）：模块把状态写回 App 的 exported ContentProvider（STATUS_URI）——
 *   该机制不依赖 sendExtraCommand 的 Bundle 方向标记（in/inout），2.1.4 已在生产验证；
 * 回执通道（辅）：模块同时把 ack_* 写回命令 Bundle（若框架为 inout 则 App 免费获得）。
 *
 * 命令白名单（模块侧只接受这四条）：
 *  - SET        发布配置快照 + enabled 开关（带 revision，模块只接受更新的 revision）
 *  - STOP       语义 = SET(enabled=false)，单独列出便于日志审计
 *  - GET_STATUS 查询模块侧真实状态（回执写入状态 provider）
 *  - PING       存活探测（模块激活自检用）
 *
 * 防 bug 约束（docs/06 §4）：
 *  1. 每条命令带 revision；乱序/过期回执必须丢弃；
 *  2. 停止必须读回状态确认 enabled=false 才算成功；
 *  3. 模块侧 system_server 重启后 enabled 默认 false（fail-safe）；
 *  4. App 侧看门狗周期性对账，发现分叉立即重发期望状态。
 */
object ControlPlane {
    const val ROOT_PROVIDER = "mockgeo_root"
    const val KEY_REQUEST_NONCE = "request_nonce"
    const val KEY_STATUS_SNAPSHOT = "status_snapshot_v1"
    const val COL_BACKEND = "backend"
    const val COL_PROCESS_ID = "process_id"
    const val COL_INSTANCE_NONCE = "instance_nonce"
    const val COL_RESPONSE_NONCE = "response_nonce"

    /** 自定义 provider 名。模块 hook sendExtraCommand 后接管此 provider 的命令。 */
    const val PROVIDER = "mockgeo"

    /** 控制包 App 的包名（模块侧调用者校验用）。 */
    const val APP_PACKAGE = "com.mockgeo.app"

    /** 命令名。 */
    const val CMD_SET = "SET"
    const val CMD_STOP = "STOP"
    const val CMD_GET_STATUS = "GET_STATUS"
    const val CMD_PING = "PING"

    /** SET 请求字段。 */
    const val KEY_ENABLED = "enabled"
    const val KEY_REVISION = "revision"
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_LAT = "latitude"
    const val KEY_LNG = "longitude"
    const val KEY_ALT = "altitude"
    const val KEY_ACCURACY = "accuracy"
    const val KEY_SPEED = "speed"
    const val KEY_BEARING = "bearing"
    const val KEY_SATellites = "satellites"
    const val KEY_JITTER_ENABLED = "jitter_enabled"
    const val KEY_JITTER_METERS = "jitter_meters"
    const val KEY_REPORT_INTERVAL_MS = "report_interval_ms"
    const val KEY_REPORT_MOCK = "report_mock"

    /** 回执字段（模块写回命令 Bundle，inout 时可见）。 */
    const val KEY_ACK_SUCCESS = "ack_success"
    const val KEY_ACK_ERROR = "ack_error"
    const val KEY_ACK_APPLIED_REVISION = "ack_applied_revision"

    /** Bundle 回执字段（GET_STATUS / PING）。 */
    const val KEY_STATUS_ENABLED = "status_enabled"
    const val KEY_STATUS_REVISION = "status_revision"
    const val KEY_STATUS_LAT = "status_lat"
    const val KEY_STATUS_LNG = "status_lng"
    const val KEY_STATUS_HOOKS = "status_hooks_installed"
    const val KEY_STATUS_UPTIME_MS = "status_uptime_ms"
    const val KEY_PONG = "pong"
    const val KEY_MODULE_VERSION = "module_version"

    // ---- 状态回执 provider（App 实现，模块写入）----

    /** provider authority。App 侧实现为 exported + uid 校验（写=system，读=自身）。 */
    const val STATUS_AUTHORITY = "com.mockgeo.app.status"

    val STATUS_URI: String = "content://$STATUS_AUTHORITY"

    /** 状态行字段。 */
    const val COL_ENABLED = "enabled"
    const val COL_REVISION = "revision"
    const val COL_LAT = "lat"
    const val COL_LNG = "lng"
    const val COL_HOOKS = "hooks_installed"
    const val COL_HOOK_DETAIL = "hook_detail"
    const val COL_LEASE_REMAINING_MS = "lease_remaining_ms"
    const val COL_LEASE_EXPIRATIONS = "lease_expirations"
    const val COL_REWRITTEN_CALLBACKS = "rewritten_callbacks"
    const val COL_UNMATCHED_CALLBACKS = "unmatched_callbacks"
    const val COL_UPTIME_MS = "uptime_ms"
    const val COL_UPDATED_AT = "updated_at"
    const val COL_MODULE_VERSION = "module_version"
    const val COL_LAST_CALLS = "last_calls"
    const val COL_CURRENT_CALLS = "current_calls"
    const val COL_LISTENER_CALLS = "listener_calls"
    const val COL_PUSHES = "pushes"
    const val COL_REPORT_MOCK = "report_mock"

    /**
     * 系统侧状态镜像：system_server 模块把 enabled/hideMock/lat/lng 写进 Settings.Global，
     * 供应用作用域的模块实例读取（StatusProvider 只允许自读，跨进程拿不到）。
     */
    const val GLOBAL_STATE_KEY = "mockgeo_state_v1"

    /** 状态新鲜度窗口：超过视为模块不可达/重启。 */
    const val STATUS_FRESH_MS = 15_000L

    /** 模块侧日志 tag（logcat 过滤用，诊断页展示给用户）。 */
    const val MODULE_LOG_TAG = "MockGeoModule"

    /** 看门狗周期：运行中每 5s 对账一次（docs/06 §3.3 ③）。 */
    const val WATCHDOG_PERIOD_MS = 5_000L

    /** 停止确认：状态轮询次数与间隔。 */
    const val STOP_CONFIRM_RETRIES = 3
    const val STOP_CONFIRM_INTERVAL_MS = 200L

    /** 命令重试：sendExtraCommand 失败（模块未接管）时的重试次数与间隔。 */
    const val CMD_RETRIES = 3
    const val CMD_RETRY_INTERVAL_MS = 200L
}
