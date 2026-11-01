package com.mockgeo.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

/** Read-only handshake. A successful Binder call alone does not prove module activation. */
internal class ControlChannelProbe(
    private val clock: Clock,
    private val backend: String,
    private val log: SimLogger,
    private val send: (String) -> Unit,
    private val read: () -> XposedEngine.ModuleStatus?,
) {
    data class Result(val reachable: Boolean, val detail: String, val status: XposedEngine.ModuleStatus? = null)

    suspend fun check(): Result {
        val requestedAt = clock.currentTimeMillis()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        try {
            send(nonce)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure("send_failed", "系统控制命令失败（${error.javaClass.simpleName}）。请导出诊断日志查看原因", error)
        }

        var last: XposedEngine.ModuleStatus? = null
        // Some ROMs complete receipt delivery after returning from sendExtraCommand.
        repeat(4) { attempt ->
            if (attempt > 0) delay(150)
            try {
                last = read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return failure("read_failed", "系统回执读取失败（${error.javaClass.simpleName}）。请导出诊断日志查看原因", error)
            }
            val status = last
            if (status != null && backendMatches(status) && status.updatedAt >= requestedAt &&
                status.isFresh(clock.currentTimeMillis()) &&
                (backend != "root" || status.responseNonce == nonce)) {
                log.log(0, SimLogger.INFO, TAG,
                    "PING backend=$backend receipt=ok module=${status.moduleVersion} pid=${status.processId} hooks=${status.hooksInstalled}")
                return Result(true, "已收到系统回执", status)
            }
        }
        val status = last
        return when {
            status == null -> failure("no_receipt",
                "控制命令已发送，但未收到系统回执；不能据此判断 LSPosed 开关状态。请导出诊断，并查看 LSPosed 的 MockGeo 加载日志")
            !backendMatches(status) -> failure("wrong_backend", "收到其他运行模式的回执，请核对运行模式与系统加载状态")
            status.updatedAt < requestedAt || !status.isFresh(clock.currentTimeMillis()) ->
                failure("stale_receipt", "仅收到旧回执，当前系统通道未确认。请导出诊断日志")
            else -> failure("nonce_mismatch", "系统回执与本次检查不匹配，请导出诊断日志")
        }
    }

    private fun backendMatches(status: XposedEngine.ModuleStatus): Boolean =
        status.backend == backend || (backend == "xposed" && status.backend.isBlank())

    private fun failure(code: String, detail: String, error: Throwable? = null): Result {
        // Never serialize a receipt or command bundle: both may contain coordinates.
        log.log(0, SimLogger.WARN, TAG, "PING backend=$backend stage=$code", error)
        return Result(false, detail)
    }

    private companion object { const val TAG = "ControlChannel" }
}
