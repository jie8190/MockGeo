package com.mockgeo.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import com.mockgeo.app.geo.AmapGeoClient
import com.mockgeo.app.geo.OsrmClient
import com.mockgeo.app.ui.map.MapController
import com.mockgeo.app.ui.map.MapControllerFactory
import com.mockgeo.core.data.prefs.SettingsRepository
import com.mockgeo.app.geo.AmapCoordinates
import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext

/**
 * Real native-map and AMap service checks. Requires prior user consent in the UI.
 * This helper never grants consent, changes system/network settings, or invokes simulation.
 *
 * am instrument -w -r -e mode map com.mockgeo.app.test/com.mockgeo.app.DeviceSmokeInstrumentation
 */
class MapSmokeInstrumentation(private val host: Instrumentation) {
    private var step = "application-ready"
    private var passed = 0
    private val results = Bundle()
    private val beijing = LatLng(39.9087, 116.3975)

    // Public streets around Ping'anli/Xisi, away from Tiananmen's restricted scenic area.
    // The coordinates are WGS84. Road snapping is checked instead of silently replacing
    // a failed request with straight-line geometry or a different transport profile.
    private val controls = listOf(LatLng(39.9300, 116.3650), LatLng(39.9360, 116.3700))

    fun run() {
        var success = false
        var activity: MainActivity? = null
        var controller: MapController? = null
        runBlocking {
            try {
                val application = host.targetContext.applicationContext as? MockGeoApp
                    ?: error("Target application is not MockGeoApp")
                withTimeout(20_000) { application.ready.await() }
                val settings = GlobalContext.get().get<SettingsRepository>().settings.first()
                check(settings.amapConsent) {
                    "AMap consent is absent. The user must approve it in the app before this test."
                }
                pass("application-ready", "existing user consent confirmed")

                step = "native-map-render"
                val renderStarted = SystemClock.elapsedRealtime()
                val launched = host.startActivitySync(Intent(host.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as? MainActivity
                    ?: error("MainActivity did not start")
                activity = launched
                val map = onMain {
                    // Detach the activity's Compose content normally. Its composition owns
                    // disposal of its original map; never invoke activity lifecycle methods.
                    launched.setContentView(FrameLayout(launched))
                    MapControllerFactory.create(launched, "amap").also {
                        controller = it
                        launched.setContentView(it.view)
                        it.onResume()
                    }
                }
                check(map.providerId == "amap") { "The native AMap renderer was not selected" }
                val nativeFrameMs = awaitNativeFrame(map, renderStarted)
                val size = withTimeout(2_000) {
                    var measured = onMain { map.view.width to map.view.height }
                    while (measured.first <= 0 || measured.second <= 0) {
                        delay(50)
                        measured = onMain { map.view.width to map.view.height }
                    }
                    measured
                }
                results.putLong("nativeMapFirstFrameMs", nativeFrameMs)
                results.putLong("nativeMapRenderWallMs", SystemClock.elapsedRealtime() - renderStarted)
                results.putInt("mapWidthPx", size.first)
                results.putInt("mapHeightPx", size.second)
                pass("native-map-render", "firstFrameMs=$nativeFrameMs view=${size.first}x${size.second}")

                val coordinates = AmapCoordinates(host.targetContext)
                checkSdkCoordinateSamples(coordinates)

                step = "wgs84-map-roundtrip"
                val roundTrip = coordinates.toWgs(coordinates.fromWgs(controls.first()))
                val transformError = controls.first().distanceTo(roundTrip)
                check(transformError < 0.5) { "Coordinate round-trip error is $transformError m" }
                onMain {
                    map.setFollowMode(false)
                    map.moveTo(controls.first(), zoom = 15.0, animate = false)
                    map.setMarker(controls.first(), "selection")
                    map.setRouteHandles(controls, editing = true)
                }
                withTimeout(6_000) {
                    while (map.cameraPosition.value.distanceTo(controls.first()) > 2.0) delay(50)
                }
                val cameraError = map.cameraPosition.value.distanceTo(controls.first())
                results.putDouble("coordinateRoundTripErrorM", transformError)
                results.putDouble("mapCameraWgs84ErrorM", cameraError)
                pass("wgs84-map-roundtrip", "transformErrorM=$transformError cameraErrorM=$cameraError")

                val client = AmapGeoClient(host.targetContext)
                step = "poi-search"
                val searchStarted = SystemClock.elapsedRealtime()
                val places = withTimeout(18_000) { client.search("北京天安门") }
                check(places.isNotEmpty()) { "POI search returned no results" }
                val nearest = places.minOf { it.position.distanceTo(beijing) }
                check(nearest < 5_000) { "Search results are not near the requested Beijing landmark" }
                results.putLong("poiSearchMs", SystemClock.elapsedRealtime() - searchStarted)
                results.putInt("poiCount", places.size)
                pass("poi-search", "elapsedMs=${SystemClock.elapsedRealtime() - searchStarted} results=${places.size}")

                step = "reverse-geocode"
                val reverseStarted = SystemClock.elapsedRealtime()
                val address = withTimeout(18_000) { client.reverse(beijing) }
                check(!address.isNullOrBlank()) { "Reverse geocoding returned no address" }
                check(address.contains("北京")) { "Reverse geocoding did not identify Beijing" }
                results.putLong("reverseGeocodeMs", SystemClock.elapsedRealtime() - reverseStarted)
                pass("reverse-geocode", "elapsedMs=${SystemClock.elapsedRealtime() - reverseStarted} Beijing address confirmed")

                for (profile in listOf("drive", "walk", "ride")) {
                    step = "route-$profile"
                    val started = SystemClock.elapsedRealtime()
                    val snapshot = controls.toList()
                    val route = withTimeout(if (profile == "drive") 18_000L else 33_000L) {
                        client.route(controls, profile)
                    }
                    check(controls == snapshot) { "$profile modified WGS84 control points" }
                    val geometryLength = checkRoute(profile, route, coordinates)
                    results.putLong("route_${profile}_ms", SystemClock.elapsedRealtime() - started)
                    results.putInt("route_${profile}_points", route.points.size)
                    results.putDouble("route_${profile}_geometry_m", geometryLength)
                    results.putDouble("route_${profile}_reported_m", route.distanceM)
                    onMain {
                        map.clearRouteOverlays()
                        map.addRouteOverlay(route.points)
                        map.setRouteHandles(controls, editing = true)
                        map.fitRoute(route.points)
                    }
                    pass("route-$profile", "elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                        "points=${route.points.size} geometryM=$geometryLength reportedM=${route.distanceM}")
                }
                success = true
            } catch (error: Throwable) {
                results.putString("failureStep", step)
                results.putString("failure", safeError(error))
                emit("FAIL", "$step: ${safeError(error)}")
            } finally {
                withContext(NonCancellable) {
                    val cleanupErrors = mutableListOf<String>()
                    onMain {
                        val map = controller
                        if (map != null) {
                            runCatching { map.onPause() }.onFailure { cleanupErrors += safeError(it) }
                            runCatching { map.dispose() }.onFailure { cleanupErrors += safeError(it) }
                        }
                        activity?.let { screen ->
                            // Detach only our view and ask Android to finish the activity.
                            // Do not directly call onPause/onDestroy on MainActivity.
                            runCatching { screen.setContentView(FrameLayout(screen)) }
                                .onFailure { cleanupErrors += safeError(it) }
                            runCatching { screen.finish() }.onFailure { cleanupErrors += safeError(it) }
                        }
                    }
                    if (cleanupErrors.isNotEmpty()) {
                        success = false
                        results.putString("cleanupFailure", cleanupErrors.joinToString("; "))
                        emit("FAIL", "cleanup: ${cleanupErrors.joinToString("; ")}")
                    } else if (controller != null) pass("cleanup", "map disposed and activity finish requested")
                }
            }
        }
        results.putInt("passedSteps", passed)
        results.putString("stream", if (success) "PASS: $passed native map/service checks\n"
            else "FAIL: native map/service checks; see failureStep/failure/cleanupFailure\n")
        host.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private suspend fun awaitNativeFrame(map: MapController, started: Long): Long {
        while (SystemClock.elapsedRealtime() - started <= 20_000) {
            map.status.value.firstFrameMs?.let { return it }
            delay(50)
        }
        error("Native map produced no first-frame callback within 20s: ${map.status.value.detail ?: "no detail"}")
    }

    private fun checkRoute(profile: String, route: OsrmClient.RouteResult, coordinates: AmapCoordinates): Double {
        check(route.points.size > 2) { "$profile returned ${route.points.size} points; road geometry is required" }
        check(route.distanceM.isFinite() && route.distanceM > 0) { "$profile returned an invalid road distance" }
        check(route.durationS.isFinite() && route.durationS >= 0) { "$profile returned an invalid duration" }
        val adjacent = route.points.zipWithNext { a, b -> a.distanceTo(b) }
        check(adjacent.all { it.isFinite() && it >= 0 && it < 1_500 }) {
            "$profile has an implausible adjacent coordinate jump"
        }
        val length = adjacent.sum()
        val direct = controls.first().distanceTo(controls.last())
        check(length in (direct * 0.5)..15_000.0) { "$profile geometry length is implausible: $length m" }
        check(route.distanceM in (direct * 0.5)..20_000.0) { "$profile declared road distance is implausible" }
        check(length / route.distanceM in 0.5..1.5) {
            "$profile road distance disagrees with its WGS84 polyline"
        }
        // The GCJ offset here is roughly 500m. A 350m snapping allowance permits
        // ordinary street matching but rejects a missing GCJ->WGS conversion.
        val startError = route.points.first().distanceTo(controls.first())
        val endError = route.points.last().distanceTo(controls.last())
        check(startError <= 350 && endError <= 350) {
            "$profile endpoints are not near WGS84 controls: start=$startError m end=$endError m"
        }
        // Sample a bounded number of positions; the normal renderer already
        // converts every route point, so this check must not multiply SDK work
        // by the size of a long GPX import.
        val stride = (route.points.size / 24).coerceAtLeast(1)
        check(route.points.filterIndexed { index, _ -> index % stride == 0 || index == route.points.lastIndex }.all {
            it.distanceTo(coordinates.toWgs(coordinates.fromWgs(it))) < 0.5
        }) { "$profile route does not survive the SDK coordinate boundary round trip" }
        return length
    }

    private data class CoordinateSample(val name: String, val point: LatLng,
                                        val expectIdentity: Boolean = false)

    private fun checkSdkCoordinateSamples(coordinates: AmapCoordinates) {
        val started = SystemClock.elapsedRealtime()
        val samples = listOf(
            CoordinateSample("Beijing", beijing),
            CoordinateSample("Zhoushan", LatLng(29.9853, 122.2072)),
            CoordinateSample("NanaoIsland", LatLng(23.4210, 117.0270)),
            CoordinateSample("ShenzhenNanao", LatLng(22.5360, 114.4900)),
            CoordinateSample("HongKong", LatLng(22.3193, 114.1694)),
            CoordinateSample("Macao", LatLng(22.1987, 113.5439)),
            CoordinateSample("Seoul", LatLng(37.5665, 126.9780), expectIdentity = true),
            CoordinateSample("Hanoi", LatLng(21.0285, 105.8542), expectIdentity = true),
            CoordinateSample("Vladivostok", LatLng(43.1155, 131.8855), expectIdentity = true),
            CoordinateSample("Tokyo", LatLng(35.6762, 139.6503), expectIdentity = true),
        )
        for (sample in samples) {
            step = "sdk-coordinate-${sample.name}"
            val forward = coordinates.fromWgs(sample.point)
            val restored = coordinates.toWgs(forward)
            val shift = sample.point.distanceTo(forward)
            val inverseError = sample.point.distanceTo(restored)
            val residual = coordinates.fromWgs(restored).distanceTo(forward)
            val identity = forward == sample.point
            results.putDouble("sdk_${sample.name}_shift_m", shift)
            results.putDouble("sdk_${sample.name}_roundtrip_m", inverseError)
            results.putBoolean("sdk_${sample.name}_identity", identity)
            // Print actual SDK measurements before assertions. No exact domestic
            // offset is assumed, including the SDK's coastal/island semantics.
            emit("MEASURE", "${sample.name}: shiftM=$shift roundTripM=$inverseError " +
                "sdkResidualM=$residual identity=$identity")
            check(inverseError < 0.5 && residual <= 0.25) {
                "${sample.name} SDK coordinate inverse did not converge accurately"
            }
            if (sample.expectIdentity) {
                check(identity && restored == sample.point) {
                    "SDK moved the overseas sample ${sample.name}; see actual measurements"
                }
            }
            if (sample.name == "Beijing") {
                check(shift > 10.0) { "SDK GPS conversion did not apply its mainland offset" }
            }
            pass(step, "official SDK round trip verified")
        }
        results.putLong("sdkCoordinateSamplesMs", SystemClock.elapsedRealtime() - started)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        host.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result) { "Main-thread action did not complete" }.getOrThrow()
    }

    private fun safeError(error: Throwable): String {
        val raw = error.message.orEmpty()
        val redacted = if (BuildConfig.AMAP_API_KEY.isBlank()) raw
            else raw.replace(BuildConfig.AMAP_API_KEY, "[redacted]")
        val message = redacted
            .replace(Regex("(?i)(?:api[_-]?key|key)=[^&\\s]+"), "key=[redacted]")
            .replace(Regex("(?i)[0-9a-f]{32}"), "[redacted]")
        return "${error.javaClass.simpleName}: ${message.take(700)}"
    }

    private fun pass(name: String, detail: String) {
        passed++
        emit("PASS", "$name: $detail")
    }

    private fun emit(kind: String, message: String) {
        host.sendStatus(0, Bundle().apply {
            putString("kind", kind)
            putString("step", step)
            putString("stream", "$kind $message\n")
        })
    }
}
