package com.mockgeo.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.*
import android.location.*
import android.os.*
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Keeps the SAME subscriptions through normal -> exempt -> resumed -> stopped phases. */
@SuppressLint("MissingPermission")
class PolicyProbeActivity : Activity() {
    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private lateinit var view: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val events = JSONArray()
    private val listeners = mutableListOf<LocationListener>()
    private val intents = mutableListOf<PendingIntent>()
    private var phase = "normal"
    private var phaseAt = 0L
    private var began = 0L
    private var closed = false
    private var runId: String? = null
    private var lastSensorAt = 0L
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val matches = status.satelliteCount == intent.getIntExtra("satellites", 12) && status.satelliteCount > 0 &&
                status.getSvid(0) == 1 && abs(status.getCn0DbHz(0) - 30f) < 0.0001 && abs(status.getElevationDegrees(0) - 15f) < 0.0001
            record("gnss", "status", JSONObject().put("matchesExpected", matches))
        }
    }
    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSensorAt < 500) return
            lastSensorAt = now
            val v = event.values
            record("sensor", "magnetic", JSONObject().put("matchesExpected",
                abs(v[0]) < 0.0001 && abs(v[1] - 22f) < 0.0001 && abs(v[2] + 38f) < 0.0001))
        }
    }
    private val nmea = OnNmeaMessageListener { sentence, _ ->
        if (sentence.startsWith("\$GPGGA")) {
            val fields = sentence.substringBefore('*').split(',')
            val matches = runCatching {
                fun coordinate(text: String, sign: String): Double {
                    val raw = text.toDouble(); val degrees = kotlin.math.floor(raw / 100)
                    return (degrees + (raw - degrees * 100) / 60) * if (sign == "S" || sign == "W") -1 else 1
                }
                val distance = FloatArray(1)
                Location.distanceBetween(39.9087, 116.3975, coordinate(fields[2], fields[3]), coordinate(fields[4], fields[5]), distance)
                distance[0] < 25f
            }.getOrDefault(false)
            record("nmea", "gga", JSONObject().put("matchesExpected", matches))
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            for (provider in listOf("gps", "network", "fused")) {
                runCatching { fix(provider, "last", lm.getLastKnownLocation(provider)) }
                    .onFailure { record(provider, "last", JSONObject().put("error", it.javaClass.simpleName)) }
            }
            handler.postDelayed(this, 700)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runId = intent.getStringExtra("runId")
        began = SystemClock.elapsedRealtime(); phaseAt = began
        view = TextView(this).apply { textSize = 14f; setPadding(20, 24, 20, 24) }
        setContentView(view)
        lm = getSystemService(LocationManager::class.java); sm = getSystemService(SensorManager::class.java)
        PolicyProbeReceiver.consumer = { provider, location -> fix(provider, "pending", location) }
        for (provider in listOf("gps", "network", "fused")) {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = fix(provider, "updates", location)
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Legacy") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            listeners += listener
            val pending = PendingIntent.getBroadcast(this, 100 + provider.hashCode(),
                Intent(this, PolicyProbeReceiver::class.java).putExtra("provider", provider),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            intents += pending
            runCatching { lm.requestLocationUpdates(provider, 500L, 0f, listener, Looper.getMainLooper()); lm.requestLocationUpdates(provider, 500L, 0f, pending) }
                .onFailure { record(provider, "register", JSONObject().put("error", it.javaClass.simpleName)) }
        }
        runCatching { lm.registerGnssStatusCallback(gnss, handler); lm.addNmeaListener(nmea, handler) }
            .onFailure { record("gnss", "register", JSONObject().put("error", it.javaClass.simpleName)) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        record("probe", "ready"); handler.post(poll)
        handler.postDelayed({ complete() }, 45_000) // bounded even if host disappears
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = intent.getStringExtra("phase") ?: return
        if (next == "complete") { complete(); return }
        if (next !in setOf("transition", "exempt", "resumed", "stopped", "restarted", "removed")) return
        if (next == "removed") { release(); handler.removeCallbacks(poll) }
        phase = next; phaseAt = SystemClock.elapsedRealtime(); record("probe", "phase")
    }
    private fun fix(provider: String, api: String, location: Location?) {
        val result = JSONObject().put("result", if (location == null) "null" else "fix")
        if (location != null) {
            val distance = FloatArray(1)
            Location.distanceBetween(39.9087, 116.3975, location.latitude, location.longitude, distance)
            result.put("matchesExpected", distance[0] < 25f).put("reportedProvider", location.provider)
        }
        record(provider, api, result)
    }
    private fun record(provider: String, api: String, event: JSONObject = JSONObject()) {
        if (closed) return
        event.put("provider", provider).put("api", api).put("phase", phase)
            .put("elapsedMs", SystemClock.elapsedRealtime() - began).put("phaseMs", SystemClock.elapsedRealtime() - phaseAt)
        events.put(event)
        view.text = "位置订阅生命周期检查\n阶段：$phase\n事件数：${events.length()}"
        File(filesDir, "policy-report.json").writeText(JSONObject().put("runId", runId).put("events", events).toString(2))
    }
    private fun complete() { if (!closed) { record("probe", "complete"); closed = true; handler.removeCallbacksAndMessages(null); release() } }
    private fun release() {
        listeners.forEach { runCatching { lm.removeUpdates(it) } }
        intents.forEach { runCatching { lm.removeUpdates(it) }; it.cancel() }
        runCatching { lm.unregisterGnssStatusCallback(gnss); lm.removeNmeaListener(nmea); sm.unregisterListener(sensorListener) }
        PolicyProbeReceiver.consumer = null
    }
    override fun onDestroy() { complete(); super.onDestroy() }
}

class PolicyProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION") val fix = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED)
        consumer?.invoke(intent.getStringExtra("provider") ?: "unknown", fix)
    }
    companion object { var consumer: ((String, Location?) -> Unit)? = null }
}
