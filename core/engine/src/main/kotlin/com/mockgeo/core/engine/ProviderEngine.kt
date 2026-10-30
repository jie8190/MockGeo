package com.mockgeo.core.engine

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.mockgeo.core.model.CheckResult
import com.mockgeo.core.model.EngineCapability
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.InjectionEngine
import com.mockgeo.core.model.SimConfig
import com.mockgeo.core.model.SimError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 路线 A 引擎：官方 test provider（docs/02 §A、docs/08 §5）。
 *
 * 要点：
 * - AppOps 自检在 start 前做（SecurityException 不会告诉用户去哪开）；
 * - Location 必须完整（provider + accuracy + time + elapsedRealtimeNanos），
 *   不依赖 @SystemApi 的 makeComplete()（普通 App 不可见）；
 * - 不改 isMock（保持系统语义；test provider 的 Location 系统会自动标记 mock）；
 * - stop 幂等：disable + remove 各自 runCatching。
 */
class ProviderEngine(
    private val context: Context,
    private val clock: Clock,
) : InjectionEngine {

    private val lm = context.getSystemService(LocationManager::class.java)

    private val registered = linkedSetOf<String>()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    override fun state(): StateFlow<EngineState> = _state

    override suspend fun capability(): EngineCapability = EngineCapability(
        engineId = ENGINE_ID,
        displayName = "官方 Test Provider",
        requiresMockAppOp = true,
        requiresExternalModule = false,
        supportsNetworkProvider = true,
        supportsStreamingUpdate = true,
    )

    override suspend fun selfCheck(): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        val appOpOk = isMockAppOpAllowed()
        results += CheckResult(
            name = "模拟位置 AppOps",
            ok = appOpOk,
            message = if (appOpOk) null else "开发者选项中未选择本应用为模拟位置应用",
            actionKey = "DEV_OPTIONS",
        )
        results += CheckResult(
            name = "LocationManager 可用",
            ok = lm != null,
            message = if (lm == null) "系统 Location 服务不可用" else null,
        )
        return results
    }

    override suspend fun start(cfg: SimConfig): Result<Unit> {
        val r = runCatching {
            checkNotNull(lm) { "LocationManager unavailable" }
            ensureAppOpAllowed()
            syncProviders(cfg)
            push(cfg)
        }
        _state.value = if (r.isSuccess) {
            EngineState.Running(clock.elapsedRealtime(), cfg)
        } else {
            EngineState.Failed(r.exceptionOrNull().toSimError(), retryable = true, detail = r.exceptionOrNull()?.message)
        }
        return r
    }

    override suspend fun update(cfg: SimConfig): Result<Unit> = runCatching {
        check(registered.isNotEmpty()) { "provider not started" }
        syncProviders(cfg)
        push(cfg)
    }.onFailure { error ->
        _state.value = EngineState.Failed(error.toSimError(), true, error.message)
    }

    override suspend fun stop(): Result<Unit> {
        val l = lm ?: return Result.success(Unit)
        if (registered.isEmpty() && !isMockAppOpAllowed()) {
            _state.value = EngineState.Idle
            return Result.success(Unit)
        }
        var failure: Throwable? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            // Removal is still attempted when disabling fails. Only an absent test
            // provider is benign; permission/service failures must reach the UI.
            runCatching { l.setTestProviderEnabled(p, false) }
            val removed = runCatching { l.removeTestProvider(p) }
            val error = removed.exceptionOrNull()
            if (error == null || error is IllegalArgumentException) registered.remove(p)
            else if (failure == null) failure = error
        }
        return if (failure == null) {
            _state.value = EngineState.Idle
            Result.success(Unit)
        } else {
            _state.value = EngineState.Failed(failure.toSimError(), true, failure.message)
            Result.failure(failure)
        }
    }

    private fun syncProviders(cfg: SimConfig) {
        val l = checkNotNull(lm) { "LocationManager unavailable" }
        val wanted = providerList(cfg).toSet()
        for (p in registered.toList() - wanted) {
            l.setTestProviderEnabled(p, false)
            l.removeTestProvider(p)
            registered.remove(p)
        }
        for (p in wanted - registered) {
            @Suppress("DEPRECATION")
            l.addTestProvider(p, false, p == LocationManager.GPS_PROVIDER, false,
                false, true, true, true, 1, 1)
            // Track immediately so partially successful setup can be rolled back.
            registered.add(p)
            l.setTestProviderEnabled(p, true)
        }
    }

    // ---- 内部 ----

    private fun providerList(cfg: SimConfig): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (cfg.networkProviderToo) add(LocationManager.NETWORK_PROVIDER)
    }

    private fun push(cfg: SimConfig) {
        val nowNanos = clock.elapsedRealtimeNanos()
        val nowMs = clock.currentTimeMillis()
        for (p in providerList(cfg)) {
            val loc = Location(p).apply {
                latitude = cfg.position.lat
                longitude = cfg.position.lng
                altitude = cfg.altitude
                accuracy = cfg.accuracyM
                speed = cfg.speedMps
                bearing = cfg.bearingDeg
                verticalAccuracyMeters = cfg.verticalAccuracyM
                speedAccuracyMetersPerSecond = cfg.speedAccuracyMps
                bearingAccuracyDegrees = cfg.bearingAccuracyDeg
                time = nowMs
                elapsedRealtimeNanos = nowNanos
                setExtras(Bundle().apply { putInt("satellites", cfg.satellites) })
            }
            // 完整性由上面字段保证：provider(构造器) + accuracy + time + elapsedRealtimeNanos
            lm.setTestProviderLocation(p, loc)
        }
    }

    private fun ensureAppOpAllowed() {
        if (!isMockAppOpAllowed()) {
            throw SecurityException("mock_location AppOps not allowed (enable in developer options)")
        }
    }

    private fun isMockAppOpAllowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val pkg = context.packageName ?: return false
        val uid = context.applicationInfo?.uid ?: return false
        return if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg) ==
                AppOpsManager.MODE_ALLOWED
        } else {
            // 字符串版 checkOpNoThrow 自 API 19 起公开（int 常量 OP_MOCK_LOCATION 是 @hide）
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg) ==
                AppOpsManager.MODE_ALLOWED
        }
    }

    private fun Throwable?.toSimError(): SimError = when (this) {
        null -> SimError.ENGINE_CRASH
        is SecurityException -> SimError.MOCK_APPOP_DENIED
        is IllegalArgumentException -> SimError.INVALID_CONFIG
        else -> SimError.ENGINE_CRASH
    }

    companion object {
        const val ENGINE_ID = "provider"
    }
}
