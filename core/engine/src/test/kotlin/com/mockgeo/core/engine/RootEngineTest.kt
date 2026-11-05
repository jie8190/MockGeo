package com.mockgeo.core.engine

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RootEngineTest {
    private val cfg = SimConfig(position = LatLng(39.9, 116.4))
    private val nonce = "a".repeat(32)
    private fun receipt() = XposedEngine.ModuleStatus(false, 1, 39.9, 116.4, true, 10, 10,
        "0.1.0", backend = "root", processId = 123, instanceNonce = nonce)
    private class Channel(var reachable: Boolean = false, var status: XposedEngine.ModuleStatus? = null) : SystemHookControl {
        val state = MutableStateFlow<EngineState>(EngineState.Idle)
        var starts = 0; var stops = 0
        var loseStartReceipt = false
        override suspend fun pingModule() = reachable
        override fun statusSnapshot() = status
        override fun state() = state
        override suspend fun capability() = EngineCapability("test", "Test", false, false, true, true)
        override suspend fun selfCheck() = emptyList<CheckResult>()
        override suspend fun start(cfg: SimConfig): Result<Unit> {
            starts++
            if (loseStartReceipt) { reachable = false; return Result.failure(IllegalStateException("receipt lost")) }
            state.value = EngineState.Running(1, cfg)
            return Result.success(Unit)
        }
        override suspend fun update(cfg: SimConfig) = Result.success(Unit)
        override suspend fun stop(): Result<Unit> { stops++; state.value = EngineState.Idle; return Result.success(Unit) }
    }
    private inner class Environment(private val channel: Channel) : RootEnvironment {
        override val version = "0.1.0"
        var loads = 0
        override suspend fun readiness() = listOf(CheckResult("Root 权限", true))
        override suspend fun load(): RootReceipt {
            loads++; channel.reachable = true
            return RootReceipt(123, nonce, version)
        }
    }
    @Test fun `first start loads bundled root backend then requires matching independent receipt`() = runTest {
        val control = Channel(status = receipt())
        val env = Environment(control)
        val engine = RootEngine(env, control, Channel(), backgroundScope)
        assertThat(engine.selfCheck().all { it.ok }).isTrue()
        assertThat(env.loads).isEqualTo(0)
        assertThat(engine.start(cfg).isSuccess).isTrue()
        assertThat(env.loads).isEqualTo(1)
        assertThat(control.starts).isEqualTo(1)
        assertThat(engine.state().value).isInstanceOf(EngineState.Running::class.java)
        assertThat(engine.update(cfg).isSuccess).isTrue()
        assertThat(engine.stop().isSuccess).isTrue()
        assertThat(control.stops).isEqualTo(1)
        assertThat(engine.start(cfg).isSuccess).isTrue()
        assertThat(env.loads).isEqualTo(1)
    }
    @Test fun `live LSPosed backend blocks root deployment instead of masquerading as root`() = runTest {
        val control = Channel(status = receipt())
        val env = Environment(control)
        val engine = RootEngine(env, control, Channel(reachable = true), backgroundScope)
        assertThat(engine.selfCheck().single().ok).isFalse()
        assertThat(engine.selfCheck().single().actionKey).isEqualTo("USE_XPOSED")
        assertThat(engine.start(cfg).isFailure).isTrue()
        assertThat(env.loads).isEqualTo(0)
        assertThat(control.starts).isEqualTo(0)
    }
    @Test fun `stale nonce or another backend cannot authorize a start`() = runTest {
        for (wrong in listOf(receipt().copy(instanceNonce = "b".repeat(32)), receipt().copy(backend = "xposed"))) {
            val control = Channel(status = wrong)
            val engine = RootEngine(Environment(control), control, Channel(), backgroundScope)
            assertThat(engine.start(cfg).isFailure).isTrue()
            assertThat(control.starts).isEqualTo(0)
        }
    }
    @Test fun `already loaded incompatible payload asks for restart instead of injecting twice`() = runTest {
        val control = Channel(true, receipt().copy(moduleVersion = "old"))
        val env = Environment(control)
        val engine = RootEngine(env, control, Channel(), backgroundScope)
        assertThat(engine.start(cfg).isFailure).isTrue()
        assertThat(env.loads).isEqualTo(0)
        assertThat(control.starts).isEqualTo(0)
    }
    @Test fun `unimplemented or incomplete receipts do not validate as root`() {
        val expected = RootReceipt(123, nonce, "0.1.0")
        assertThat(expected.matches(receipt())).isTrue()
        assertThat(expected.matches(receipt().copy(hooksInstalled = false))).isFalse()
        assertThat(expected.matches(receipt().copy(processId = 0))).isFalse()
        assertThat(expected.matches(receipt().copy(instanceNonce = ""))).isFalse()
        assertThat(expected.matches(receipt().copy(backend = ""))).isFalse()
        assertThat(RootReceipt(0, nonce, "0.1.0").matches(receipt().copy(processId = 0))).isFalse()
    }
    @Test fun `lost start receipt keeps stop unconfirmed until the root channel returns`() = runTest {
        val channel = Channel(status = receipt()).apply { loseStartReceipt = true }
        val engine = RootEngine(Environment(channel), channel, Channel(), backgroundScope)
        assertThat(engine.start(cfg).isFailure).isTrue()
        assertThat(engine.stop().isFailure).isTrue()
        assertThat(engine.state().value).isInstanceOf(EngineState.Failed::class.java)
        channel.reachable = true
        assertThat(engine.stop().isSuccess).isTrue()
        assertThat(channel.stops).isEqualTo(1)
        assertThat(engine.state().value).isEqualTo(EngineState.Idle)
    }
}
