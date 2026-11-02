package com.mockgeo.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A separate APK/UID: it consumes public location APIs and never calls MockGeo's control plane. */
class ProbeActivity : Activity() {
    private lateinit var manager: LocationManager
    private lateinit var output: TextView
    private var web: WebView? = null
    private val events = JSONArray()
    private val listeners = mutableListOf<LocationListener>()
    private val cancellations = mutableListOf<CancellationSignal>()
    private val handler = Handler(Looper.getMainLooper())
    private var expectedLat = 39.9087
    private var expectedLng = 116.3975
    private var started = 0L
    private var finished = false
    private var durationMs = 60_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("keepScreenOn", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Explicit test-only opt-in: show this fixture over keyguard without dismissing it.
        // The device remains secured; no other application's UI is exposed.
        if (intent.getBooleanExtra("showWhenLocked", false)) {
            if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
            else {
                @Suppress("DEPRECATION")
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
        manager = getSystemService(LocationManager::class.java)
        durationMs = intent.getIntExtra("durationMs", 60_000).coerceIn(20_000, 180_000).toLong()
        expectedLat = intent.getStringExtra("lat")?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: expectedLat
        expectedLng = intent.getStringExtra("lng")?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: expectedLng
        output = TextView(this).apply { textSize = 15f; setPadding(24, 36, 24, 24) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 350))
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else begin()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) begin()
        else output.text = "未授予定位权限，检查未执行。"
    }

    @SuppressLint("MissingPermission", "SetJavaScriptEnabled")
    private fun begin() {
        started = SystemClock.elapsedRealtime()
        output.text = "独立定位检查 · UID ${android.os.Process.myUid()}\n预期 ${expectedLat}, ${expectedLng}\n检查 GPS / 网络 / 融合 / WebView，一共 ${durationMs / 1000} 秒，记录全程回调。\n\n"
        record("probe", "begin", JSONObject().put("uid", android.os.Process.myUid()))
        for (provider in listOf("gps", "network", "fused")) {
            try { recordLocation(provider, "last", manager.getLastKnownLocation(provider)) }
            catch (error: Exception) { recordError(provider, "last", error) }
            try {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        recordLocation(provider, "updates", location)
                    }
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                    @Deprecated("Legacy callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                listeners += listener
                manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (error: Exception) { recordError(provider, "updates", error) }
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val cancellation = CancellationSignal()
                    cancellations += cancellation
                    manager.getCurrentLocation(provider, cancellation, mainExecutor) { fix -> recordLocation(provider, "current", fix) }
                } catch (error: Exception) { recordError(provider, "current", error) }
            }
        }
        web?.apply {
            settings.javaScriptEnabled = true
            settings.setGeolocationEnabled(true)
            addJavascriptInterface(object {
                @JavascriptInterface fun result(latitude: Double, longitude: Double, accuracy: Double) {
                    handler.post {
                        recordLocation("webview", "geolocation", Location("webview").apply {
                            this.latitude = latitude; this.longitude = longitude; this.accuracy = accuracy.toFloat()
                        })
                    }
                }
                @JavascriptInterface fun error(code: Int) { handler.post { record("webview", "error", JSONObject().put("code", code)) } }
            }, "Probe")
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, origin == "https://probe.mockgeo.invalid/" || origin == "https://probe.mockgeo.invalid", false)
                }
            }
            loadDataWithBaseURL("https://probe.mockgeo.invalid/", """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <p id="status">浏览器 Geolocation 检查中…</p><script>
                navigator.geolocation.getCurrentPosition(p=>{
                  document.getElementById('status').textContent='浏览器已返回位置';
                  Probe.result(p.coords.latitude,p.coords.longitude,p.coords.accuracy);
                },e=>{document.getElementById('status').textContent='浏览器定位失败：'+e.code;Probe.error(e.code)},
                {enableHighAccuracy:true,maximumAge:0,timeout:15000});
                </script>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        handler.postDelayed({
            if (!finished) {
                record("probe", "complete", JSONObject())
                finished = true
                release()
                output.append("\n检查完成，结果写入本应用 report.json。\n")
            }
        }, durationMs)
    }

    private fun recordLocation(provider: String, api: String, location: Location?) {
        val value = JSONObject()
        if (location == null) value.put("result", "null")
        else {
            val distance = FloatArray(1)
            Location.distanceBetween(expectedLat, expectedLng, location.latitude, location.longitude, distance)
            @Suppress("DEPRECATION") val isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
            value.put("result", "fix").put("matchesExpected", distance[0] < 25f)
                .put("isMock", if (provider == "webview") JSONObject.NULL else isMock)
                .put("reportedProvider", location.provider).put("accuracy", location.accuracy.toDouble())
                .put("sourceElapsedMs", if (provider == "webview") JSONObject.NULL else location.elapsedRealtimeNanos / 1_000_000)
                .put("sourceAgeMs", if (provider == "webview") JSONObject.NULL else
                    (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000)
        }
        record(provider, api, value)
    }
    private fun recordError(provider: String, api: String, error: Exception) =
        record(provider, api, JSONObject().put("error", error.javaClass.simpleName))
    private fun record(provider: String, api: String, value: JSONObject) {
        if (finished) return
        value.put("provider", provider).put("api", api).put("elapsedMs", SystemClock.elapsedRealtime() - started)
        events.put(value)
        output.append("$provider/$api : $value\n")
        File(filesDir, "report.json").writeText(JSONObject().put("runId", intent.getStringExtra("runId"))
            .put("durationMs", durationMs).put("events", events).toString(2))
    }
    @SuppressLint("MissingPermission")
    private fun release() {
        listeners.forEach { runCatching { manager.removeUpdates(it) } }; listeners.clear()
        cancellations.forEach { it.cancel() }; cancellations.clear()
    }
    override fun onDestroy() {
        finished = true; handler.removeCallbacksAndMessages(null); release()
        web?.destroy(); web = null
        super.onDestroy()
    }
}
