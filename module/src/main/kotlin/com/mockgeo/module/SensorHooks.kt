package com.mockgeo.module

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import java.util.IdentityHashMap

/** A single stable proxy per listener, shared by all overloads and sensor types. */
internal object SensorHooks {
    private class Entry(val original: SensorEventListener) : SensorEventListener2 {
        val sensors = mutableSetOf<Sensor>()
        private val steps = StepAccumulator()
        private val motion = SensorMotion()
        override fun onSensorChanged(event: SensorEvent) {
            val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
            val type = event.sensor?.type
            if (!AppScopeHooks.isActive(state) || event.values.isEmpty() || type == null) {
                original.onSensorChanged(event)
                return
            }
            if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) {
                if (!SensorSynth.supports(type)) { original.onSensorChanged(event); return }
                // 加速度/陀螺/旋转向量/磁力：改值后转发，再恢复（框架可能复用数组）
                val backup = event.values.copyOf()
                runCatching { SensorSynth.apply(event, state, event.timestamp / 1_000_000L, motion.turnRate(state)) }
                try {
                    original.onSensorChanged(event)
                } finally {
                    backup.copyInto(event.values)
                }
                return
            }
            val old = event.values[0]
            val value = if (type == Sensor.TYPE_STEP_COUNTER) steps.counter(old, state.speed, state.session, SystemClock.elapsedRealtime())
                else steps.detector(state.speed, state.session, SystemClock.elapsedRealtime()) ?: return
            try { event.values[0] = value; original.onSensorChanged(event) }
            finally { event.values[0] = old } // framework may reuse this array
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = original.onAccuracyChanged(sensor, accuracy)
        override fun onFlushCompleted(sensor: Sensor?) { (original as? SensorEventListener2)?.onFlushCompleted(sensor) }
    }
    private val entries = IdentityHashMap<SensorEventListener, Entry>()
    private val depth = ThreadLocal.withInitial { 0 }

    fun install(api: XposedInterface) {
        for (method in SensorManager::class.java.declaredMethods) {
            if (method.name !in setOf("registerListener", "unregisterListener", "flush")) continue
            if (method.parameterTypes.firstOrNull() != SensorEventListener::class.java) continue
            runCatching { api.hook(method).intercept(XposedInterface.Hooker { chain ->
                val nesting = depth.get() ?: 0
                if (nesting > 0) return@Hooker chain.proceed()
                val listener = chain.args.firstOrNull() as? SensorEventListener ?: return@Hooker chain.proceed()
                val entry = synchronized(entries) { entries[listener] }
                    ?: if (method.name == "registerListener") Entry(listener) else return@Hooker chain.proceed()
                val sensor = chain.args.filterIsInstance<Sensor>().firstOrNull()
                val args = chain.args.toTypedArray().apply { this[0] = entry }
                depth.set(nesting + 1)
                try {
                    val result = chain.proceed(args)
                    synchronized(entries) {
                        if (method.name == "registerListener" && result == true && sensor != null) {
                            entry.sensors.add(sensor); entries[listener] = entry
                        } else if (method.name == "unregisterListener") {
                            if (sensor == null) entry.sensors.clear() else entry.sensors.remove(sensor)
                            if (entry.sensors.isEmpty()) entries.remove(listener)
                        }
                    }
                    result
                } finally { depth.set(nesting) }
            }) }.onFailure { ModuleLog.w("sensor ${method.name} install failed", it) }
        }
    }
}

/** Counter progression is monotonic within a session; pausing holds the value. */
internal class StepAccumulator {
    private var session = Long.MIN_VALUE
    private var lastAt = 0L
    private var lastSpeed = 0f
    private var total = 0.0
    private var lastDetectorAt = Long.MIN_VALUE
    private var detectorSession = Long.MIN_VALUE
    fun counter(real: Float, speed: Float, id: Long, now: Long): Float {
        if (id != session) { session = id; total = real.toDouble(); lastAt = now; lastSpeed = speed }
        val dt = (now - lastAt).coerceAtLeast(0)
        if (lastSpeed > 0f) total += dt / 1000.0
        lastAt = now; lastSpeed = speed
        return kotlin.math.floor(total).toFloat()
    }
    fun detector(speed: Float, id: Long, now: Long): Float? {
        if (id != detectorSession) { detectorSession = id; lastDetectorAt = Long.MIN_VALUE }
        if (speed <= 0f || (lastDetectorAt != Long.MIN_VALUE && now - lastDetectorAt < 500)) return null
        lastDetectorAt = now
        return 1f
    }
}
