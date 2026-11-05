package com.mockgeo.probe

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Samples observed motion only; no coordinates, hardware step totals or health-platform access. */
class CadenceProbeActivity : Activity(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val samples = JSONArray()
    private lateinit var sensors: SensorManager
    private lateinit var view: TextView
    private var start = 0L
    private var closed = false
    private var accepted = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = TextView(this).apply { textSize = 18f; setPadding(30,60,30,30) }
        setContentView(view)
        sensors = getSystemService(SensorManager::class.java)
        start = SystemClock.elapsedRealtime()
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accepted = sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        write(false)
        handler.postDelayed({ sensors.unregisterListener(this); closed = true; write(true) }, 8000)
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (closed || event.values.size < 3) return
        samples.put(JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-start)
            .put("eventNanos",event.timestamp).put("z",event.values[2].toDouble()))
        if (samples.length() % 50 == 0) write(false)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    private fun write(complete: Boolean) {
        val report = JSONObject().put("runId",intent.getStringExtra("runId"))
            .put("accepted",accepted).put("complete",complete).put("samples",samples)
        File(filesDir,"cadence-report.json").writeText(report.toString())
        view.text = "Cadence signal check\nSamples: ${samples.length()}\nComplete: $complete"
    }
    override fun onDestroy() {
        closed=true;handler.removeCallbacksAndMessages(null)
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        super.onDestroy()
    }
}
