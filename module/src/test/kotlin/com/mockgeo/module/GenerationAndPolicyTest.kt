package com.mockgeo.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import kotlin.math.*

class GenerationAndPolicyTest {
    private val base = ScopeState(true, true, 39.9, 116.4, 1f, 12, 0, 1000, 1, 1)
    @Test fun `GNSS count and constellation remain coherent including zero and maximum`() {
        for (count in listOf(0, 1, 4, 12, 32, 64)) {
            val sats = GnssSynth.satellites(count)
            assertThat(sats).hasSize(count)
            assertThat(sats.map { it.constellation to it.svid }.distinct()).hasSize(count)
            sats.forEach { s ->
                assertThat(s.svid).isIn(1..32)
                assertThat(s.elevation).isAtLeast(0f)
                assertThat(s.elevation).isAtMost(90f)
                assertThat(s.carrierHz).isEqualTo(if (s.constellation == 1) 1_575_420_000f else 1_561_098_000f)
            }
        }
    }
    @Test fun `acceleration equals gravity plus linear acceleration at every sample`() {
        for (speed in listOf(0f, 0.2f, 1.4f, 8f)) for (time in listOf(0L, 123L, 550L, 1200L)) {
            val state = base.copy(speed = speed)
            val acc = FloatArray(3); val gravity = FloatArray(3); val linear = FloatArray(3)
            SensorSynth.apply(1, acc, state, time)
            SensorSynth.apply(9, gravity, state, time)
            SensorSynth.apply(10, linear, state, time)
            for (i in 0..2) assertThat(acc[i]).isWithin(0.00001f).of(gravity[i] + linear[i])
        }
    }
    @Test fun `rotation vector and magnetic field describe the same yaw about Z`() {
        for (bearing in listOf(0f, 45f, 90f, 180f, 270f, 359f)) {
            val rotation = FloatArray(4); val magnetic = FloatArray(3)
            SensorSynth.apply(11, rotation, base.copy(bearing = bearing), 0)
            SensorSynth.apply(2, magnetic, base.copy(bearing = bearing), 0)
            assertThat(rotation[0]).isEqualTo(0f); assertThat(rotation[1]).isEqualTo(0f)
            val z = rotation[2].toDouble(); val w = rotation[3].toDouble()
            val azimuth = atan2(-2 * w * z, 1 - 2 * z * z)
            val magAzimuth = atan2(-magnetic[0].toDouble(), magnetic[1].toDouble())
            assertThat(sin(azimuth)).isWithin(0.00001).of(sin(Math.toRadians(bearing.toDouble())))
            assertThat(cos(azimuth)).isWithin(0.00001).of(cos(magAzimuth))
        }
    }
    @Test fun `gyroscope turns across north and resets across sessions`() {
        val motion = SensorMotion()
        assertThat(motion.turnRate(base.copy(bearing = 359f))).isEqualTo(0f)
        val next = base.copy(bearing = 1f, elapsedMs = 2000)
        assertThat(motion.turnRate(next)).isWithin(0.000001f).of(Math.toRadians(2.0).toFloat())
        assertThat(motion.turnRate(next)).isWithin(0.000001f).of(Math.toRadians(2.0).toFloat())
        assertThat(motion.turnRate(next.copy(session = 2))).isEqualTo(0f)
    }
    @Test fun `unsupported and short sensor samples remain unmodified`() {
        val original = floatArrayOf(7f, 8f)
        assertThat(SensorSynth.apply(11, original, base, 0)).isFalse()
        assertThat(original.toList()).containsExactly(7f, 8f).inOrder()
        val other = floatArrayOf(7f, 8f, 9f)
        assertThat(SensorSynth.apply(999, other, base, 0)).isFalse()
        assertThat(other.toList()).containsExactly(7f, 8f, 9f).inOrder()
    }
    @Test fun `exemption policy accepts comments but rejects malformed package identities`() {
        assertThat(Exemptions.parse("# comment\ncom.mockgeo.probe # test\nandroid\ncom.mockgeo.probe"))
            .containsExactly("com.mockgeo.probe", "android")
        assertThrows<IllegalArgumentException> { Exemptions.parse("com.bad package") }
        assertThrows<IllegalArgumentException> { Exemptions.parse("com.bad,com.other") }
        assertThrows<IllegalArgumentException> { Exemptions.parse("a".repeat(20_000)) }
    }
    @Test fun `mirror rejects duplicate exemption field and malformed separators`() {
        val json = """{"e":1,"h":1,"lat":39.9,"lng":116.4,"spd":1.2,"sat":12,"ts":1,"et":1000,"boot":4,"session":10,"alt":50,"brg":90,"x":"com.mockgeo.probe"}"""
        assertThat(AppScopeConfig.parse(json)!!.activeFor("com.mockgeo.probe")).isFalse()
        assertThat(AppScopeConfig.parse(json.replace("}", ",\"x\":\"com.mockgeo.probe\"}"))).isNull()
        assertThat(AppScopeConfig.parse(json.replace(",\"x\"", ",,\"x\""))).isNull()
        assertThat(AppScopeConfig.parse(json.replace("}", ",}"))).isNull()
    }
    @Test fun `native and synthetic racing callbacks cannot exceed the request quota`() {
        val quota = CallbackQuota(13)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val delivered = (1..100).map { pool.submit<Int> { quota.claim(3) } }.sumOf { it.get() }
            assertThat(delivered).isEqualTo(13)
            assertThat(quota.claim(1)).isEqualTo(0)
        } finally { pool.shutdownNow() }
    }
}
