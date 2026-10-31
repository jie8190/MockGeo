package com.mockgeo.core.engine

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.InjectionEngine
import com.mockgeo.core.model.SimConfig
import com.mockgeo.core.model.SimError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态机测试（docs/08 §13：TTL 用虚拟时钟）。
 * 覆盖：快速连点幂等、启动 ack 超时 → Failed、停止超时回滚 Running、
 * 引擎异常 → Failed、resetToIdle fail-safe、乱序回执丢弃。
 */
class SimulationSessionTest {

    private val cfg = SimConfig(position = com.mockgeo.core.model.LatLng(39.9, 116.4))

    /** 可编排的假引擎：记录调用序列，可控制挂起与异常。 */
    private class FakeEngine : InjectionEngine {
        val calls = CopyOnWriteArrayList<String>()

        /** start 挂起控制：默认 null（立即成功）。 */
        var startGate: CompletableDeferred<Unit>? = null
        var startError: Throwable? = null
        var updateGate: CompletableDeferred<Unit>? = null
        var stopError: Throwable? = null
        var stopDelayMs = 0L
        var startupTimeoutMs: Long? = null

        private val _state = kotlinx.coroutines.flow.MutableStateFlow<EngineState>(EngineState.Idle)

        override suspend fun capability() = com.mockgeo.core.model.EngineCapability(
            engineId = "fake",
            displayName = "fake",
            requiresMockAppOp = false,
            requiresExternalModule = false,
            supportsNetworkProvider = true,
            supportsStreamingUpdate = true,
            startupTimeoutMs = startupTimeoutMs,
        )

        override suspend fun start(cfg: SimConfig): Result<Unit> {
            calls.add("start")
            startGate?.await()
            startError?.let { return Result.failure(it) }
            _state.value = EngineState.Running(0, cfg)
            return Result.success(Unit)
        }

        override suspend fun update(cfg: SimConfig): Result<Unit> {
            calls.add("update")
            updateGate?.await()
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<Unit> {
            calls.add("stop")
            if (stopDelayMs > 0) kotlinx.coroutines.delay(stopDelayMs)
            stopError?.let { return Result.failure(it) }
            _state.value = EngineState.Idle
            return Result.success(Unit)
        }

        override suspend fun selfCheck() = emptyList<com.mockgeo.core.model.CheckResult>()

        override fun state() = _state

        /** 模拟引擎看门狗失联：直接把引擎状态打成 Failed。 */
        fun failMidRun(reason: SimError, detail: String?) {
            _state.value = EngineState.Failed(reason, retryable = true, detail = detail)
        }
    }

    @Test
    fun `happy path start then stop`() = runTest {
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)

        val r1 = session.requestStart(cfg)
        assertThat(r1).isEqualTo(SimulationSession.RequestOutcome.ACCEPTED)
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)

