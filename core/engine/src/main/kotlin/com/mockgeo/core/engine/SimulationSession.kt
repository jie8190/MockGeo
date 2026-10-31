package com.mockgeo.core.engine

import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.InjectionEngine
import com.mockgeo.core.model.SimConfig
import com.mockgeo.core.model.SimError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Acknowledged session state. No successful stop is published until cleanup succeeds. */
class SimulationSession(
    private val engine: InjectionEngine,
    private val clock: Clock,
    private val log: SimLogger,
    private val scope: CoroutineScope,
    private val startTtlMs: Long = 3_000L,
    private val stopTtlMs: Long = 5_000L,
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state
    private val requestSeq = java.util.concurrent.atomic.AtomicLong()
    private val txnMutex = Mutex()
    // A tick already in flight must finish before the disable command is sent.
    private val engineMutex = Mutex()
    @Volatile private var stopRequested = false
    private var retryJob: Job? = null
    private var latestConfig: SimConfig? = null

    @Volatile var lastError: String? = null
        private set

    init {
        scope.launch {
            engine.state().collect { es ->
                if (es is EngineState.Failed && _state.value is EngineState.Running && !stopRequested) {
                    lastError = es.detail
                    _state.value = es
                    log.log(0, SimLogger.ERROR, TAG, "engine failed: ${es.reason}")
                }
            }
        }
    }

    suspend fun requestStart(cfg: SimConfig): RequestOutcome {
        if (!txnMutex.tryLock()) return RequestOutcome.IN_PROGRESS
        try {
            if (_state.value is EngineState.Running || _state.value is EngineState.Starting ||
                _state.value is EngineState.Stopping) return RequestOutcome.IN_PROGRESS
            val req = requestSeq.incrementAndGet()
            retryJob?.cancel()
            stopRequested = false
            latestConfig = cfg
            lastError = null
            _state.value = EngineState.Starting(req)
            log.log(req, SimLogger.INFO, TAG, "start requested")
            try {
                val timeout = engine.capability().startupTimeoutMs?.coerceIn(1, 90_000) ?: startTtlMs
                val result = bounded(timeout) { engine.start(cfg) }
                if (result?.isSuccess == true) {
                    _state.value = EngineState.Running(clock.elapsedRealtime(), cfg)
                } else {
                    fail(result?.exceptionOrNull(), "start ack timeout")
                    stopRequested = true
                    val cleanup = bounded(stopTtlMs) { engine.stop() }
                    if (cleanup?.isSuccess != true) scheduleStopRetry()
                }
            } catch (cancel: CancellationException) {
                stopRequested = true
                withContext(NonCancellable) { bounded(stopTtlMs) { engine.stop() } }
                fail(cancel, "start cancelled")
                throw cancel
            }
            return RequestOutcome.ACCEPTED
        } finally {
            txnMutex.unlock()
        }
    }

    suspend fun requestStop(): RequestOutcome {
        // Record intent even while a startup transaction is completing.
        stopRequested = true
        if (!txnMutex.tryLock()) {
            scheduleStopRetry()
            return RequestOutcome.IN_PROGRESS
        }
        try {
            if (_state.value == EngineState.Idle) return RequestOutcome.ACCEPTED
            val req = requestSeq.incrementAndGet()
            val previous = _state.value
            val cfg = (previous as? EngineState.Running)?.cfg ?: latestConfig
            if (cfg != null) _state.value = EngineState.Stopping(req, cfg)
            log.log(req, SimLogger.INFO, TAG, "stop requested")
            val result = try {
                bounded(stopTtlMs) { engine.stop() }
            } catch (cancel: CancellationException) {
                lastError = "stop interrupted; confirmation pending"
                _state.value = previous
                scheduleStopRetry()
                throw cancel
            }
            if (result?.isSuccess == true) {
                lastError = null
                _state.value = EngineState.Idle
                log.log(req, SimLogger.INFO, TAG, "stop acknowledged")
            } else {
                lastError = result?.exceptionOrNull()?.message ?: "stop ack timeout"
                _state.value = if (previous is EngineState.Running && cfg != null) {
                    previous.copy(cfg = cfg)
                } else EngineState.Failed(result?.exceptionOrNull().toSimError(), true,
                    "停止未确认：$lastError")
                log.log(req, SimLogger.ERROR, TAG, "stop unconfirmed: $lastError")
                scheduleStopRetry()
            }
            return RequestOutcome.ACCEPTED
        } finally {
            txnMutex.unlock()
        }
    }

    suspend fun tick(cfg: SimConfig) {
        if (stopRequested || _state.value !is EngineState.Running) return
        engineMutex.withLock {
            if (stopRequested || _state.value !is EngineState.Running) return
            val result = try { engine.update(cfg) } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) { Result.failure(error) }
            if (result.isSuccess) {
                latestConfig = cfg
                val running = _state.value as? EngineState.Running
                if (running != null && !stopRequested) _state.value = running.copy(cfg = cfg)
            } else {
                fail(result.exceptionOrNull(), "position update failed")
            }
        }
    }

    /** Called at process startup; cleanup failures remain visible. */
    suspend fun resetToIdle() {
        txnMutex.withLock {
            stopRequested = true
            val result = bounded(stopTtlMs) { engine.stop() }
            if (result?.isSuccess == true) _state.value = EngineState.Idle
            else {
                fail(result?.exceptionOrNull(), "startup cleanup timeout")
                scheduleStopRetry()
            }
        }
    }

    private suspend fun bounded(timeout: Long, operation: suspend () -> Result<Unit>): Result<Unit>? {
        // Android Binder calls are synchronous and not cooperatively cancellable.
        // Await an application-scope worker so the session TTL can still expire.
        val worker = scope.async {
            engineMutex.withLock {
                try { operation() } catch (cancel: CancellationException) { throw cancel }
                catch (error: Throwable) { Result.failure(error) }
            }
        }
        try {
            val result = withTimeoutOrNull(timeout) { worker.await() }
            if (result == null) {
                worker.cancel()
                // If a blocked Binder call returns late, close it before any queued
                // new start. This cleanup never changes acknowledged UI state.
                scope.launch {
                    engineMutex.withLock {
                        if (stopRequested) runCatching { engine.stop() }
                    }
                }
            }
            return result
        } finally {
            worker.cancel()
        }
    }

    private fun scheduleStopRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            repeat(3) {
                delay(1_000L)
                if (!stopRequested || _state.value == EngineState.Idle) return@launch
                requestStop()
            }
        }
    }

    private fun fail(error: Throwable?, fallback: String) {
        lastError = error?.message ?: fallback
        _state.value = EngineState.Failed(error.toSimError(), true, lastError)
        log.log(requestSeq.get(), SimLogger.ERROR, TAG, lastError ?: fallback, error)
    }

    private fun Throwable?.toSimError(): SimError = when (this) {
        null -> SimError.ACK_TIMEOUT
        is SecurityException -> SimError.MOCK_APPOP_DENIED
        is IllegalArgumentException -> SimError.INVALID_CONFIG
        else -> SimError.ENGINE_CRASH
    }

    enum class RequestOutcome { ACCEPTED, IN_PROGRESS, REJECTED }
    companion object {
        private const val TAG = "SimSession"
        const val WATCHDOG_GRACE_MS = 500L
    }
}
