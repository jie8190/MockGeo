package com.mockgeo.app.geo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mockgeo.app.BuildConfig
import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Manages user-configurable AMap API Key (with fallback to BuildConfig.AMAP_API_KEY),
 * APK signing certificate SHA-1 fingerprint inspection, and GCJ-02 / BD-09 -> WGS-84 coordinate conversion.
 */
object AmapKeyStore {
    private const val PREF_NAME = "mockgeo_amap_key"
    private const val KEY_CUSTOM_AMAP = "custom_amap_api_key"

    private val _customKey = MutableStateFlow<String?>(null)

    fun customKeyFlow(context: Context): StateFlow<String?> {
        if (_customKey.value == null) {
            _customKey.value = getCustomApiKey(context)
        }
        return _customKey
    }

    fun getCustomApiKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_AMAP, "")?.trim().orEmpty()
    }

    fun setCustomApiKey(context: Context, key: String) {
        val cleaned = key.trim()
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_AMAP, cleaned).apply()
        _customKey.value = cleaned
    }

    fun effectiveApiKey(context: Context): String {
        val custom = _customKey.value ?: getCustomApiKey(context).also { _customKey.value = it }
        return if (custom.isNotBlank()) custom else BuildConfig.AMAP_API_KEY
    }

    fun hasAvailableKey(context: Context): Boolean = effectiveApiKey(context).isNotBlank()

    @Suppress("DEPRECATION")
    fun getSigningSha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo
            val sigs = if (signingInfo?.hasMultipleSigners() == true) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory
            }
            sigs?.firstOrNull()?.toByteArray()
        } else {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching "未知"

        val md = MessageDigest.getInstance("SHA1")
        val digest = md.digest(certBytes)
        digest.joinToString(":") { "%02X".format(it) }
    }.getOrDefault("未知")

    // ==================== GCJ-02 / BD-09 -> WGS-84 Coordinate Conversion ====================

    private const val X_PI = PI * 3000.0 / 180.0
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): LatLng {
        if (outOfChina(gcjLat, gcjLng)) return LatLng(gcjLat, gcjLng)
        var dLat = transformLat(gcjLng - 105.0, gcjLat - 35.0)
        var dLng = transformLng(gcjLng - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        val mgLat = gcjLat + dLat
        val mgLng = gcjLng + dLng
        return LatLng(
            (gcjLat * 2 - mgLat).coerceIn(-90.0, 90.0),
            (gcjLng * 2 - mgLng).coerceIn(-180.0, 180.0),
        )
    }

    fun bd09ToWgs84(bdLat: Double, bdLng: Double): LatLng {
        val x = bdLng - 0.0065
        val y = bdLat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        val gcjLng = z * cos(theta)
        val gcjLat = z * sin(theta)
        return gcj02ToWgs84(gcjLat, gcjLng)
    }

    /**
     * Parses free-form clipboard text into (lat, lng, swappedOrder).
     * Automatically detects Chinese coordinate pickers that output `lng, lat` (e.g. `116.397428, 39.90923`).
     */
    fun parseCoordinateText(raw: String): Triple<Double, Double, Boolean>? {
        val matches = Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(raw).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (matches.size < 2) return null
        val first = matches[0]
        val second = matches[1]
        return when {
            abs(first) > 90.0 && abs(first) <= 180.0 && abs(second) <= 90.0 ->
                Triple(second, first, true)
            first in 72.0..136.0 && second in 3.0..54.0 ->
                Triple(second, first, true)
            abs(first) <= 90.0 && abs(second) <= 180.0 ->
                Triple(first, second, false)
            else -> null
        }
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
