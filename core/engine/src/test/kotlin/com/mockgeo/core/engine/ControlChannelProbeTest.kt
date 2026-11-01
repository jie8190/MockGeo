package com.mockgeo.core.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ControlChannelProbeTest {
    private class TestClock(var now: Long = 1_000) : Clock {
        override fun currentTimeMillis() = now
        override fun elapsedRealtime() = now
        override fun elapsedRealtimeNanos() = now * 1_000_000
    }
    private fun status() = XposedEngine.ModuleStatus(false, 1, 39.123456, 116.123456,
        true, 10, 1_000, "0.3.13", backend = "xposed", processId = 123)

    @Test fun `delayed receipt is accepted without resending the command or exposing position`() = runTest {
        val log = RingSimLogger()
        var sent = 0
        var reads = 0
        val probe = ControlChannelProbe(TestClock(), "xposed", log, { sent++ }, {
            if (++reads < 3) null else status()
        })
        assertThat(probe.check().reachable).isTrue()
        assertThat(sent).isEqualTo(1)
        assertThat(testScheduler.currentTime).isEqualTo(300)
        assertThat(log.recent().single().message).contains("receipt=ok")
        assertThat(log.recent().toString()).doesNotContain("39.123456")
    }

    @Test fun `no receipt times out with actionable log instead of claiming disabled module`() = runTest {
        val log = RingSimLogger()
        val checked = ControlChannelProbe(TestClock(), "xposed", log, {}, { null }).check()
        assertThat(checked.reachable).isFalse()
        assertThat(checked.detail).contains("不能据此判断 LSPosed 开关状态")
        assertThat(log.recent().single().message).contains("stage=no_receipt")
        assertThat(testScheduler.currentTime).isEqualTo(450)
    }

    @Test fun `send exception is recorded and does not read a stale success`() = runTest {
        val log = RingSimLogger()
        val result = ControlChannelProbe(TestClock(), "xposed", log,
            { throw SecurityException("fixture denied") }, { error("must not read") }).check()
        assertThat(result.reachable).isFalse()
        assertThat(result.detail).contains("SecurityException")
        assertThat(log.recent().single().error).contains("fixture denied")
        assertThat(log.recent().single().message).contains("stage=send_failed")
    }

    @Test fun `provider failures are distinguishable from module load failures`() = runTest {
        val log = RingSimLogger()
        val result = ControlChannelProbe(TestClock(), "xposed", log, {},
            { throw IllegalArgumentException("fixture missing column") }).check()
        assertThat(result.reachable).isFalse()
        assertThat(result.detail).contains("回执读取失败")
        assertThat(log.recent().single().message).contains("stage=read_failed")
    }

    @Test fun `fresh but earlier receipt and wrong backend cannot pass`() = runTest {
        for ((receipt, code) in listOf(status().copy(updatedAt = 999) to "stale_receipt",
            status().copy(backend = "root") to "wrong_backend")) {
            val log = RingSimLogger()
            assertThat(ControlChannelProbe(TestClock(), "xposed", log, {}, { receipt }).check().reachable).isFalse()
            assertThat(log.recent().single().message).contains("stage=$code")
        }
    }

    @Test fun `root still requires the nonce of this exact request`() = runTest {
        var nonce = ""
        val receipt = status().copy(backend = "root")
        val accepted = ControlChannelProbe(TestClock(), "root", RingSimLogger(), { nonce = it },
            { receipt.copy(responseNonce = nonce) }).check()
        assertThat(accepted.reachable).isTrue()
        val log = RingSimLogger()
        assertThat(ControlChannelProbe(TestClock(), "root", log, {}, { receipt }).check().reachable).isFalse()
        assertThat(log.recent().single().message).contains("stage=nonce_mismatch")
    }

    @Test fun `cancellation is never swallowed as an activation error`() = runTest {
        val log = RingSimLogger()
        assertThrows<CancellationException> {
            ControlChannelProbe(TestClock(), "xposed", log, { throw CancellationException() }, { null }).check()
        }
        assertThat(log.recent()).isEmpty()
    }
}
