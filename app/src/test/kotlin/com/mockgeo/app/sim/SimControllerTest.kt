package com.mockgeo.app.sim

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.data.prefs.AppSettings
import com.mockgeo.core.engine.*
import com.mockgeo.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimControllerTest {
    private class Sink : InjectionEngine {
        val snapshots = mutableListOf<SimConfig>()
        val events = mutableListOf<String>()
        val state = MutableStateFlow<EngineState>(EngineState.Idle)
        var stopFails = false
        override suspend fun capability() = EngineCapability("test", "Test", false, false, true, true)
        override suspend fun selfCheck() = emptyList<CheckResult>()
        override fun state() = state
        override suspend fun start(cfg: SimConfig): Result<Unit> {
            snapshots += cfg; events += "start"; state.value = EngineState.Running(0, cfg)
            return Result.success(Unit)
        }
        override suspend fun update(cfg: SimConfig): Result<Unit> {
            snapshots += cfg; events += "update"; state.value = EngineState.Running(0, cfg)
            return Result.success(Unit)
        }
        override suspend fun stop(): Result<Unit> {
            events += "stop"
            if (stopFails) return Result.failure(IllegalStateException("stop failed"))
            state.value = EngineState.Idle
            return Result.success(Unit)
        }
    }
    private fun TestScope.controller(sink: Sink): SimController {
        val clock = object : Clock {
            override fun currentTimeMillis() = testScheduler.currentTime
            override fun elapsedRealtime() = testScheduler.currentTime
            override fun elapsedRealtimeNanos() = testScheduler.currentTime * 1_000_000
        }
        val log = RingSimLogger()
        return SimController(SimulationSession(sink, clock, log, backgroundScope), clock, log, backgroundScope)
    }
    private val a = LatLng(39.9, 116.4)
    private val b = a.destination(90.0, 1000.0)

    @Test fun `default smooth route moves and pause freezes without catch up`() = runTest {
        val sink = Sink(); val controller = controller(sink)
        controller.startRoute(listOf(a, b), RouteMode.ONCE, 5.0)
        runCurrent(); advanceTimeBy(2000); runCurrent()
        assertThat(a.distanceTo(controller.ui.value.currentPosition!!)).isGreaterThan(0.1)
        controller.pause(); runCurrent()
        val frozen = controller.ui.value.currentPosition!!
        val progress = controller.ui.value.routeProgress
        advanceTimeBy(30_000); runCurrent()
        assertThat(controller.ui.value.currentPosition).isEqualTo(frozen)
        assertThat(controller.ui.value.currentSpeedMps).isEqualTo(0.0)
        assertThat(controller.ui.value.routeProgress).isEqualTo(progress)
        controller.resume(); runCurrent(); advanceTimeBy(1000); runCurrent()
        val moved = frozen.distanceTo(controller.ui.value.currentPosition!!)
        assertThat(moved).isGreaterThan(0.0)
        assertThat(moved).isLessThan(6.0)
    }

    @Test fun `stop never emits another update`() = runTest {
        val sink = Sink(); val controller = controller(sink)
        controller.startRoute(listOf(a, b), RouteMode.LOOP, 5.0)
        runCurrent(); advanceTimeBy(1500); runCurrent()
        controller.stop(); runCurrent()
        val count = sink.snapshots.size
        advanceTimeBy(10_000); runCurrent()
        assertThat(sink.snapshots.size).isEqualTo(count)
        assertThat(sink.events.last()).isEqualTo("stop")
        assertThat(controller.ui.value.engineState).isEqualTo(EngineState.Idle)
    }

    @Test fun `mode replacement stops old engine before publishing new coordinates`() = runTest {
        val sink = Sink(); val controller = controller(sink)
        controller.startFixed(a); runCurrent()
        controller.startJoystick(b, 3.0); runCurrent()
        assertThat(sink.events).containsExactly("start", "stop", "start").inOrder()
        assertThat(controller.ui.value.mode).isEqualTo(SimController.Mode.JOYSTICK)
        assertThat(sink.snapshots.last().position).isEqualTo(b)
    }

    @Test fun `failed cleanup preserves old mode and does not start new engine`() = runTest {
        val sink = Sink(); val controller = controller(sink)
        controller.startFixed(a); runCurrent()
        sink.stopFails = true
        controller.startJoystick(b, 3.0); runCurrent()
        assertThat(sink.events.count { it == "start" }).isEqualTo(1)
        assertThat(controller.ui.value.mode).isEqualTo(SimController.Mode.FIXED)
        assertThat(controller.ui.value.engineState).isInstanceOf(EngineState.Running::class.java)
    }

    @Test fun `route speed changes affect the active route`() = runTest {
        val sink = Sink(); val controller = controller(sink)
        controller.updateSettings(AppSettings(sineSpeedCurve = false, routeSpeedMps = 1.0))
        controller.startRoute(listOf(a, b), RouteMode.ONCE, 1.0)
        runCurrent(); advanceTimeBy(1000); runCurrent()
        val first = controller.ui.value.currentPosition!!
        controller.updateSettings(AppSettings(sineSpeedCurve = false, routeSpeedMps = 10.0))
        advanceTimeBy(1000); runCurrent()
        assertThat(first.distanceTo(controller.ui.value.currentPosition!!)).isWithin(0.1).of(10.0)
    }

    @Test fun `GPX altitude and segment speed survive playback and pause`() = runTest {
        val sink = Sink(); val controller = controller(sink)
        controller.updateSettings(AppSettings(sineSpeedCurve = false))
        controller.startRoute(listOf(a, b), RouteMode.ONCE, 1.4, listOf(
            RoutePoint(seq = 0, lat = a.lat, lng = a.lng, altitude = 100.0),
            RoutePoint(seq = 1, lat = b.lat, lng = b.lng, altitude = 200.0, speedMps = 10.0),
        ))
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertThat(sink.snapshots.last().speedMps).isEqualTo(10f)
        assertThat(sink.snapshots.last().altitude).isWithin(0.1).of(101.0)
        controller.pause(); runCurrent()
        assertThat(sink.snapshots.last().altitude).isWithin(0.1).of(101.0)
        assertThat(sink.snapshots.last().speedMps).isEqualTo(0f)
    }
}
