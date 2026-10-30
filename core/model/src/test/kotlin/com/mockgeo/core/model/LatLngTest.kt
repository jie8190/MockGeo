package com.mockgeo.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class LatLngTest {

    @Test
    fun `distance known pair`() {
        // 北京天安门 → 北京南站，约 4.7 km（实测参考值）
        val tiananmen = LatLng(39.9087, 116.3975)
        val nanzhan = LatLng(39.8652, 116.3786)
        val d = tiananmen.distanceTo(nanzhan)
        assertThat(d).isWithin(500.0).of(4700.0)
    }

    @Test
    fun `distance symmetric and zero`() {
        val a = LatLng(31.2304, 121.4737)
        val b = LatLng(30.5728, 104.0668)
        assertThat(a.distanceTo(b)).isWithin(1e-6).of(b.distanceTo(a))
        assertThat(a.distanceTo(a)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `bearing four quadrants`() {
        val origin = LatLng(0.0, 0.0)
        assertThat(origin.bearingTo(LatLng(1.0, 0.0))).isWithin(0.1f).of(0f) // 北
        assertThat(origin.bearingTo(LatLng(0.0, 1.0))).isWithin(0.1f).of(90f) // 东
        assertThat(origin.bearingTo(LatLng(-1.0, 0.0))).isWithin(0.1f).of(180f) // 南
        assertThat(origin.bearingTo(LatLng(0.0, -1.0))).isWithin(0.1f).of(270f) // 西
        // 东北 45°
        assertThat(origin.bearingTo(LatLng(1.0, 1.0))).isWithin(0.2f).of(45f)
    }

    @Test
    fun `bearing range 0-360`() {
        val a = LatLng(39.9, 116.4)
        val b = LatLng(39.8, 116.3)
        val br = a.bearingTo(b)
        assertThat(br).isAtLeast(0f)
        assertThat(br).isLessThan(360f)
    }

    @Test
    fun `destination round trip`() {
        val a = LatLng(39.9087, 116.3975)
        val dest = a.destination(45.0, 1000.0)
        assertThat(a.distanceTo(dest)).isWithin(1.0).of(1000.0)
        assertThat(a.bearingTo(dest)).isWithin(0.5f).of(45f)
    }

    @Test
    fun `destination zero distance`() {
        val a = LatLng(1.0, 2.0)
        assertThat(a.destination(90.0, 0.0)).isEqualTo(a)
    }

    @Test
    fun `parse valid and invalid`() {
        assertThat(LatLng.parse("39.9, 116.4")).isEqualTo(LatLng(39.9, 116.4))
        assertThat(LatLng.parse("39.9")).isNull()
        assertThat(LatLng.parse("abc,def")).isNull()
        assertThat(LatLng.parse("91,0")).isNull()
        assertThat(LatLng.parse("0,181")).isNull()
    }

    @Test
    fun `constructor rejects out of range`() {
        assertThrows<IllegalArgumentException> { LatLng(91.0, 0.0) }
        assertThrows<IllegalArgumentException> { LatLng(0.0, -181.0) }
    }

    @Test
    fun `normalizeLng wraps`() {
        assertThat(LatLng.normalizeLng(190.0)).isWithin(1e-12).of(-170.0)
        assertThat(LatLng.normalizeLng(-190.0)).isWithin(1e-12).of(170.0)
        assertThat(LatLng.normalizeLng(10.0)).isWithin(1e-12).of(10.0)
    }
}

class JoystickVectorTest {

    @Test
    fun `dead zone suppresses small input`() {
        val v = JoystickVector.fromRaw(0.05f, 0.05f)
        assertThat(v.isIdle).isTrue()
        assertThat(v.magnitude).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `full input normalized to magnitude 1`() {
        val v = JoystickVector.fromRaw(1f, 0f)
        assertThat(v.magnitude).isWithin(1e-6f).of(1f)
        assertThat(v.dx).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `bearing north east south west`() {
        assertThat(JoystickVector(0f, -1f).toBearingDeg()).isWithin(0.1f).of(0f) // 上 = 北
        assertThat(JoystickVector(1f, 0f).toBearingDeg()).isWithin(0.1f).of(90f) // 右 = 东
        assertThat(JoystickVector(0f, 1f).toBearingDeg()).isWithin(0.1f).of(180f) // 下 = 南
        assertThat(JoystickVector(-1f, 0f).toBearingDeg()).isWithin(0.1f).of(270f) // 左 = 西
    }

    @Test
    fun `magnitude scales between dead zone and edge`() {
        // 死区 0.1：输入 0.55 → (0.55-0.1)/0.9 = 0.5
        val v = JoystickVector.fromRaw(0.55f, 0f)
        assertThat(abs(v.magnitude - 0.5f)).isLessThan(1e-4f)
    }
}

class SimConfigTest {

    private val pos = LatLng(0.0, 0.0)

    @Test
    fun `defaults are valid`() {
        val cfg = SimConfig(position = pos)
        assertThat(cfg.accuracyM).isEqualTo(5f)
        assertThat(cfg.satellites).isEqualTo(12)
    }

    @Test
    fun `rejects bad accuracy`() {
        assertThrows<IllegalArgumentException> { SimConfig(position = pos, accuracyM = 0f) }
    }

    @Test
    fun `rejects bad satellites`() {
        assertThrows<IllegalArgumentException> { SimConfig(position = pos, satellites = 100) }
    }

    @Test
    fun `rejects negative speed and jitter`() {
        assertThrows<IllegalArgumentException> { SimConfig(position = pos, speedMps = -1f) }
        assertThrows<IllegalArgumentException> { SimConfig(position = pos, jitterMeters = -1.0) }
    }
}
