package com.mockgeo.core.route

import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.RouteMode
import kotlin.math.abs

/**
 * 沿折线按距离推进的路线模拟器。
 *
 * 关键约定（docs/08 §6）：
 * - advance(dt) 用调用方传入的实际经过时间积分位移（不假设固定间隔，防 Doze 漂移）；
 * - 插值在两段端点间线性插值经纬度（短距离误差可忽略）；
 * - ONCE 到终点停住（speed 归 0）；LOOP 回绕；PING_PONG 反向。
 */
class RouteSimulator(
    private val points: List<LatLng>,
    private val speedProvider: (progressMeters: Double) -> Double,
    private val mode: RouteMode = RouteMode.ONCE,
) {

    private val segments: List<Segment>
    private val cumulative: DoubleArray
    private val length: Double

    /** 当前已行进距离（米）。PING_PONG 反向后表示"沿反方向的行进距离"。 */
    private var traveled = 0.0

    /** PING_PONG 方向：+1 正向 / -1 反向。 */
    private var direction = 1

    /** 是否已到达终点（ONCE 模式）。 */
    var finished = false
        private set
    var completedLaps = 0L
        private set

    init {
        require(points.size >= 2) { "route needs at least 2 points, got ${points.size}" }
        val segs = mutableListOf<Segment>()
        var acc = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val len = a.distanceTo(b)
            segs += Segment(a, b, len, acc)
            acc += len
        }
        segments = segs
        length = acc
        finished = length == 0.0 && mode == RouteMode.ONCE
        cumulative = DoubleArray(segs.size)
        for (i in segs.indices) cumulative[i] = segs[i].cumStart
    }

    val totalDistance: Double get() = length

    val progress: Double get() = traveled

    /** 当前所在位置。 */
    fun position(): LatLng {
        if (traveled <= 0.0) return segments.first().start
        if (traveled >= totalDistance) return segments.last().end
        val idx = segmentIndexFor(traveled)
        val seg = segments[idx]
        val t = if (seg.length == 0.0) 0.0 else (traveled - seg.cumStart) / seg.length
        return lerp(seg.start, seg.end, t)
    }

    /** 当前线段方位角。 */
    fun bearing(): Float {
        val idx = segmentIndexFor(traveled.coerceIn(0.0, if (totalDistance == 0.0) 0.0 else totalDistance - 1e-9))
        val seg = segments[idx]
        val base = seg.start.bearingTo(seg.end)
        return if (direction < 0) (base + 180f) % 360f else base
    }

    /**
     * 推进一帧。返回推进后的位置。
     * dtSeconds 为距上一帧的实际秒数；speedProvider 以"正向进度"为参数取速度。
     */
    fun advance(dtSeconds: Double): LatLng {
        if (finished || totalDistance == 0.0) return position()
        require(dtSeconds.isFinite()) { "elapsed time must be finite" }
        if (dtSeconds <= 0.0) return position()

        val speed = currentSpeed()
        if (speed == 0.0) return position()

        var remaining = speed * dtSeconds
        require(remaining.isFinite()) { "distance must be finite" }
        // Reduce whole cycles before consuming distance, including long background delays.
        if (mode == RouteMode.LOOP) {
            val distance = traveled + remaining
            require(distance.isFinite()) { "distance must be finite" }
            val laps = kotlin.math.floor(distance / totalDistance).toLong()
            completedLaps += laps.coerceAtMost(Long.MAX_VALUE - completedLaps)
            traveled = distance % totalDistance
            return position()
        }
        if (mode == RouteMode.PING_PONG) remaining %= totalDistance * 2.0
        // 逐段消费位移，处理跨段/回绕/反向
        var guard = 0
        while (remaining > 1e-9 && guard++ < 10_000) {
            val pos = traveled
            when (direction) {
                1 -> {
                    val segLeft = totalDistance - pos
                    if (remaining < segLeft) {
                        traveled = pos + remaining
                        remaining = 0.0
                    } else {
                        remaining -= segLeft
                        traveled = totalDistance
                        when (mode) {
                            RouteMode.ONCE -> {
                                finished = true
                                remaining = 0.0
                            }
                            RouteMode.LOOP -> traveled = 0.0
                            RouteMode.PING_PONG -> {
                                direction = -1
                                traveled = totalDistance
                            }
                        }
                    }
                }
                else -> {
                    if (remaining < pos) {
                        traveled = pos - remaining
                        remaining = 0.0
                    } else {
                        remaining -= pos
                        traveled = 0.0
                        when (mode) {
                            RouteMode.LOOP -> traveled = totalDistance
                            RouteMode.PING_PONG -> {
                                direction = 1
                                traveled = 0.0
                            }
                            RouteMode.ONCE -> {
                                finished = true
                                remaining = 0.0
                            }
                        }
                    }
                }
            }
        }
        return position()
    }

    /** 当前速度（m/s），供 SimConfig.speedMps 使用。 */
    fun currentSpeed(): Double =
        if (finished || totalDistance == 0.0) 0.0 else speedProvider(traveled).let {
            require(it.isFinite()) { "speed must be finite" }
            it.coerceAtLeast(0.0)
        }

    private fun segmentIndexFor(dist: Double): Int {
        // 二分：找最后一个 cumStart <= dist 的段
        var lo = 0
        var hi = segments.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= dist || abs(segments[mid].length) < 1e-12 && cumulative[mid] <= dist + 1e-9) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun lerp(a: LatLng, b: LatLng, t: Double): LatLng {
        val deltaLng = ((b.lng - a.lng + 540.0) % 360.0) - 180.0
        val lng = ((a.lng + deltaLng * t + 540.0) % 360.0) - 180.0
        return LatLng(a.lat + (b.lat - a.lat) * t, lng)
    }

    private data class Segment(val start: LatLng, val end: LatLng, val length: Double, val cumStart: Double)
}
