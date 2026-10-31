package com.mockgeo.app

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.mockgeo.app.sim.SimController
import com.mockgeo.app.sim.SimulationService
import com.mockgeo.core.data.prefs.AppSettings
import com.mockgeo.core.data.prefs.SettingsRepository
import com.mockgeo.core.engine.EngineRouter
import com.mockgeo.core.engine.RootXposedBridge
import com.mockgeo.core.engine.SimulationSession
import com.mockgeo.core.model.ControlPlane
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.RouteMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext

/**
 * Real-device smoke checks, executed in the target application's UID.
 * No JUnit/AndroidX test dependency, persistent preference writes, UI or network operations.
 *
 * am instrument -w -r com.mockgeo.app.test/com.mockgeo.app.DeviceSmokeInstrumentation
 * am instrument -w -r -e mode lease com.mockgeo.app.test/com.mockgeo.app.DeviceSmokeInstrumentation
 * am instrument -w -r -e mode map com.mockgeo.app.test/com.mockgeo.app.DeviceSmokeInstrumentation
 *
 * Lease mode prints LEASE_READY, then holds for 60 seconds. Force-stopping the target
 * process during that window deliberately prevents finally cleanup, exercising the
 * module's independent lease. A normal return still cleans up.
 */
@SuppressLint("MissingPermission")
class DeviceSmokeInstrumentation : Instrumentation() {
    private var mode = "smoke"
    private var passed = 0
    private var step = "initialization"
    private val beijing = LatLng(39.9087, 116.3975)
    private var expectedMock = true
    private var compatibilityOverride: Boolean? = null
    private var requiredModule: String? = null
    private val results = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "smoke"
        compatibilityOverride = arguments?.getString("compatibility")?.toBooleanStrictOrNull()
        requiredModule = arguments?.getString("requiredModule")
        start()
    }

    override fun onStart() {
        super.onStart()
        if (mode == "data") {
            DataSmokeChecks(this).run()
            return
        }
        if (mode == "map") {
            MapSmokeInstrumentation(this).run()
            return
        }
        if (mode == "root-check") {
            runRootEngineChecks()
            return
        }
        var success = false
        runBlocking {
            var controller: SimController? = null
            var router: EngineRouter? = null
            var originalEngine: String? = null
            var originalSettings: AppSettings? = null
            try {
                require(mode == "smoke" || mode == "lease") { "Unknown mode: $mode" }
                step = "application-ready"
                val application = targetContext.applicationContext as? MockGeoApp
                    ?: error("Target application is not MockGeoApp")
                withTimeout(20_000) { application.ready.await() }
                check(Process.myUid() == targetContext.applicationInfo.uid) {
                    "Wrong UID: process=${Process.myUid()}, target=${targetContext.applicationInfo.uid}"
                }
                check(targetContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED) { "Target app requires fine location permission" }
                val koin = GlobalContext.get()
                val activeController = koin.get<SimController>()
                val activeRouter = koin.get<EngineRouter>()
                controller = activeController
                router = activeRouter
                originalEngine = activeRouter.currentEngineId
                val savedSettings = koin.get<SettingsRepository>().settings.first()
                originalSettings = savedSettings
                expectedMock = !(compatibilityOverride ?: savedSettings.compatibilityMode)
                pass("application-ready", "uid=${Process.myUid()} originalEngine=$originalEngine")

                step = "select-xposed"
                withTimeout(10_000) { activeController.stop() }
                awaitState(activeController, "Idle before engine selection") { it == EngineState.Idle }
                switchWhenIdle(activeRouter, "xposed")
                activeController.updateSettings(savedSettings.copy(
                    engineId = "xposed", simIntervalMs = 200L,
                    jitterEnabled = false, jitterMeters = 0.0,
                    sineSpeedCurve = false, routeSpeedMps = 8.0,
                    networkProviderToo = false,
                    compatibilityMode = !expectedMock,
                ))
                val locationManager = targetContext.getSystemService(LocationManager::class.java)
                    ?: error("LocationManager is unavailable")
                val before = receipt()
                requiredModule?.let { check(before.version == it) { "Required module $it; loaded ${before.version}" } }
                pass("select-xposed", "revision=${before.revision}")

                step = "fixed-start"
                if (mode == "lease") {
                    // Exercise the same foreground-service lifetime as a real simulation.
                    // A bare controller in background instrumentation can be frozen by OEMs.
                    SimulationService.startFixed(targetContext, beijing)
                } else {
                    val start = withTimeout(10_000) { activeController.startFixed(beijing) }
                    check(start == SimulationSession.RequestOutcome.ACCEPTED) { "Fixed start outcome=$start" }
                }
                awaitState(activeController, "Running after fixed start") { it is EngineState.Running }
                val fixedReceipt = awaitReceipt(enabled = true, minRevision = before.revision + 1)
                check(fixedReceipt.hooksInstalled) { "Module reports hooksInstalled=false" }
                assertNear(LatLng(fixedReceipt.lat, fixedReceipt.lng), beijing, 1.0, "receipt position")
                assertLocation(gps(locationManager), beijing, 1.0, "fixed GPS")
                pass("fixed-start", "revision=${fixedReceipt.revision} hooks=true module=${fixedReceipt.version}")

                if (mode == "lease") {
                    emit("LEASE_READY", "LEASE_READY uid=${Process.myUid()} revision=${fixedReceipt.revision} " +
                        "lat=${beijing.lat} lng=${beijing.lng} holdSeconds=60")
                    results.putLong("leaseRevision", fixedReceipt.revision)
                    repeat(12) {
                        delay(5_000)
                        val live = receipt()
                        check(live.enabled && live.version == fixedReceipt.version &&
                            System.currentTimeMillis() - live.updatedAt in 0..10_000) { "Test lease lost freshness" }
                        emit("LEASE_HEARTBEAT", "revision=${live.revision} enabled=true")
                    }
                    pass("lease-window", "60-second window finished; cleaning up normally")
                } else {
                    step = "gps-listener"
                    val delivered = awaitGpsListener(locationManager)
                    assertLocation(delivered, beijing, 1.0, "GPS listener")
                    pass("gps-listener", "provider=${delivered.provider} mock=${isMock(delivered)}")

                    if (Build.VERSION.SDK_INT >= 30) {
                        for (provider in listOf("gps", "network", "fused")) {
                            step = "current-$provider"
                            val current = awaitCurrentFix(locationManager, provider)
                            assertLocation(current, beijing, 1.0, "$provider one-shot")
                            check(current.provider == provider) { "One-shot provider identity mismatch" }
                            pass(step, "single callback delivered with matching provider")
                        }
                    }

                    step = "route-start"
                    val endpoint = beijing.destination(0.0, 200.0)
                    val routeStart = withTimeout(10_000) {
                        activeController.startRoute(listOf(beijing, endpoint), RouteMode.ONCE, 8.0)
                    }
                    check(routeStart == SimulationSession.RequestOutcome.ACCEPTED) {
                        "Route start outcome=$routeStart"
                    }
                    awaitState(activeController, "Running after route start") { it is EngineState.Running }
                    val moved = awaitPosition(activeController, "route movement") { beijing.distanceTo(it) >= 5.0 }
                    val movedGps = gps(locationManager)
                    assertLocation(movedGps, moved, 10.0, "moving GPS")
                    check(movedGps.speed > 0f) { "Moving route reports zero speed" }
                    val routeReceipt = awaitReceipt(enabled = true, minRevision = fixedReceipt.revision + 1)
                    pass("route-movement", "distanceM=${beijing.distanceTo(moved)} " +
                        "speedMps=${movedGps.speed} revision=${routeReceipt.revision}")

                    step = "route-pause"
                    withTimeout(8_000) { activeController.pause() }
                    check(activeController.ui.value.paused) { "Pause flag was not set" }
                    val pausedGps = gps(locationManager)
                    check(pausedGps.speed == 0f) { "Paused GPS speed=${pausedGps.speed}" }
                    val pausedPosition = LatLng(pausedGps.latitude, pausedGps.longitude)
                    val pausedReceipt = awaitReceipt(enabled = true, minRevision = routeReceipt.revision + 1)
                    delay(1_200)
                    assertLocation(gps(locationManager), pausedPosition, 0.25, "paused GPS after waiting")
                    check(gps(locationManager).speed == 0f) { "Pause no longer reports zero speed" }
                    assertNear(activeController.ui.value.currentPosition ?: error("No paused UI position"),
                        pausedPosition, 0.25, "paused UI position")
                    pass("route-pause", "position held for 1200ms; speed=0; revision=${pausedReceipt.revision}")

                    step = "route-resume"
                    withTimeout(8_000) { activeController.resume() }
                    check(!activeController.ui.value.paused) { "Resume did not clear pause flag" }
                    val resumed = awaitPosition(activeController, "resumed movement") {
                        pausedPosition.distanceTo(it) >= 4.0
                    }
                    val resumedGps = gps(locationManager)
                    assertLocation(resumedGps, resumed, 10.0, "resumed GPS")
                    check(resumedGps.speed > 0f) { "Resumed GPS speed=${resumedGps.speed}" }
                    pass("route-resume", "distanceM=${pausedPosition.distanceTo(resumed)} speedMps=${resumedGps.speed}")

                    step = "stop-receipt"
                    val beforeStop = receipt()
                    withTimeout(10_000) { activeController.stop() }
                    awaitState(activeController, "Idle after stop") { it == EngineState.Idle }
                    val stopped = awaitReceipt(enabled = false, minRevision = beforeStop.revision + 1)
                    pass("stop-receipt", "enabled=false revision=${stopped.revision}")
                }
                success = true
            } catch (error: Throwable) {
                success = false
                results.putString("failureStep", step)
                results.putString("failure", error.toString())
                emit("FAIL", "$step: ${error.javaClass.simpleName}: ${error.message}")
                controller?.recentLogs()?.takeLast(10)?.forEach {
                    emit("LOG", "${it.tag} ${it.message}${it.error?.let { detail -> " | $detail" } ?: ""}")
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        controller?.let { activeController ->
                            try {
                                withTimeout(12_000) { activeController.stop() }
                                awaitState(activeController, "Idle during cleanup") { it == EngineState.Idle }
                            } finally {
                                originalSettings?.let { activeController.updateSettings(it) }
                            }
                        }
                        val restoreRouter = router
                        val restoreEngine = originalEngine
                        if (restoreRouter != null && restoreEngine != null) switchWhenIdle(restoreRouter, restoreEngine)
                        if (controller != null) {
                            val cleaned = receipt()
                            check(!cleaned.enabled) { "Cleanup receipt still enabled" }
                            pass("cleanup", "enabled=false original engine/settings restored")
                        }
                    } catch (cleanup: Throwable) {
                        success = false
                        results.putString("cleanupFailure", cleanup.toString())
                        emit("FAIL", "cleanup: ${cleanup.javaClass.simpleName}: ${cleanup.message}")
                    }
                }
            }
        }
        results.putInt("passedSteps", passed)
        results.putString("mode", mode)
        results.putString("stream", if (success) "PASS: $passed device smoke checks ($mode)\n"
            else "FAIL: device smoke checks ($mode), see failureStep/failure/cleanupFailure\n")
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    /** This path requires an independent Root receipt and never selects the xposed backend. */
    private fun runRootEngineChecks() {
        var success = false
        var engaged = false
        runBlocking {
            var controller: SimController? = null
            var router: EngineRouter? = null
            var saved: AppSettings? = null
            var oldEngine: String? = null
            try {
                step = "root-application-ready"
                val app = targetContext.applicationContext as MockGeoApp
                withTimeout(20_000) { app.ready.await() }
                val koin = GlobalContext.get()
                val activeRouter = koin.get<EngineRouter>().also { router = it }
                val active = koin.get<SimController>().also { controller = it }
                saved = koin.get<SettingsRepository>().settings.first()
                oldEngine = activeRouter.currentEngineId
                val checks = activeRouter.selfCheck("root")
                check(checks.all { it.ok }) { checks.filterNot { it.ok }.joinToString { it.message ?: it.name } }
                pass("root-environment", "independent Root prerequisites available")
                engaged = true
                withTimeout(10_000) { active.stop() }
                awaitState(active, "Idle before Root") { it == EngineState.Idle }
                switchWhenIdle(activeRouter, "root")
                active.updateSettings(saved!!.copy(engineId = "root", compatibilityMode = true,
                    simIntervalMs = 200, jitterEnabled = false, jitterMeters = 0.0))
                expectedMock = false
                step = "root-first-start"
                check(active.startFixed(beijing) == SimulationSession.RequestOutcome.ACCEPTED)
                awaitState(active, "Root Running", timeoutMs = 90_000) { it is EngineState.Running }
                val status = checkNotNull(activeRouter.moduleSnapshot())
                check(status.backend == "root" && status.processId > 1 && status.hooksInstalled &&
                    status.instanceNonce.matches(Regex("[0-9a-f]{32}"))) { "Missing independent system process identity" }
                pass("root-independent-receipt", "backend=root pid=${status.processId} version=${status.moduleVersion}")
                val lm = targetContext.getSystemService(LocationManager::class.java)!!
                assertLocation(gps(lm), beijing, 1.0, "root last GPS")
                pass("root-gps-last", "matching synthetic location")
                assertLocation(awaitGpsListener(lm), beijing, 1.0, "root GPS listener")
                pass("root-gps-listener", "matching synthetic callback")
                for (provider in listOf("gps", "network", "fused")) {
                    step = "root-current-$provider"
                    val fix = awaitCurrentFix(lm, provider)
                    assertLocation(fix, beijing, 1.0, step)
                    check(fix.provider == provider)
                    pass(step, "matching provider and location")
                }
                step = "root-stop"
                active.stop()
                awaitState(active, "Root Idle") { it == EngineState.Idle }
                check(activeRouter.moduleSnapshot()?.enabled == false)
                pass("root-stop", "system receipt confirms disabled")
                success = true
            } catch (failure: Throwable) {
                results.putString("failureStep", step)
                results.putString("failure", failure.toString())
                emit("FAIL", "$step: $failure")
            } finally {
                try {
                    if (engaged) {
                        controller?.stop()
                        saved?.let { controller?.updateSettings(it) }
                        if (router != null && oldEngine != null) switchWhenIdle(router!!, oldEngine!!)
                    }
                } catch (cleanup: Throwable) {
                    success = false
                    results.putString("cleanupFailure", cleanup.toString())
                }
            }
        }
        results.putInt("passedSteps", passed)
        results.putString("mode", mode)
        results.putString("stream", if (success) "PASS: $passed independent Root checks\n" else "FAIL: independent Root checks\n")
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private suspend fun switchWhenIdle(router: EngineRouter, engine: String) {
        withTimeout(3_000) {
            while (true) {
                val switched = router.switch(engine)
                if (switched.isSuccess) return@withTimeout
                delay(50)
            }
        }
    }

    private suspend fun awaitState(controller: SimController, description: String,
                                   timeoutMs: Long = 6_000,
                                   accepts: (EngineState) -> Boolean) {
        withTimeout(timeoutMs) {
            while (!accepts(controller.ui.value.engineState)) {
                val current = controller.ui.value.engineState
                check(current !is EngineState.Failed) { "$description; state=$current" }
                delay(50)
            }
        }
    }

    private suspend fun awaitPosition(controller: SimController, description: String,
                                      accepts: (LatLng) -> Boolean): LatLng = withTimeout(6_000) {
        while (true) {
            val state = controller.ui.value
            check(state.engineState is EngineState.Running) { "$description; state=${state.engineState}" }
            val position = state.currentPosition
            if (position != null && accepts(position)) return@withTimeout position
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun awaitGpsListener(manager: LocationManager): Location {
        val next = CompletableDeferred<Location>()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // A real pre-existing cached fix may arrive first. Accept only the
                // requested mock point, while letting the deadline expose a broken hook.
                if (isMock(location) == expectedMock &&
                    LatLng(location.latitude, location.longitude).distanceTo(beijing) <= 1.0) {
                    next.complete(Location(location))
                }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val started = SystemClock.elapsedRealtime()
        try {
            var registrationError: Throwable? = null
            runOnMainSync {
                try {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, listener,
                        Looper.getMainLooper())
                } catch (error: Throwable) { registrationError = error }
            }
            registrationError?.let { throw it }
            return withTimeout((6_000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1L)) {
                next.await()
            }
        } finally {
            var removalError: Throwable? = null
            runOnMainSync {
                try { manager.removeUpdates(listener) }
                catch (error: Throwable) { removalError = error }
            }
            removalError?.let { throw it }
        }
    }

    private fun gps(manager: LocationManager): Location =
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: error("GPS last location is null")

    @android.annotation.TargetApi(30)
    private suspend fun awaitCurrentFix(manager: LocationManager, provider: String): Location {
        val result = CompletableDeferred<Location?>()
        val cancel = android.os.CancellationSignal()
        try {
            runOnMainSync {
                manager.getCurrentLocation(provider, cancel, targetContext.mainExecutor) { result.complete(it) }
            }
            return withTimeout(6_000) { result.await() } ?: error("$provider one-shot returned null")
        } finally {
            cancel.cancel()
        }
    }

    private fun assertLocation(location: Location, expected: LatLng, toleranceM: Double, label: String) {
        check(isMock(location) == expectedMock) { "$label has an unexpected mock flag" }
        check(location.time > 0 && location.elapsedRealtimeNanos > 0 && location.hasAccuracy()) {
            "$label is incomplete"
        }
        assertNear(LatLng(location.latitude, location.longitude), expected, toleranceM, label)
    }

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider

    private fun assertNear(actual: LatLng, expected: LatLng, toleranceM: Double, label: String) {
        val error = actual.distanceTo(expected)
        check(error <= toleranceM) { "$label error=$error m exceeds $toleranceM m; actual=$actual expected=$expected" }
    }

    private data class Receipt(val enabled: Boolean, val revision: Long, val hooksInstalled: Boolean,
                               val lat: Double, val lng: Double, val updatedAt: Long, val version: String)

    private fun receipt(): Receipt {
        val cursor = targetContext.contentResolver.query(Uri.parse(ControlPlane.STATUS_URI),
            null, null, null, null) ?: error("Status provider returned no cursor")
        return cursor.use {
            check(it.moveToFirst()) { "Status provider returned no row for target UID" }
            Receipt(
                it.getInt(it.getColumnIndexOrThrow(ControlPlane.COL_ENABLED)) != 0,
                it.getLong(it.getColumnIndexOrThrow(ControlPlane.COL_REVISION)),
                it.getInt(it.getColumnIndexOrThrow(ControlPlane.COL_HOOKS)) != 0,
                it.getDouble(it.getColumnIndexOrThrow(ControlPlane.COL_LAT)),
                it.getDouble(it.getColumnIndexOrThrow(ControlPlane.COL_LNG)),
                it.getLong(it.getColumnIndexOrThrow(ControlPlane.COL_UPDATED_AT)),
                it.getString(it.getColumnIndexOrThrow(ControlPlane.COL_MODULE_VERSION)).orEmpty(),
            )
        }
    }

    private suspend fun awaitReceipt(enabled: Boolean, minRevision: Long): Receipt = withTimeout(6_000) {
        while (true) {
            val current = receipt()
            if (current.enabled == enabled && current.revision >= minRevision &&
                System.currentTimeMillis() - current.updatedAt in 0..ControlPlane.STATUS_FRESH_MS) {
                return@withTimeout current
            }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun pass(name: String, detail: String) {
        passed++
        emit("PASS", "$name: $detail")
    }

    private fun emit(kind: String, message: String) {
        sendStatus(0, Bundle().apply {
            putString("step", step)
            putString("kind", kind)
            putString("stream", "$kind $message\n")
        })
    }
}
