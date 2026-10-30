package com.mockgeo.core.model

/** 路线循环模式。 */
enum class RouteMode { ONCE, LOOP, PING_PONG }

/** 收藏点。 */
data class Favorite(
    val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val createdAt: Long,
    val groupName: String? = null,
)

/** 历史记录来源。 */
enum class HistorySource { MAP_TAP, SEARCH, FAVORITE, GPX, MANUAL, ROUTE }

data class HistoryEntry(
    val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val source: HistorySource,
    val createdAt: Long,
    val extra: String? = null,
)

/** 路线（含途经点）。 */
data class Route(
    val id: Long = 0,
    val name: String,
    val distanceM: Double,
    val durationS: Double?,
    val mode: RouteMode,
    val createdAt: Long,
    val updatedAt: Long,
    val points: List<RoutePoint> = emptyList(),
    /** Editable waypoints, separate from dense route geometry. Empty for legacy/imported tracks. */
    val controlPoints: List<LatLng> = emptyList(),
    val profile: String = "drive",
)

data class RoutePoint(
    val routeId: Long = 0,
    val seq: Int,
    val lat: Double,
    val lng: Double,
    val altitude: Double? = null,
    val speedMps: Double? = null,
)

/** 摇杆输出（归一化，死区已处理）。 */
data class JoystickVector(val dx: Float, val dy: Float) {
    val magnitude: Float get() = kotlin.math.sqrt(dx * dx + dy * dy)
    val isIdle: Boolean get() = magnitude < 0.001f

    /** 屏幕向量 → 方位角（北向上）：dy 取负（屏幕 y 向下）。 */
    fun toBearingDeg(): Float {
        if (isIdle) return 0f
        val deg = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble()))
        return ((deg % 360.0) + 360.0).toFloat() % 360f
    }

    companion object {
        const val DEAD_ZONE = 0.1f

        /** 原始输入应用死区后归一化。 */
        fun fromRaw(rawX: Float, rawY: Float): JoystickVector {
            val m = kotlin.math.sqrt(rawX * rawX + rawY * rawY)
            if (m <= DEAD_ZONE) return JoystickVector(0f, 0f)
            val scaled = (m - DEAD_ZONE) / (1f - DEAD_ZONE)
            return JoystickVector(rawX / m * scaled, rawY / m * scaled)
        }
    }
}