        val r2 = session.requestStop()
        assertThat(r2).isEqualTo(SimulationSession.RequestOutcome.ACCEPTED)
        assertThat(session.state.value).isEqualTo(EngineState.Idle)
        assertThat(engine.calls).containsExactly("start", "stop").inOrder()
    }

    @Test
    fun `first start initialization can exceed ordinary ack timeout while remaining bounded`() = runTest {
        val engine = FakeEngine().apply {
            startGate = CompletableDeferred()
            startupTimeoutMs = 10_000
        }
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope, startTtlMs = 3000)
        val start = backgroundScope.async { session.requestStart(cfg) }
        runCurrent()
        advanceTimeBy(3500)
        runCurrent()
        assertThat(session.state.value).isInstanceOf(EngineState.Starting::class.java)
        engine.startGate!!.complete(Unit)
        assertThat(start.await()).isEqualTo(SimulationSession.RequestOutcome.ACCEPTED)
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)
        session.requestStop()
        engine.startGate = CompletableDeferred()
        val stalled = backgroundScope.async { session.requestStart(cfg) }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        stalled.await()
        assertThat(session.state.value).isInstanceOf(EngineState.Failed::class.java)
    }

    @Test
    fun `rapid double click returns IN_PROGRESS not silent`() = runTest {
        // T1 回归（docs/06 §2.4）：1 秒内点 5 次 → 第 1 次 ACCEPTED，其余 IN_PROGRESS
        val engine = FakeEngine()
        val gate = CompletableDeferred<Unit>()
        engine.startGate = gate
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)

        // 第一个事务在后台挂起（等 gate）
        val first = backgroundScope.async { session.requestStart(cfg) }
        runCurrent()
        // 第一个事务还在 Starting（gate 未开）——第二、三次点击立即返回 IN_PROGRESS
        assertThat(session.requestStart(cfg)).isEqualTo(SimulationSession.RequestOutcome.IN_PROGRESS)
        assertThat(session.requestStart(cfg)).isEqualTo(SimulationSession.RequestOutcome.IN_PROGRESS)
        gate.complete(Unit)
        runCurrent()
        assertThat(first.await()).isEqualTo(SimulationSession.RequestOutcome.ACCEPTED)
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)
    }

    @Test
    fun `start ack timeout falls to Failed and rolls back engine`() = runTest {
        // T2 回归：引擎不回 ack → 3s TTL 后 Failed(ACK_TIMEOUT)，引擎被回滚 stop。
        // requestStart 挂起到事务完成（虚拟时间自动推进 3s）才返回。
        val engine = FakeEngine()
        engine.startGate = CompletableDeferred() // 永不完成
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)

        session.requestStart(cfg)
        // 事务已结束：Failed(ACK_TIMEOUT)，引擎被回滚
        val s = session.state.value
        assertThat(s).isInstanceOf(EngineState.Failed::class.java)
        assertThat((s as EngineState.Failed).reason).isEqualTo(SimError.ACK_TIMEOUT)
        assertThat(engine.calls).contains("stop")
    }

    @Test
    fun `engine start exception surfaces as Failed`() = runTest {
        val engine = FakeEngine()
        engine.startError = SecurityException("mock appop denied")
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)

        session.requestStart(cfg)
        val s = session.state.value
        assertThat(s).isInstanceOf(EngineState.Failed::class.java)
        assertThat((s as EngineState.Failed).reason).isEqualTo(SimError.MOCK_APPOP_DENIED)
        assertThat(s.retryable).isTrue()
    }

    @Test
    fun `stop failure rolls back to Running instead of lying`() = runTest {
        // T3 回归（docs/06 §3.4）：停止失败必须回滚 Running（宁可显示仍在模拟）
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)

        engine.stopError = IllegalStateException("module unreachable")
        session.requestStop()
        val s = session.state.value
        assertThat(s).isInstanceOf(EngineState.Running::class.java)
    }

    @Test
    fun `stop timeout watchdog rolls back to Running`() = runTest {
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)

        engine.stopDelayMs = 10_000 // 超过 5s TTL
        // requestStop 挂起：先观察到 Stopping，5s TTL 后回滚 Running
        val stop = backgroundScope.async { session.requestStop() }
        runCurrent()
        assertThat(session.state.value).isInstanceOf(EngineState.Stopping::class.java)

        advanceTimeBy(5_000 + SimulationSession.WATCHDOG_GRACE_MS + 100)
        runCurrent()

        // 看门狗回滚 Running（宁可显示仍在模拟，不骗用户）
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)
        stop.cancel() // 事务协程已被超时取消，收尾
    }

    @Test
    fun `stop while idle is idempotent success`() = runTest {
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        val r = session.requestStop()
        assertThat(r).isEqualTo(SimulationSession.RequestOutcome.ACCEPTED)
        assertThat(engine.calls).isEmpty()
    }

    @Test
    fun `invalid config cannot be constructed - session validation is defense in depth`() = runTest {
        // SimConfig 在构造期即校验（见 SimConfigTest），非法配置无法进入会话；
        // 会话层的 validate() 是纵深防御（未来字段若绕过 init 校验仍会被拦）。
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        val bad = runCatching { cfg.copy(accuracyM = 0f) }.isFailure
        assertThat(bad).isTrue() // 构造即抛
        // 合法配置正常受理
        assertThat(session.requestStart(cfg)).isEqualTo(SimulationSession.RequestOutcome.ACCEPTED)
        assertThat(engine.calls).containsExactly("start")
    }

    @Test
    fun `resetToIdle stops engine and clears`() = runTest {
        // T5 回归：杀进程重启后不得仍显示运行中
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)
        session.resetToIdle()
        assertThat(session.state.value).isEqualTo(EngineState.Idle)
        assertThat(engine.calls).contains("stop")
    }

    @Test
    fun `tick only updates while Running`() = runTest {
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.tick(cfg) // Idle：不应触达引擎
        assertThat(engine.calls).isEmpty()
        session.requestStart(cfg)
        session.tick(cfg)
        assertThat(engine.calls).contains("update")
    }

    @Test
    fun `stale watchdog does not overwrite newer state`() = runTest {
        // 乱序回执丢弃：启动超时的看门狗不得覆盖之后的新状态
        val engine = FakeEngine()
        engine.startGate = CompletableDeferred() // 第一次 start 永远挂起
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)

        advanceTimeBy(3_500 + SimulationSession.WATCHDOG_GRACE_MS)
        runCurrent()
        assertThat(session.state.value).isInstanceOf(EngineState.Failed::class.java)

        // 新一轮 start（引擎这次正常）
        engine.startGate = null
        val r = session.requestStart(cfg)
        runCurrent()
        assertThat(r).isEqualTo(SimulationSession.RequestOutcome.ACCEPTED)
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)

        // 旧看门狗的迟到动作不得把 Running 打回 Failed
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)
    }

    @Test
    fun `engine failure mid-run propagates to session`() = runTest {
        // 传播桥：引擎看门狗发现的失联必须让会话可见（UI 不许假装 Running）
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)
        assertThat(session.state.value).isInstanceOf(EngineState.Running::class.java)

        // 模拟引擎侧失联（XposedEngine 看门狗的行为）
        engine.failMidRun(SimError.MODULE_UNREACHABLE, "module status stale")
        runCurrent()

        val s = session.state.value
        assertThat(s).isInstanceOf(EngineState.Failed::class.java)
        assertThat((s as EngineState.Failed).reason).isEqualTo(SimError.MODULE_UNREACHABLE)
    }

    @Test
    fun `failed state stop failure is never reported as Idle`() = runTest {
        val engine = FakeEngine().apply { startError = IllegalStateException("failed start") }
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)
        engine.stopError = IllegalStateException("cleanup failed")
        session.requestStop()
        assertThat(session.state.value).isInstanceOf(EngineState.Failed::class.java)
        assertThat(session.lastError).contains("cleanup failed")
    }

    @Test
    fun `startup cleanup failure remains visible`() = runTest {
        val engine = FakeEngine().apply { stopError = IllegalStateException("still enabled") }
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.resetToIdle()
        assertThat(session.state.value).isInstanceOf(EngineState.Failed::class.java)
    }

    @Test
    fun `stop waits for in flight frame and blocks later frames`() = runTest {
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)
        engine.updateGate = CompletableDeferred()
        val frame = backgroundScope.async { session.tick(cfg) }
        runCurrent()
        val stop = backgroundScope.async { session.requestStop() }
        runCurrent()
        assertThat(engine.calls).containsExactly("start", "update").inOrder()
        engine.updateGate!!.complete(Unit)
        frame.await()
        stop.await()
        session.tick(cfg)
        assertThat(engine.calls).containsExactly("start", "update", "stop").inOrder()
        assertThat(session.state.value).isEqualTo(EngineState.Idle)
    }

    @Test
    fun `failed stop retries while forbidding enabled frames`() = runTest {
        val engine = FakeEngine()
        val session = SimulationSession(engine, TestClock(), RingSimLogger(), backgroundScope)
        session.requestStart(cfg)
        engine.stopError = IllegalStateException("temporary outage")
        session.requestStop()
        session.tick(cfg)
        assertThat(engine.calls).doesNotContain("update")
        engine.stopError = null
        advanceTimeBy(1001)
        runCurrent()
        assertThat(session.state.value).isEqualTo(EngineState.Idle)
    }

    /** 虚拟时钟（runTest 的虚拟时间由 TestScope 管理，这里只提供读数）。 */
    private class TestClock : Clock {
        override fun currentTimeMillis(): Long = 0L
        override fun elapsedRealtime(): Long = 0L
        override fun elapsedRealtimeNanos(): Long = 0L
    }
}
