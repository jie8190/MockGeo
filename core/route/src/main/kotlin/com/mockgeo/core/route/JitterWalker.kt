package com.mockgeo.core.route

import com.mockgeo.core.model.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 抖动器：半径内的随机游走（不是每帧独立随机，避免"抽搐"观感——docs/08 §6）。
 *
 * 用 Ornstein-Uhlenbeck 式的一阶回归：每帧向随机方向走一步，
 * 但同时被拉回中心，长期均值 = 0，标准差 ≈ radius。
 */
class JitterWalker(
    private val radiusMeters: Double,
    private val seed: Long = System.nanoTime(),
) {
    init {
        require(radiusMeters >= 0.0) { "radius must be >= 0" }
    }

    private val random = java.util.Random(seed)
    private var offsetX = 0.0
    private var offsetY = 0.0

    /** 推进一帧并返回相对中心的偏移（米，x=东向 y=北向）。 */
    fun step(dtSeconds: Double): Offset {
        if (radiusMeters == 0.0) return Offset(0.0, 0.0)
        // OU 过程 Euler 积分稳定性要求 dt < 2/θ（θ=1）；超长间隔按 2s 截断
        // （抖动是观感层，截断不影响坐标正确性）
        val dt = dtSeconds.coerceIn(0.0, 2.0)
        // OU 过程：dx = -θ·x·dt + σ·√dt·N(0,1)，θ=1 使自相关时间 ≈ 1s
        val theta = 1.0
        val sigma = radiusMeters * sqrt2OverPi // 使稳态 std ≈ radius
        val sqrtDt = kotlin.math.sqrt(dt)
        offsetX += -theta * offsetX * dt + sigma * sqrtDt * random.nextGaussian()
        offsetY += -theta * offsetY * dt + sigma * sqrtDt * random.nextGaussian()
        // 硬夹取，保证永不超界
        val m = kotlin.math.sqrt(offsetX * offsetX + offsetY * offsetY)
        if (m > radiusMeters) {
            offsetX = offsetX / m * radiusMeters
            offsetY = offsetY / m * radiusMeters
        }
        return Offset(offsetX, offsetY)
    }

    /** 把米制偏移叠加到经纬度上。 */
    fun apply(base: LatLng, offset: Offset): LatLng {
        if (offset.x == 0.0 && offset.y == 0.0) return base
        val dLat = offset.y / 111_320.0
        val dLng = offset.x / (111_320.0 * cos(Math.toRadians(base.lat)).coerceAtLeast(1e-6))
        return LatLng(base.lat + dLat, base.lng + dLng)
    }

    data class Offset(val x: Double, val y: Double)

    companion object {
        private val sqrt2OverPi = kotlin.math.sqrt(2.0 / PI)

        /** 供测试构造确定性实例。 */
        fun deterministic(radiusMeters: Double, seed: Long) = JitterWalker(radiusMeters, seed)
    }
}

/** 摇杆推进器：把归一化向量 + 速度换算成逐帧位移。松开后速度渐降。 */
class JoystickMover(
    private val speedMps: Double,
    /** 松开后的减速时间常数（秒）；0 = 立即停。 */
    private val decaySeconds: Double = 0.5,
) {
    private var currentSpeed = 0.0
    private var lastBearing = 0.0

    /**
     * 推进一帧。vector 非空时按其方位角全速（×幅值）移动；
     * 空闲时速度按指数衰减。返回新位置。
     * dt 用实际经过时间（不设上限——Doze/后台恢复后的长间隔是真实位移）。
     */
    fun advance(from: LatLng, vector: com.mockgeo.core.model.JoystickVector, dtSeconds: Double): LatLng {
        val dt = dtSeconds.coerceAtLeast(0.0)
        if (vector.isIdle) {
            if (decaySeconds <= 0.0 || currentSpeed == 0.0) {
                currentSpeed = 0.0
                return from
            }
            currentSpeed *= exp(-dt / decaySeconds)
            if (currentSpeed < 0.05) {
                currentSpeed = 0.0
                return from
            }
            return from.destination(lastBearing, currentSpeed * dt)
        }
        lastBearing = vector.toBearingDeg().toDouble()
        currentSpeed = speedMps * vector.magnitude.toDouble()
        return from.destination(lastBearing, currentSpeed * dt)
    }

    fun currentBearing(): Float = lastBearing.toFloat()

    fun currentSpeed(): Double = currentSpeed
}
