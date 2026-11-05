package com.mockgeo.app.sim

import com.mockgeo.core.data.prefs.AppSettings
import com.mockgeo.core.engine.Clock
import com.mockgeo.core.engine.SimLogger
import com.mockgeo.core.engine.SimulationSession
import com.mockgeo.core.model.*
import com.mockgeo.core.route.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns movement and serializes mode changes. Pausing keeps a stationary mock fix alive. */
class SimController(
    private val session: SimulationSession,
    private val clock: Clock,
    private val log: SimLogger,
    private val scope: CoroutineScope,
) {
    enum class Mode { FIXED, ROUTE, JOYSTICK }
    data class SimUiState(
        val engineState: EngineState = EngineState.Idle,
        val mode: Mode = Mode.FIXED,
        val currentPosition: LatLng? = null,
        val routeProgress: Double = 0.0,
        val currentSpeedMps: Double = 0.0,
        val joystickActive: Boolean = false,
        val paused: Boolean = false,
        val routeDistanceM: Double = 0.0,
        val routePoints: List<LatLng> = emptyList(),
        val routeMode: RouteMode = RouteMode.ONCE,
        val completedLaps: Long = 0,
    )
    private val _ui = MutableStateFlow(SimUiState())
    val ui: StateFlow<SimUiState> = _ui
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices
    private val commands = Mutex()
    @Volatile private var settings = AppSettings()
    @Volatile var joystick = JoystickVector(0f, 0f)
        private set
    private var routeSim: RouteSimulator? = null
    private var routeDetails: List<RoutePoint> = emptyList()
    private var routeDistances = doubleArrayOf()
    private var fixedPosition: LatLng? = null
    private var joystickMover: JoystickMover? = null
    private var joystickSpeed = 0.0
    private var jitterWalker: JitterWalker? = null
    private var jitterRadius = -1.0
    private var tickJob: Job? = null

    init {
        scope.launch {
            session.state.collect { state ->
                _ui.update { it.copy(engineState = state,
                    currentSpeedMps = if (state == EngineState.Idle) 0.0 else it.currentSpeedMps,
                    paused = if (state == EngineState.Idle) false else it.paused,
                    joystickActive = if (state == EngineState.Idle) false else it.joystickActive) }
            }
        }
    }

    fun updateSettings(value: AppSettings) { settings = value }
    fun reportError(message: String) {
        log.log(0, SimLogger.ERROR, "SimController", message)
        _notices.tryEmit(message)
    }
    fun setJoystick(vector: JoystickVector) {
        joystick = vector
        _ui.update { it.copy(joystickActive = !vector.isIdle && !it.paused) }
    }

    private suspend fun prepareMode(): Boolean {
        stopTick()
        val state = session.state.value
        if (state is EngineState.Starting || state is EngineState.Stopping) return false
        if (state != EngineState.Idle) session.requestStop()
        if (session.state.value != EngineState.Idle) {
            reportError("上一次模拟尚未停止，请重试停止后再切换模式")
            return false
        }
        joystick = JoystickVector(0f, 0f)
        return true
    }

    suspend fun startFixed(pos: LatLng) = commands.withLock {
        if (!prepareMode()) return@withLock SimulationSession.RequestOutcome.REJECTED
        fixedPosition = pos
        routeSim = null
        joystickMover = null
        _ui.update { it.copy(mode = Mode.FIXED, currentPosition = pos, routeProgress = 0.0,
            routeDistanceM = 0.0, routePoints = emptyList(), routeMode = RouteMode.ONCE, completedLaps = 0,
            paused = false, currentSpeedMps = 0.0) }
        start(buildConfig(pos, 0.0, 0f))
    }

    suspend fun startRoute(points: List<LatLng>, mode: RouteMode, speedMps: Double,
                           details: List<RoutePoint> = emptyList()) = commands.withLock {
        require(points.size >= 2) { "路线至少需要两个点" }
        val total = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        require(total > 0.01) { "路线至少需要两个不同的位置" }
        if (!prepareMode()) return@withLock SimulationSession.RequestOutcome.REJECTED
        settings = settings.copy(routeSpeedMps = speedMps)
        routeDetails = details.takeIf { it.size == points.size } ?: emptyList()
        routeDistances = DoubleArray(points.size)
        for (i in 1 until points.size) routeDistances[i] = routeDistances[i - 1] + points[i - 1].distanceTo(points[i])
        routeSim = RouteSimulator(points, { progress ->
            val index = segmentIndex(progress)
            val recorded = routeDetails.getOrNull(index + 1)?.speedMps
            val speed = recorded?.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(100.0) ?: settings.routeSpeedMps
            if (settings.sineSpeedCurve) SpeedCurves.sine(speed, (total * 0.15).coerceIn(5.0, 100.0), total)(progress)
            else speed
        }, mode)
        fixedPosition = null
        joystickMover = null
        _ui.update { it.copy(mode = Mode.ROUTE, currentPosition = points.first(), routeProgress = 0.0,
            routeDistanceM = total, routePoints = points, routeMode = mode, completedLaps = 0,
            paused = false, currentSpeedMps = 0.0) }
        start(buildConfig(points.first(), 0.0, 0f).copy(altitude = routeDetails.firstOrNull()?.altitude ?: settings.altitudeM))
    }

    suspend fun startJoystick(origin: LatLng, speedMps: Double) = commands.withLock {
        if (!prepareMode()) return@withLock SimulationSession.RequestOutcome.REJECTED
        settings = settings.copy(routeSpeedMps = speedMps)
        joystickSpeed = speedMps
        joystickMover = JoystickMover(speedMps)
        routeSim = null
        fixedPosition = null
        _ui.update { it.copy(mode = Mode.JOYSTICK, currentPosition = origin, paused = false,
            routeProgress = 0.0, routePoints = emptyList(), routeMode = RouteMode.ONCE, completedLaps = 0,
            routeDistanceM = 0.0, currentSpeedMps = 0.0) }
        start(buildConfig(origin, 0.0, 0f))
    }

    private suspend fun start(cfg: SimConfig): SimulationSession.RequestOutcome {
        val result = session.requestStart(cfg)
        _ui.update { it.copy(engineState = session.state.value) }
        if (session.state.value is EngineState.Running) onRunning()
        return result
    }

    suspend fun pause() = commands.withLock {
        if (session.state.value !is EngineState.Running) return@withLock
        stopTick()
        _ui.update { it.copy(paused = true, currentSpeedMps = 0.0, joystickActive = false) }
        joystick = JoystickVector(0f, 0f)
        tickOnce(0.0)
        onRunning()
    }

    suspend fun resume() = commands.withLock {
        if (session.state.value !is EngineState.Running) return@withLock
        stopTick()
        _ui.update { it.copy(paused = false) }
        onRunning()
    }

    suspend fun stop() = commands.withLock {
        stopTick()
        joystick = JoystickVector(0f, 0f)
        _ui.update { it.copy(currentSpeedMps = 0.0, joystickActive = false) }
        var result = session.requestStop()
        repeat(35) {
            if (result == SimulationSession.RequestOutcome.IN_PROGRESS) {
                delay(100)
                result = session.requestStop()
            }
        }
        _ui.update { it.copy(engineState = session.state.value) }
        if (session.state.value is EngineState.Running) reportError("停止尚未确认，正在重试；请留意模拟状态")
        result
    }

    @Synchronized fun onRunning() {
        if (tickJob?.isActive == true || session.state.value !is EngineState.Running) return
        tickJob = scope.launch {
            var last = clock.elapsedRealtime()
            while (isActive && session.state.value is EngineState.Running) {
                delay(settings.simIntervalMs)
                val now = clock.elapsedRealtime()
                val dt = (now - last) / 1000.0
                last = now
                if (dt > 0 && session.state.value is EngineState.Running) {
                    try { tickOnce(dt) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        reportError("位置更新失败：${error.message}")
                        session.requestStop()
                        break
                    }
                }
            }
        }
    }

    private suspend fun stopTick() {
        val job = tickJob
        tickJob = null
        job?.cancelAndJoin()
    }

    private suspend fun tickOnce(dt: Double) {
        val state = session.state.value as? EngineState.Running ?: return
        val ui = _ui.value
        val cfg = if (ui.paused) {
            buildConfig(ui.currentPosition ?: state.cfg.position, 0.0, state.cfg.bearingDeg).copy(
                altitude = if (ui.mode == Mode.ROUTE) routeAltitude(routeSim?.progress ?: 0.0) else settings.altitudeM)
        } else when (ui.mode) {
            Mode.FIXED -> buildConfig(jitter(fixedPosition ?: return, dt), 0.0, state.cfg.bearingDeg)
            Mode.ROUTE -> {
                val route = routeSim ?: return
                val pos = route.advance(dt)
                _ui.update { it.copy(routeProgress = (route.progress / route.totalDistance).coerceIn(0.0, 1.0), completedLaps = route.completedLaps) }
                buildConfig(jitter(pos, dt), route.currentSpeed(), route.bearing()).copy(altitude = routeAltitude(route.progress))
            }
            Mode.JOYSTICK -> {
                if (joystickSpeed != settings.routeSpeedMps) {
                    joystickSpeed = settings.routeSpeedMps
                    joystickMover = JoystickMover(joystickSpeed)
                }
                val mover = joystickMover ?: return
                val pos = mover.advance(ui.currentPosition ?: return, joystick, dt)
                buildConfig(pos, mover.currentSpeed(), mover.currentBearing())
            }
        }
        session.tick(cfg)
        if (session.state.value is EngineState.Running) {
            _ui.update { it.copy(currentPosition = cfg.position, currentSpeedMps = cfg.speedMps.toDouble()) }
        }
    }

    private fun segmentIndex(progress: Double): Int {
        val found = routeDistances.binarySearch(progress)
        return (if (found >= 0) found else -found - 2).coerceIn(0, (routeDistances.size - 2).coerceAtLeast(0))
    }

    private fun routeAltitude(progress: Double): Double {
        val i = segmentIndex(progress)
        val a = routeDetails.getOrNull(i)?.altitude ?: return settings.altitudeM
        val b = routeDetails.getOrNull(i + 1)?.altitude ?: a
        val length = routeDistances[i + 1] - routeDistances[i]
        val t = if (length > 0) ((progress - routeDistances[i]) / length).coerceIn(0.0, 1.0) else 0.0
        return a + (b - a) * t
    }

    private fun jitter(base: LatLng, dt: Double): LatLng {
        val radius = if (settings.jitterEnabled) settings.jitterMeters else 0.0
        if (radius != jitterRadius) { jitterRadius = radius; jitterWalker = JitterWalker(radius) }
        return jitterWalker?.let { it.apply(base, it.step(dt)) } ?: base
    }

    private fun buildConfig(pos: LatLng, speed: Double, bearing: Float) = SimConfig(
        position = pos, altitude = settings.altitudeM, speedMps = speed.toFloat(), bearingDeg = bearing,
        accuracyM = settings.gpsAccuracyM.toFloat(), satellites = settings.satellites,
        jitterEnabled = settings.jitterEnabled, jitterMeters = settings.jitterMeters,
        networkProviderToo = settings.networkProviderToo,
        reportMock = !settings.compatibilityMode,
        stepCadenceSpm = settings.stepCadenceSpm,
    )
    fun recentLogs(): List<SimLogger.LogLine> = log.recent()
}
