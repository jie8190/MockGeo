package com.mockgeo.module

import android.os.Bundle
import com.mockgeo.core.model.ControlPlane

/**
 * 命令分发器：sendExtraCommand hook 的业务逻辑。
 *
 * 命令白名单：SET / STOP / GET_STATUS / PING（docs/02 §B 的白名单设计）。
 * 调用者校验：只接受控制 App 的 uid（docs/03 §2.6）。
 * 回执：双通道——Bundle 回写（inout 时生效）+ 状态 provider（主通道）。
 *
 * 返回值：sendExtraCommand 的方法返回值（恒 true）。
 */
object CommandDispatcher {

    @Synchronized
    fun intercept(command: String?, extras: Bundle?): Any? {
        // 非本模块 provider 的命令不会走到这里（HookInstaller 已过滤），
        // 但仍防御：未知命令直接吞掉并回执错误（不 proceed，避免系统侧抛异常）
        val cmd = command ?: return java.lang.Boolean.TRUE
        try {
            if (!StatusWriter.isControlAppCaller()) {
                ModuleLog.w("rejected: caller is not control app, cmd=$cmd")
                writeAck(extras, success = false, error = "unauthorized caller")
                return java.lang.Boolean.TRUE
            }
            when (cmd) {
                ControlPlane.CMD_SET -> handleSet(extras)
                ControlPlane.CMD_STOP -> handleStop(extras)
                ControlPlane.CMD_GET_STATUS -> handleGetStatus(extras)
                ControlPlane.CMD_PING -> handlePing(extras)
                else -> {
                    ModuleLog.w("unknown command: $cmd")
                    writeAck(extras, success = false, error = "unknown command: $cmd")
                }
            }
        } catch (t: Throwable) {
            // 命令处理绝不让异常逃逸到 system_server 调用方
            ModuleLog.e("command $cmd failed", t)
            runCatching { writeAck(extras, success = false, error = t.message ?: "internal error") }
        }
        return java.lang.Boolean.TRUE
    }

    // ---- 命令实现 ----

    private fun handleSet(extras: Bundle?) {
        val b = extras ?: throw IllegalArgumentException("SET without extras")
        val rev = b.getLong(ControlPlane.KEY_REVISION, -1L)
        if (rev <= 0) {
            writeAck(b, success = false, error = "missing revision")
            return
        }
        val enabled = b.getBoolean(ControlPlane.KEY_ENABLED, false)
        val beginningSession = enabled && !ModuleConfig.snapshot().enabled
        val hasPosition = b.containsKey(ControlPlane.KEY_LAT) && b.containsKey(ControlPlane.KEY_LNG)

        // 关键修复（docs/06 §3.3）：停止前不推 true；这里只按命令语义翻转，
        // revision 单调防乱序（旧命令迟到不覆盖新状态）
        val applied = ModuleConfig.apply(rev) { old ->
            old.with(
                revision = rev,
                enabled = enabled,
                lat = if (hasPosition) b.getDouble(ControlPlane.KEY_LAT) else old.lat,
                lng = if (hasPosition) b.getDouble(ControlPlane.KEY_LNG) else old.lng,
                altitude = if (b.containsKey(ControlPlane.KEY_ALT)) b.getDouble(ControlPlane.KEY_ALT) else old.altitude,
                accuracyM = if (b.containsKey(ControlPlane.KEY_ACCURACY)) b.getFloat(ControlPlane.KEY_ACCURACY) else old.accuracyM,
                speedMps = if (b.containsKey(ControlPlane.KEY_SPEED)) b.getFloat(ControlPlane.KEY_SPEED) else old.speedMps,
                bearingDeg = if (b.containsKey(ControlPlane.KEY_BEARING)) b.getFloat(ControlPlane.KEY_BEARING) else old.bearingDeg,
                satellites = if (b.containsKey(ControlPlane.KEY_SATellites)) b.getInt(ControlPlane.KEY_SATellites) else old.satellites,
                jitterEnabled = if (b.containsKey(ControlPlane.KEY_JITTER_ENABLED)) b.getBoolean(ControlPlane.KEY_JITTER_ENABLED) else old.jitterEnabled,
                jitterMeters = if (b.containsKey(ControlPlane.KEY_JITTER_METERS)) b.getDouble(ControlPlane.KEY_JITTER_METERS) else old.jitterMeters,
                reportIntervalMs = if (b.containsKey(ControlPlane.KEY_REPORT_INTERVAL_MS)) b.getLong(ControlPlane.KEY_REPORT_INTERVAL_MS) else old.reportIntervalMs,
                reportMock = if (b.containsKey(ControlPlane.KEY_REPORT_MOCK)) b.getBoolean(ControlPlane.KEY_REPORT_MOCK) else old.reportMock,
            )
        }
        val accepted = applied.revision == rev
        if (accepted && beginningSession) HookDiagnostics.reset()
        writeAck(b, success = accepted, error = if (accepted) null else "stale revision", appliedRevision = applied.revision)
        StatusWriter.write(applied)
        ListenerPusher.onConfigChanged(applied)
        ModuleLog.d("SET rev=$rev enabled=$enabled accepted=$accepted")
    }

    private fun handleStop(extras: Bundle?) {
        val applied = ModuleConfig.setEnabled(false)
        writeAck(extras, success = true, appliedRevision = applied.revision)
        StatusWriter.write(applied)
        ListenerPusher.onConfigChanged(applied)
        ModuleLog.d("STOP applied, enabled=false")
    }

    private fun handleGetStatus(extras: Bundle?) {
        val cfg = ModuleConfig.snapshot()
        extras?.apply {
            putBoolean(ControlPlane.KEY_STATUS_ENABLED, cfg.enabled)
            putLong(ControlPlane.KEY_STATUS_REVISION, cfg.revision)
            putDouble(ControlPlane.KEY_STATUS_LAT, cfg.lat)
            putDouble(ControlPlane.KEY_STATUS_LNG, cfg.lng)
            putBoolean(ControlPlane.KEY_STATUS_HOOKS, ModuleConfig.hooksInstalled > 0)
            putLong(ControlPlane.KEY_STATUS_UPTIME_MS, StatusWriter.uptimeMs())
        }
        // 主回执通道也刷新一次（App 可能只看 provider）
        StatusWriter.write(cfg)
        ModuleLog.d("GET_STATUS enabled=${cfg.enabled}")
    }

    private fun handlePing(extras: Bundle?) {
        ModuleRuntime.responseNonce = extras?.getString(ControlPlane.KEY_REQUEST_NONCE).orEmpty().take(64)
        extras?.apply {
            putBoolean(ControlPlane.KEY_PONG, true)
            putString(ControlPlane.KEY_MODULE_VERSION, ModuleRuntime.version)
        }
        StatusWriter.write(ModuleConfig.snapshot())
        ModuleLog.d("PING")
    }

    // ---- 回执 ----

    private fun writeAck(extras: Bundle?, success: Boolean, error: String? = null, appliedRevision: Long = 0L) {
        // Bundle 回写（辅助通道：框架 inout 时 App 免费获得）
        runCatching {
            extras?.apply {
                putBoolean(ControlPlane.KEY_ACK_SUCCESS, success)
                putString(ControlPlane.KEY_ACK_ERROR, error)
                putLong(ControlPlane.KEY_ACK_APPLIED_REVISION, appliedRevision)
            }
        }
    }
}
