package com.mockgeo.app.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

data class CapturedFix(
    val requestedProvider: String, val providerEnabled: Boolean?, val source: String,
    val provider: String? = null, val latitude: Double? = null, val longitude: Double? = null,
    val accuracyM: Float? = null, val timeMs: Long? = null, val ageMs: Long? = null,
    val mock: Boolean? = null, val note: String? = null,
)

data class LocalLocationReport(val capturedAtMs: Long, val fixes: List<CapturedFix>) {
    fun json(): String = JSONObject().apply {
        put("format", "mockgeo-local-location-v1")
        put("capturedAtMs", capturedAtMs)
        put("meaning", "System-returned observations; other location tools or providers may affect results")
        put("fixes", JSONArray().apply { fixes.forEach { fix -> put(JSONObject().apply {
            put("requestedProvider", fix.requestedProvider); put("providerEnabled", fix.providerEnabled ?: JSONObject.NULL)
            put("source", fix.source); put("provider", fix.provider ?: JSONObject.NULL)
            put("latitude", fix.latitude ?: JSONObject.NULL); put("longitude", fix.longitude ?: JSONObject.NULL)
            put("accuracyM", fix.accuracyM ?: JSONObject.NULL); put("timeMs", fix.timeMs ?: JSONObject.NULL)
            put("ageMs", fix.ageMs ?: JSONObject.NULL); put("isMock", fix.mock ?: JSONObject.NULL)
            put("note", fix.note ?: JSONObject.NULL)
        }) } })
    }.toString(2)
}

/** One user-requested bounded observation, with no network client or automatic upload/export. */
object LocalLocationCapture {
    @SuppressLint("MissingPermission")
    suspend fun collect(context: Context): LocalLocationReport = coroutineScope {
        val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
        val available = manager.allProviders.toSet()
        val observations = listOf("gps", "network", "fused").map { provider -> async {
          try {
            if (provider !in available) return@async CapturedFix(provider, false, "unavailable", note = "系统未提供此定位来源")
            val enabled = manager.isProviderEnabled(provider)
            val cached = manager.getLastKnownLocation(provider)
            val fresh = if (enabled) withTimeoutOrNull(10_000) { current(manager, provider, context) } else null
            val location = fresh ?: cached
            if (location == null) CapturedFix(provider, enabled, "none", note = if (enabled) "10 秒内未获得定位，且无缓存" else "定位来源已关闭，且无缓存")
            else CapturedFix(provider, enabled, if (fresh != null) "current" else "cached",
                location.provider, location.latitude, location.longitude,
                location.accuracy.takeIf { location.hasAccuracy() }, location.time,
                locationAgeMs(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos()),
                @Suppress("DEPRECATION") (if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider),
                if (fresh == null) "未取得本次定位，展示历史缓存；请查看定位时间和年龄" else null)
          } catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: SecurityException) { throw denied }
            catch (error: Exception) { CapturedFix(provider, null, "error", note = "系统接口异常：${error.javaClass.simpleName}") }
        } }.awaitAll()
        LocalLocationReport(System.currentTimeMillis(), observations)
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager, provider: String, context: Context): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) {
                    if (continuation.isActive) continuation.resume(it?.let(::Location))
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(Location(location))
                    }
                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    @Deprecated("Legacy Android callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                @Suppress("DEPRECATION") manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                if (!continuation.isActive) manager.removeUpdates(listener)
            }
        }
}

internal fun locationAgeMs(sourceElapsedNanos: Long, nowElapsedNanos: Long): Long? =
    if (sourceElapsedNanos <= 0 || nowElapsedNanos < sourceElapsedNanos) null
    else (nowElapsedNanos - sourceElapsedNanos) / 1_000_000
