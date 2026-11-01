package com.mockgeo.module

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Binder
import com.mockgeo.core.model.ControlPlane

/**
 * 状态回执写入：模块（system_server, uid 1000）把真实状态写进 App 的 exported provider。
 *
 * 这是控制面的**主回执通道**——不依赖 sendExtraCommand 的 Bundle 方向标记。
 * 写入时机：每条命令应用之后（同步，App 在 sendExtraCommand 返回后即可读到）。
 *
 * 安全：App 侧 provider 校验写调用者 uid==1000（system）；
 * 模块侧只在响应命令时写（此时 App 必然活着，不会因 provider 写入拉起 App 进程）。
 */
object StatusWriter {

    private val statusUri: Uri = Uri.parse(ControlPlane.STATUS_URI)

    /** 缓存的 system context（system_server 内经 ActivityThread 反射获取）。 */
    @Volatile
    private var systemContext: Context? = null

    fun write(cfg: ModuleConfig) {
        val identity = Binder.clearCallingIdentity()
        try {
            val ctx = obtainSystemContext() ?: run {
                ModuleLog.w("status write skipped: system context unavailable")
                return
            }
            val values = ContentValues().apply {
                put(ControlPlane.COL_ENABLED, cfg.enabled)
                put(ControlPlane.COL_REVISION, cfg.revision)
                put(ControlPlane.COL_LAT, cfg.lat)
                put(ControlPlane.COL_LNG, cfg.lng)
                put(ControlPlane.COL_HOOKS, ModuleConfig.hooksReady)
                put(ControlPlane.COL_HOOK_DETAIL, ModuleConfig.hookDetail)
                put(ControlPlane.COL_LEASE_REMAINING_MS, ModuleConfig.leaseRemainingMs())
                put(ControlPlane.COL_LEASE_EXPIRATIONS, ModuleConfig.leaseExpirations)
                put(ControlPlane.COL_REWRITTEN_CALLBACKS, HookDiagnostics.rewrittenCallbacks.get())
                put(ControlPlane.COL_UNMATCHED_CALLBACKS, HookDiagnostics.unmatchedCallbacks.get())
                put(ControlPlane.COL_UPTIME_MS, uptimeMs())
                put(ControlPlane.COL_UPDATED_AT, System.currentTimeMillis())
                put(ControlPlane.COL_MODULE_VERSION, ModuleRuntime.version)
                put(ControlPlane.COL_BACKEND, ModuleRuntime.backend)
                put(ControlPlane.COL_PROCESS_ID, android.os.Process.myPid())
                put(ControlPlane.COL_INSTANCE_NONCE, ModuleRuntime.instanceNonce)
                put(ControlPlane.COL_RESPONSE_NONCE, ModuleRuntime.responseNonce)
                put(ControlPlane.COL_LAST_CALLS, HookDiagnostics.lastCalls.get())
                put(ControlPlane.COL_CURRENT_CALLS, HookDiagnostics.currentCalls.get())
                put(ControlPlane.COL_LISTENER_CALLS, HookDiagnostics.listenerCalls.get())
                put(ControlPlane.COL_PUSHES, HookDiagnostics.pushes.get() + HookDiagnostics.pendingPushes.get())
                put(ControlPlane.COL_REPORT_MOCK, cfg.reportMock)
            }
            // insert = upsert 语义由 App 侧 provider 实现（replace）
            if (ctx.contentResolver.insert(statusUri, values) == null) {
                ModuleLog.w("status write rejected by application provider")
            }
        } catch (t: Throwable) {
            // App 未安装 / provider 不可用：回执通道降级（Bundle 回写仍在），不致命
            ModuleLog.w("status write failed", t)
        } finally {
            // Receipt failure must not prevent STOP reaching the client mirror.
            publishScopeState(cfg)
            Binder.restoreCallingIdentity(identity)
        }
    }

    fun uptimeMs(): Long =
        if (ModuleConfig.loadedAtMs == 0L) 0 else System.currentTimeMillis() - ModuleConfig.loadedAtMs

    private fun obtainSystemContext(): Context? {
        systemContext?.let { return it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getMethod("currentActivityThread").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(current) as? Context
            systemContext = ctx
            ctx
        }.getOrNull()
    }

    /**
     * 状态镜像：Settings.Global 对所有 App 可读，应用作用域模块实例用它判断
     * enabled / hideMock（含坐标，供证据合成）。
     */
    private var publishedEnabled = false
    private var sessionId = 0L
    private var lastPublishedAt = -1L
    private var publishedMock = true

    @Synchronized
    internal fun publishScopeState(cfg: ModuleConfig) {
        val ctx = obtainSystemContext() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (cfg.enabled && publishedEnabled && cfg.reportMock == publishedMock && now - lastPublishedAt in 0..199) return
        if (cfg.enabled && !publishedEnabled) sessionId = cfg.revision
        val identity = Binder.clearCallingIdentity()
        runCatching {
            val json = org.json.JSONObject()
                .put("e", if (cfg.enabled) 1 else 0)
                .put("h", if (cfg.enabled && !cfg.reportMock) 1 else 0)
                .put("lat", cfg.lat)
                .put("lng", cfg.lng)
                .put("spd", cfg.speedMps.toDouble())
                .put("sat", cfg.satellites)
                .put("ts", System.currentTimeMillis())
                .put("et", now)
                .put("boot", android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1))
                .put("session", sessionId)
                .put("alt", cfg.altitude)
                .put("brg", ((cfg.bearingDeg % 360f) + 360f) % 360f)
                .put("x", Exemptions.list().sorted().joinToString(","))
                .toString()
            check(android.provider.Settings.Global.putString(
                ctx.contentResolver, com.mockgeo.core.model.ControlPlane.GLOBAL_STATE_KEY, json,
            ))
            publishedEnabled = cfg.enabled
            publishedMock = cfg.reportMock
            lastPublishedAt = now
            AppScopeConfig.invalidate()
        }.onFailure { ModuleLog.w("global state publish failed", it) }
        Binder.restoreCallingIdentity(identity)
    }

    /** system context（PendingIntent 投递需要 Context）；拿不到时降级。 */
    internal fun systemContextOrNull(): Context? = obtainSystemContext()

    /** 调用者校验：sendExtraCommand 的发起者必须是控制 App（docs/03 §2.6 的调用者校验）。 */
    fun isControlAppCaller(): Boolean {
        return try {
            val uid = Binder.getCallingUid()
            val ctx = obtainSystemContext() ?: return false
            val pkgs = ctx.packageManager.getPackagesForUid(uid)
            pkgs?.contains(ControlPlane.APP_PACKAGE) == true
        } catch (t: Throwable) {
            ModuleLog.w("caller verify failed", t)
            false
        }
    }

    const val MODULE_VERSION = "0.3.14"
}
