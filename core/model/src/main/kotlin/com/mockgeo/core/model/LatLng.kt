package com.mockgeo.core.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 经纬度坐标。内部统一用十进制度；距离单位米，方位角 0~360（正北为 0，顺时针）。
 */
data class LatLng(val lat: Double, val lng: Double) {

    init {
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lng in -180.0..180.0) { "longitude out of range: $lng" }
    }

    /** haversine 距离，米。短距离误差 < 0.5%。 */
    fun distanceTo(other: LatLng): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLng / 2).pow(2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 初始方位角（从本点指向 other），0~360，正北 0，顺时针。 */
    fun bearingTo(other: LatLng): Float {
        val lat1 = Math.toRadians(lat)
        val lat2 = Math.toRadians(other.lat)
        val dLng = Math.toRadians(other.lng - lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg % 360.0) + 360.0).toFloat() % 360f
    }

    /** 沿给定方位角移动 distanceMeters 后的目标点（球面目标点公式）。 */
    fun destination(bearingDeg: Double, distanceMeters: Double): LatLng {
        if (distanceMeters == 0.0) return this
        val d = distanceMeters / EARTH_RADIUS_M
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val lat2 = kotlin.math.asin(
            sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br),
        )
        val lng2 = lng1 + atan2(
            sin(br) * sin(d) * cos(lat1),
            cos(d) - sin(lat1) * sin(lat2),
        )
        return LatLng(
            lat = normalizeLat(Math.toDegrees(lat2)),
            lng = normalizeLng(Math.toDegrees(lng2)),
        )
    }

    override fun toString(): String = "%.6f,%.6f".format(lat, lng)

    companion object {
        const val EARTH_RADIUS_M = 6_371_000.0

        fun normalizeLat(lat: Double): Double = lat.coerceIn(-90.0, 90.0)

        fun normalizeLng(lng: Double): Double {
            var v = lng % 360.0
            if (v > 180.0) v -= 360.0
            if (v < -180.0) v += 360.0
            return v
        }

        /** 解析 "lat,lng" 文本（用户手动输入坐标用），容忍空格。 */
        fun parse(text: String): LatLng? {
            val parts = text.split(',', limit = 2)
            if (parts.size != 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lng = parts[1].trim().toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return LatLng(lat, lng)
        }
    }
}
