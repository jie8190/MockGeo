package com.mockgeo.module

import android.hardware.Sensor
import android.hardware.SensorEvent
import kotlin.math.*

/** A flat virtual device. All motion/orientation outputs derive from one coordinate convention. */
internal object SensorSynth {
    private const val GRAVITY = 9.80665f
    fun supports(type: Int) = type in setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY, Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

    fun apply(event: SensorEvent, state: ScopeState, nowMs: Long, turnRate: Float = 0f): Boolean {
        return apply(event.sensor?.type ?: return false, event.values, state, nowMs, turnRate)
    }

    fun apply(type: Int, values: FloatArray, state: ScopeState, nowMs: Long, turnRate: Float = 0f): Boolean {
        if (!supports(type) || values.size < 3) return false
        val frequency = if (state.stepCadenceSpm > 0) state.stepCadenceSpm / 60.0 else 1.6
        val phase = 2 * PI * frequency * nowMs / 1000.0
        val amplitude = min(state.speed / 1.4f, 1f).coerceAtLeast(0f)
        val linear = floatArrayOf((0.04 * amplitude * sin(phase)).toFloat(),
            (0.04 * amplitude * cos(phase)).toFloat(), (0.35 * amplitude * sin(phase)).toFloat())
        val heading = Math.toRadians(state.bearing.toDouble())
        when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                linear.copyInto(values); values[2] += GRAVITY
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> linear.copyInto(values)
            Sensor.TYPE_GRAVITY -> { values[0] = 0f; values[1] = 0f; values[2] = GRAVITY }
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                values[0] = 0f; values[1] = 0f; values[2] = -turnRate
            }
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                values[0] = (-22 * sin(heading)).toFloat()
                values[1] = (22 * cos(heading)).toFloat()
                values[2] = -38f
            }
            else -> {
                // Android azimuth is clockwise from north; quaternion rotation is about Z.
                var z = sin(-heading / 2).toFloat()
                val w = cos(-heading / 2).toFloat()
                if (values.size == 3 && w < 0) z = -z
                values[0] = 0f; values[1] = 0f; values[2] = z
                if (values.size > 3) values[3] = w
                if (values.size > 4) values[4] = 0.05f
            }
        }
        if (type in setOf(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)) {
            for (index in 3 until min(values.size, 6)) values[index] = 0f
        }
        return true
    }
}

/** Changes in heading, not arbitrary oscillation, determine angular velocity. */
internal class SensorMotion {
    private var last: ScopeState? = null
    private var rate = 0f
    fun turnRate(state: ScopeState): Float {
        val previous = last
        if (previous == null || state.session != previous.session || state.elapsedMs <= previous.elapsedMs) {
            if (previous == null || state.session != previous.session || state.elapsedMs < previous.elapsedMs) rate = 0f
        } else {
            val delta = ((state.bearing - previous.bearing + 540f) % 360f) - 180f
            rate = (Math.toRadians(delta.toDouble()) * 1000 / (state.elapsedMs - previous.elapsedMs)).toFloat()
        }
        last = state
        return rate
    }
}
