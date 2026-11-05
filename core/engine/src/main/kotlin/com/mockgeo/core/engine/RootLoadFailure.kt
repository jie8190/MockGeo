package com.mockgeo.core.engine

internal object RootLoadFailure {
    fun describe(detail: String): String = when {
        "PTRACE_INTERRUPT" in detail || "interrupt failed" in detail ->
            "Root 已授权，但加载器暂停系统进程失败，尚未进入载荷加载步骤。请保存诊断；此类失败需结合 errno 和 ROM 检查，勿反复尝试注入。原始错误：$detail"
        "PTRACE_SEIZE" in detail || "ptrace denied" in detail ->
            "Root 已授权，但系统未允许加载器附加目标进程。原始错误：$detail"
        "target restoration failed" in detail ->
            "加载器未能确认系统线程已完整恢复。请保存诊断并在方便时手动重启，不要继续反复注入。原始错误：$detail"
        else -> detail
    }
}
