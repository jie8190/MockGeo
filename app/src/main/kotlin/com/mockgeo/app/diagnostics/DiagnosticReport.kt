package com.mockgeo.app.diagnostics

import com.mockgeo.core.engine.SimLogger
import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.EngineState
import java.time.Instant

/** The export is a report, never a dump of settings, routes, or EngineState.cfg. */
object DiagnosticReport {
    fun stateLabel(state: EngineState): String = when (state) {
        EngineState.Idle -> "空闲"
        is EngineState.Starting -> "启动中"
        is EngineState.Running -> "运行中"
        is EngineState.Stopping -> "停止中"
        is EngineState.Failed -> "失败 (${state.reason})"
    }

    fun render(metadata: List<Pair<String, String>>, checks: List<CheckResult>,
               logs: List<SimLogger.LogLine>, secrets: Collection<String>): String {
        val redact = DiagnosticRedactor(secrets)
        return buildString {
            appendLine("MockGeo 诊断日志")
            appendLine("导出时间 UTC：${Instant.now()}")
            appendLine("设备与运行信息；不导出收藏、历史、路线文件或完整设置。")
            appendLine()
            metadata.forEach { (key, value) -> appendLine("$key：${redact.clean(value)}") }
            appendLine()
            appendLine("运行环境检查（最近一次结果）")
            if (checks.isEmpty()) appendLine("暂无检查结果，请打开运行状态重新检查。")
            checks.forEach { appendLine(redact.clean("${if (it.ok) "通过" else "待检查"} ${it.name}: ${it.message.orEmpty()}")) }
            appendLine()
            appendLine("近期应用日志（当前进程，重启应用后清空，最多 500 条）")
            if (logs.isEmpty()) appendLine("暂无日志。请先复现问题，再导出。")
            logs.takeLast(500).forEach {
                appendLine(redact.clean("${Instant.ofEpochMilli(it.at)} level=${it.level} request=${it.requestId} [${it.tag}] ${it.message}${it.error?.let { error -> " | $error" }.orEmpty()}"))
            }
        }
    }
}

/** Removes known map keys plus sensitive fields that may occur in SDK error text. */
class DiagnosticRedactor(secrets: Collection<String>) {
    private val secrets = secrets.map(String::trim).filter { it.isNotEmpty() }.distinct().sortedByDescending(String::length)
    fun clean(input: String): String {
        var text = input
        secrets.forEach { text = text.replace(it, "[密钥已过滤]", ignoreCase = true) }
        text = urls.replace(text, "[链接已过滤]")
        text = fields.replace(text) { "${it.groupValues[1]}[敏感字段已过滤]" }
        text = bearer.replace(text, "Bearer [已过滤]")
        text = coordinates.replace(text) { "${it.groupValues[1]}[坐标已省略]" }
        text = coordinatePair.replace(text, "[坐标已省略]")
        text = hexToken.replace(text, "[标识已过滤]")
        return text.take(4096)
    }

    companion object {
        private val urls = Regex("(?i)\\b(?:https?|content|file)://[^\\s<>\"']+")
        private val fields = Regex("(?i)((?:[\"']?)(?:api[_-]?key|amap[._-]?(?:api)?key|access[_-]?token|refresh[_-]?token|token|password|secret)(?:[\"']?)\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;})]+)")
        private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+")
        private val coordinates = Regex("""(?i)(\b(?:lat(?:itude)?|lon(?:gitude)?|lng)["']?\s*[:=]\s*["']?)[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?""")
        private val coordinatePair = Regex("(?<![\\w.])[-+]?\\d{1,3}\\.\\d{3,}\\s*,\\s*[-+]?\\d{1,3}\\.\\d{3,}(?![\\w.])")
        private val hexToken = Regex("(?i)\\b[0-9a-f]{32,}\\b")
    }
}
