package com.mockgeo.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Separate UID test: only public location APIs; never reads the control plane. */
@SuppressLint("MissingPermission")
class LifecycleProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: LocationManager
    private lateinit var view: TextView
    private val events = JSONArray()
    private val intents = mutableListOf<PendingIntent>()
    private val listeners = mutableListOf<LocationListener>()
    private val cancellations = mutableListOf<CancellationSignal>()
    private var start = 0L
    private var removed = false
    private var closed = false
    private var expectedMock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24) }
        setContentView(view)
        manager = getSystemService(LocationManager::class.java)
        expectedMock = intent.getBooleanExtra("expectedMock", false)
        start = SystemClock.elapsedRealtime()
        PendingProbeReceiver.consumer = ::onPending
        PendingProbeReceiver.flushConsumer = { provider, code, valid ->
            record(provider, "flush", JSONObject().put("code", code).put("validType", valid))
        }
        record("probe", "begin", JSONObject().put("uid", android.os.Process.myUid()))
        for (provider in listOf("gps", "network", "fused")) {
            val pending = PendingIntent.getBroadcast(this, provider.hashCode(),
                Intent(this, PendingProbeReceiver::class.java).putExtra("provider", provider),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            intents += pending
            attempt(provider, "pending-register") { manager.requestLocationUpdates(provider, 500, 0f, pending) }
            val single = object : LocationListener {
                override fun onLocationChanged(location: Location) = fix(provider, "single", location)
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Legacy") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            listeners += single
            @Suppress("DEPRECATION")
            attempt(provider, "single-register") { manager.requestSingleUpdate(provider, single, Looper.getMainLooper()) }
            if (Build.VERSION.SDK_INT >= 30) {
                val pre = CancellationSignal().apply { cancel() }
                try {
                    manager.getCurrentLocation(provider, pre, mainExecutor) { fix(provider, "pre-cancel-callback", it) }
                } catch (expected: OperationCanceledException) {
                    record(provider, "pre-cancel-rejected", JSONObject().put("result", "cancelled"))
                } catch (error: Exception) {
                    record(provider, "pre-cancel-register", JSONObject().put("error", error.javaClass.simpleName))
                }
                val queued = CancellationSignal()
                cancellations += queued
                // Delay execution so cancel deterministically precedes callback consumption.
                attempt(provider, "queued-cancel-register") {
                    manager.getCurrentLocation(provider, queued, { action -> handler.postDelayed(action, 1500) }) {
                        fix(provider, "queued-cancel-callback", it)
                    }
                    queued.cancel()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) handler.postDelayed({
            listOf("gps", "network", "fused").zip(intents).forEachIndexed { index, (provider, pending) ->
                attempt(provider, "flush-request") { manager.requestFlush(provider, pending, 710 + index) }
            }
        }, 3000)
        handler.postDelayed({
            intents.forEach { runCatching { manager.removeUpdates(it) } }
            record("probe", "removed", JSONObject())
            // Allow already queued Binder deliveries to drain before checking removal.
            handler.postDelayed({ removed = true }, 1000)
        }, 7000)
        handler.postDelayed({
            record("probe", "complete", JSONObject())
            cleanup()
        }, 12000)
    }

    private fun attempt(provider: String, api: String, block: () -> Unit) {
        try { block() } catch (error: Exception) {
            record(provider, api, JSONObject().put("error", error.javaClass.simpleName))
        }
    }
    private fun onPending(provider: String, location: Location?) = fix(provider, if (removed) "pending-after-remove" else "pending", location)
    private fun fix(provider: String, api: String, location: Location?) {
        val value = JSONObject()
        if (location == null) value.put("result", "null") else {
            val distance = FloatArray(1)
            Location.distanceBetween(39.9087, 116.3975, location.latitude, location.longitude, distance)
            @Suppress("DEPRECATION") val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
            value.put("result", "fix").put("matchesExpected", distance[0] < 25)
                .put("reportedProvider", location.provider).put("isMock", mock).put("mockMatches", mock == expectedMock)
        }
        record(provider, api, value)
    }
    private fun record(provider: String, api: String, value: JSONObject) {
        if (closed) return
        value.put("provider", provider).put("api", api).put("elapsedMs", SystemClock.elapsedRealtime() - start)
        events.put(value)
        view.append("$provider/$api $value\n")
        File(filesDir, "lifecycle-report.json").writeText(JSONObject().put("runId", intent.getStringExtra("runId")).put("events", events).toString(2))
    }
    private fun cleanup() {
        closed = true
        intents.forEach { runCatching { manager.removeUpdates(it) }; it.cancel() }
        listeners.forEach { runCatching { manager.removeUpdates(it) } }
        cancellations.forEach { it.cancel() }
        PendingProbeReceiver.consumer = null
        PendingProbeReceiver.flushConsumer = null
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); cleanup(); super.onDestroy() }
}

class PendingProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 31 && intent.hasExtra(LocationManager.KEY_FLUSH_COMPLETE)) {
            @Suppress("DEPRECATION") val code = intent.extras?.get(LocationManager.KEY_FLUSH_COMPLETE)
            flushConsumer?.invoke(intent.getStringExtra("provider") ?: "unknown", code as? Int ?: -1, code is Int)
            return
        }
        @Suppress("DEPRECATION") val location = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED)
        consumer?.invoke(intent.getStringExtra("provider") ?: "unknown", location)
    }
    companion object {
        var consumer: ((String, Location?) -> Unit)? = null
        var flushConsumer: ((String, Int, Boolean) -> Unit)? = null
    }
}
