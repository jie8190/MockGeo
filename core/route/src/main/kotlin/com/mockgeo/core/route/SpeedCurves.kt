package com.mockgeo.core.route

import kotlin.math.sin

/**
 * 速度曲线（docs/08 §6：匀速 / 正弦加减速）。
 */
object SpeedCurves {

    /** 匀速。 */
    fun constant(mps: Double): (Double) -> Double = { mps }

    /**
     * 正弦加减速：在路线两端 [rampMeters] 范围内从 0 平滑升到巡航速度。
     * progressMeters 为正向进度。
     */
    fun sine(mps: Double, rampMeters: Double, totalDistance: Double): (Double) -> Double {
        require(mps.isFinite() && mps >= 0.0) { "speed must be finite and >= 0" }
        require(rampMeters.isFinite() && rampMeters >= 0.0) { "ramp must be finite and >= 0" }
        require(totalDistance.isFinite() && totalDistance >= 0.0)
        return { progress ->
            val factor = if (rampMeters == 0.0) {
                1.0
            } else if (totalDistance <= rampMeters * 2) {
                // 路线太短：整体按半正弦走
                val t = if (totalDistance == 0.0) 0.0 else progress / totalDistance
                sin(t * Math.PI).coerceAtLeast(0.0)
            } else {
                val up = (progress / rampMeters).coerceIn(0.0, 1.0)
                val down = ((totalDistance - progress) / rampMeters).coerceIn(0.0, 1.0)
                sin(up * Math.PI / 2) * sin(down * Math.PI / 2)
            }
            // A distance-based curve cannot leave an endpoint at exactly zero speed.
            // Keep a low rolling speed; ONCE completion supplies the final zero.
            mps * factor.coerceIn(0.08, 1.0)
        }
    }
}
