package com.mockgeo.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StepCadenceTest {
    @Test fun `walking waveform cycles follow requested steps per minute`() {
        fun peaks(cadence: Int): Int {
            val state = ScopeState(true, false, 0.0, 0.0, 1.4f, 12, 1, stepCadenceSpm = cadence)
            val values = (0..80).map { tick ->
                FloatArray(3).also { SensorSynth.apply(10, it, state, tick * 25L) }[2]
            }
            return (1 until values.lastIndex).count { values[it] > values[it - 1] && values[it] > values[it + 1] }
        }
        assertThat(peaks(60)).isEqualTo(2)
        assertThat(peaks(120)).isEqualTo(4)
    }
    @Test fun `cadence controls rate while pause freezes and reset prevents catch up`() {
        val steps = StepAccumulator()
        assertThat(steps.counter(100f, 1f, 1, 0, 120)).isEqualTo(100f)
        assertThat(steps.counter(100f, 0f, 1, 3000, 120)).isEqualTo(106f)
        assertThat(steps.counter(100f, 0f, 1, 9000, 120)).isEqualTo(106f)
        steps.reset()
        assertThat(steps.counter(110f, 1f, 1, 20000, 60)).isEqualTo(110f)
        assertThat(steps.counter(110f, 1f, 1, 23000, 60)).isEqualTo(113f)
    }
    @Test fun `detector interval follows the configured cadence`() {
        val steps = StepAccumulator()
        assertThat(steps.detector(1f, 1, 0, 60)).isEqualTo(1f)
        assertThat(steps.detector(1f, 1, 500, 60)).isNull()
        assertThat(steps.detector(1f, 1, 1000, 60)).isEqualTo(1f)
        assertThat(steps.detector(1f, 1, 1200, 300)).isEqualTo(1f)
    }
    @Test fun `cadence mirror accepts older payload and rejects out of range values`() {
        val old = """{"e":1,"h":0,"lat":0,"lng":0,"spd":1,"sat":12,"ts":1,"et":1,"boot":1,"session":1,"alt":0,"brg":0}"""
        assertThat(AppScopeConfig.parse(old)?.stepCadenceSpm).isEqualTo(0)
        assertThat(AppScopeConfig.parse(old.dropLast(1) + ",\"cad\":120}")?.stepCadenceSpm).isEqualTo(120)
        assertThat(AppScopeConfig.parse(old.dropLast(1) + ",\"cad\":301}")).isNull()
    }
}
