package com.mockgeo.core.engine

/**
 * 可注入时钟（docs/08 §3：tick 用 delay()，时间用注入 Clock，测试可虚拟化）。
 */
interface Clock {
    fun currentTimeMillis(): Long

    /** 单调递增毫秒（对应 SystemClock.elapsedRealtime）。 */
    fun elapsedRealtime(): Long

    fun elapsedRealtimeNanos(): Long
}

class SystemClockImpl : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
    override fun elapsedRealtimeNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}

/** 结构化日志：一条 requestId 贯穿 UI → 服务 → 通道 → 注入侧（docs/06 §4 第 10 条）。 */
interface SimLogger {
    fun log(requestId: Long, level: Int, tag: String, message: String, error: Throwable? = null)

    /** 最近 N 条日志（诊断页展示）。 */
    fun recent(): List<LogLine>

    data class LogLine(
        val at: Long,
        val requestId: Long,
        val level: Int,
        val tag: String,
        val message: String,
        val error: String? = null,
    )

    companion object {
        const val VERBOSE = 2
        const val DEBUG = 3
        const val INFO = 4
        const val WARN = 5
        const val ERROR = 6
    }
}

/** 环形缓冲日志（内存版，进程内有效）。 */
class RingSimLogger(private val capacity: Int = 500) : SimLogger {
    private val lines = ArrayDeque<SimLogger.LogLine>(capacity)

    @Synchronized
    override fun log(requestId: Long, level: Int, tag: String, message: String, error: Throwable?) {
        lines.addLast(
            SimLogger.LogLine(
                at = System.currentTimeMillis(),
                requestId = requestId,
                level = level,
                tag = tag,
                message = message,
                error = error?.toString(),
            ),
        )
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    override fun recent(): List<SimLogger.LogLine> = lines.toList()
}
