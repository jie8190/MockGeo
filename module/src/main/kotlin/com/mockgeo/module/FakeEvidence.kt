package com.mockgeo.module

import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** 身份隐藏判定：模拟开启且未要求"标准模拟标记"时，隐藏模拟身份。 */
internal object IdentityGuard {
    fun shouldHideMock(cfg: ModuleConfig): Boolean = cfg.enabled && !cfg.reportMock
}

/**
 * 证据合成（纯函数，便于 JVM 单测）。
 *
 * 设计原则：所有证据都由 (lat, lng) 确定性派生——同一位置每次生成一致，
 * 避免"每次扫描都换一批 AP/基站"这种一眼假的抖动。
 */
internal object FakeEvidence {

    private val DEFAULT_SSIDS = listOf(
        "FAKE-WIFI-2.4G", "CMCC-FAKE", "ChinaNet-FAKE",
        "FAKE-HOME", "FAKE-OFFICE", "FAKE-CAFE", "FAKE-MALL",
    )

    fun ssids(): List<String> = DEFAULT_SSIDS

    /** 由坐标派生 6 字节 BSSID（本地管理位 0x02 + 坐标哈希），同坐标稳定。 */
    fun bssid(lat: Double, lng: Double, index: Int): String {
        var h = lat.toRawBits() * 31 + lng.toRawBits() + index * 0x9E3779B1L
        h = h xor (h ushr 33)
        val bytes = ByteArray(6)
        bytes[0] = 0x02
        for (i in 1..5) {
            bytes[i] = ((h ushr ((i - 1) * 8)) and 0xFF).toByte()
        }
        return bytes.joinToString(":") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
    }

    fun bssid(cfg: ModuleConfig, index: Int): String = bssid(cfg.lat, cfg.lng, index)

    data class CellIdentity(val mcc: Int, val mnc: Int, val ci: Int, val pci: Int, val tac: Int)

    /** 由坐标派生小区参数（mcc 固定 460；mnc/ci/tac/pci 按坐标哈希）。 */
    fun cellIdentity(lat: Double, lng: Double): CellIdentity {
        var h = lat.toRawBits() * 17 + lng.toRawBits() * 131
        h = h xor (h ushr 29)
        val mnc = abs(h % 99).toInt().coerceIn(0, 99)
        val ci = abs((h ushr 8) % 268_435_455L).toInt()
        val tac = abs((h ushr 24) % 65_535L).toInt()
        val pci = abs((h ushr 40) % 503L).toInt()
        return CellIdentity(460, mnc, ci, pci, tac)
    }

    fun cellIdentity(cfg: ModuleConfig): CellIdentity = cellIdentity(cfg.lat, cfg.lng)

    /** UTC 的 hhmmss（NMEA 时间字段）。 */
    fun nmeaTime(timeMs: Long): Int {
        val totalSeconds = (timeMs / 1000) % 86_400
        val hh = (totalSeconds / 3600).toInt()
        val mm = ((totalSeconds % 3600) / 60).toInt()
        val ss = (totalSeconds % 60).toInt()
        return hh * 10_000 + mm * 100 + ss
    }

    /** NMEA 校验：`$` 与 `*` 之间所有字符异或。 */
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return String.format(Locale.US, "%02X", c and 0xFF)
    }

    /** 经纬度 → NMEA 的 ddmm.mmmm / dddmm.mmmm。 */
    fun dmmText(value: Double, isLat: Boolean): String {
        val absValue = abs(value)
        val deg = absValue.toInt()
        val minutes = (absValue - deg) * 60.0
        return String.format(Locale.US, if (isLat) "%02d%07.4f" else "%03d%07.4f", deg, minutes)
    }

    fun hemisphere(value: Double, isLat: Boolean): String = when {
        isLat && value >= 0 -> "N"
        isLat -> "S"
        value >= 0 -> "E"
        else -> "W"
    }

    /** $GPGGA：定位质量 1，卫星 08，HDOP 0.9，海拔取配置。 */
    fun nmeaGga(lat: Double, lng: Double, altitude: Double, timeMs: Long, satellites: Int = 12): String {
        val body = String.format(Locale.US, "GPGGA,%06d.000,%s,%s,%s,%s,%d,%02d,0.9,%.1f,M,0.0,M,,",
            nmeaTime(timeMs),
            dmmText(lat, true), hemisphere(lat, true),
            dmmText(lng, false), hemisphere(lng, false),
            if (satellites > 0) 1 else 0, satellites.coerceIn(0, 64), altitude,
        )
        return wrap(body)
    }

    fun nmeaGga(cfg: ModuleConfig, timeMs: Long): String = nmeaGga(cfg.lat, cfg.lng, cfg.altitude, timeMs, cfg.satellites)

    /** $GPRMC：速度换算成节，航向取 bearing。 */
    fun nmeaRmc(lat: Double, lng: Double, speedMps: Float, bearingDeg: Float, timeMs: Long): String {
        val knots = speedMps * 1.943844f
        val date = SimpleDateFormat("ddMMyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timeMs))
        val body = String.format(Locale.US, "GPRMC,%06d.000,A,%s,%s,%s,%s,%.1f,%.1f,%s,,",
            nmeaTime(timeMs),
            dmmText(lat, true), hemisphere(lat, true),
            dmmText(lng, false), hemisphere(lng, false),
            knots, bearingDeg, date,
        )
        return wrap(body)
    }

    fun nmeaRmc(cfg: ModuleConfig, timeMs: Long): String =
        nmeaRmc(cfg.lat, cfg.lng, cfg.speedMps, cfg.bearingDeg, timeMs)

    private fun wrap(body: String): String = "\$$body*${checksum(body)}\r\n"

    /** 按时间推进的累计步数（默认 60 步/分；静止不产生步数）。 */
    fun steps(cfg: ModuleConfig, elapsedMs: Long, cadenceSpm: Int = 60): Int {
        if (cfg.speedMps <= 0f) return 0
        return ((elapsedMs / 1000.0) * (cadenceSpm / 60.0)).roundToInt()
    }
}
