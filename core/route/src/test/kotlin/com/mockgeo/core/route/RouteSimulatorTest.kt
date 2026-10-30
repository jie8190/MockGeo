package com.mockgeo.core.route

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.RouteMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class RouteSimulatorTest {

    /** 1 km 正北直线：纬度差 ≈ 0.008993°。 */
    private val start = LatLng(0.0, 0.0)
    private val end = LatLng(0.008993, 0.0)

    @Test
    fun `advance reaches endpoint in 4 steps of 250m`() {
        val sim = RouteSimulator(listOf(start, end), { 1.4 })
        var pos = sim.advance(178.6) // 250m / 1.4mps ≈ 178.6s
        assertThat(start.distanceTo(pos)).isWithin(2.0).of(250.0)
        repeat(3) { pos = sim.advance(178.6) }
        assertThat(start.distanceTo(pos)).isWithin(2.0).of(1000.0)
        assertThat(sim.finished).isTrue()
    }

    @Test
    fun `once mode stops at end with zero speed`() {
        val sim = RouteSimulator(listOf(start, end), { 10.0 }, RouteMode.ONCE)
        sim.advance(200.0) // 2000m > 1000m → 到终点
        assertThat(sim.finished).isTrue()
        assertThat(sim.currentSpeed()).isEqualTo(0.0)
        // 终点后再推进不动
        val pos = sim.advance(10.0)
        assertThat(start.distanceTo(pos)).isWithin(2.0).of(1000.0)
    }

    @Test
    fun `loop mode wraps back to start`() {
        val sim = RouteSimulator(listOf(start, end), { 10.0 }, RouteMode.LOOP)
        sim.advance(200.0) // 走 2000m → 绕整数圈后回到起点附近
        // 总长 ≈ 999.976m（haversine），2000m = 2 圈 + 余量
        assertThat(sim.progress).isWithin(1e-6).of(2000.0 % sim.totalDistance)
        assertThat(sim.finished).isFalse()
    }

    @Test
    fun `pingpong mode reverses at end`() {
        val sim = RouteSimulator(listOf(start, end), { 10.0 }, RouteMode.PING_PONG)
        sim.advance(150.0) // 1500m → 到终点反向 500m
        assertThat(sim.progress).isWithin(1.0).of(500.0)
        val pos = sim.advance(50.0) // 再反向 500m → 回起点
        assertThat(sim.progress).isWithin(1.0).of(0.0)
        assertThat(start.distanceTo(pos)).isWithin(2.0).of(0.0)
    }

    @Test
    fun `bearing along north segment is 0`() {
        val sim = RouteSimulator(listOf(start, end), { 1.0 })
        sim.advance(10.0)
        assertThat(sim.bearing()).isWithin(0.5f).of(0f)
    }

    @Test
    fun `bearing east segment is 90`() {
        val sim = RouteSimulator(listOf(start, LatLng(0.0, 0.008993)), { 1.0 })
        sim.advance(10.0)
        assertThat(sim.bearing()).isWithin(0.5f).of(90f)
    }

    @Test
    fun `pingpong reverse bearing flips 180`() {
        val sim = RouteSimulator(listOf(start, end), { 10.0 }, RouteMode.PING_PONG)
        sim.advance(120.0) // 过终点反向
        assertThat(sim.bearing()).isWithin(0.5f).of(180f)
    }

    @Test
    fun `multi segment interpolation`() {
        // 0→(0.0044965,0)→(0.0044965, 0.0044965)：L 形两段各 ~500m
        val mid = LatLng(0.0044965, 0.0)
        val corner = LatLng(0.0044965, 0.0044965)
        val sim = RouteSimulator(listOf(start, mid, corner), { 1.0 })
        val pos = sim.advance(250.0) // 250m：第一段中点
        assertThat(pos.lat).isWithin(1e-6).of(0.00224825)
        assertThat(pos.lng).isWithin(1e-9).of(0.0)
        val pos2 = sim.advance(250.0) // 500m：拐点
        assertThat(pos2.distanceTo(corner)).isWithin(1.0).of(500.0)
    }

    @Test
    fun `total distance is haversine sum`() {
        val sim = RouteSimulator(listOf(start, end), { 1.0 })
        assertThat(sim.totalDistance).isWithin(1.0).of(1000.0)
    }

    @Test
    fun `zero speed does not advance`() {
        val sim = RouteSimulator(listOf(start, end), { 0.0 })
        val pos = sim.advance(100.0)
        assertThat(start.distanceTo(pos)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `requires at least 2 points`() {
        assertThrows<IllegalArgumentException> { RouteSimulator(listOf(start), { 1.0 }) }
    }

    @Test
    fun `speed curve receives progress`() {
        var seen = -1.0
        val sim = RouteSimulator(listOf(start, end), { p -> seen = p; 1.0 })
        sim.advance(100.0)
        assertThat(seen).isAtLeast(0.0)
    }
}

class SpeedCurvesTest {

    @Test
    fun `constant`() {
        val f = SpeedCurves.constant(2.5)
        assertThat(f(0.0)).isEqualTo(2.5)
        assertThat(f(123.0)).isEqualTo(2.5)
    }

    @Test
    fun `sine keeps a rolling speed at endpoints`() {
        val total = 1000.0
        val f = SpeedCurves.sine(2.0, rampMeters = 100.0, totalDistance = total)
        assertThat(f(0.0)).isWithin(1e-9).of(0.16)
        assertThat(f(100.0)).isWithin(1e-9).of(2.0)
        assertThat(f(500.0)).isWithin(1e-9).of(2.0)
        assertThat(f(1000.0)).isWithin(1e-9).of(0.16)
    }

    @Test
    fun `sine route leaves start and reaches endpoint`() {
        val start = LatLng(0.0, 0.0)
        val end = LatLng(0.001, 0.0)
        val distance = start.distanceTo(end)
        val sim = RouteSimulator(listOf(start, end), SpeedCurves.sine(4.0, 20.0, distance))
        sim.advance(1.0)
        assertThat(sim.progress).isGreaterThan(0.0)
        repeat(500) { sim.advance(1.0) }
        assertThat(sim.finished).isTrue()
        assertThat(sim.currentSpeed()).isEqualTo(0.0)
    }

    @Test
    fun `date line segment takes the short path`() {
        val a = LatLng(0.0, 179.9)
        val b = LatLng(0.0, -179.9)
        val sim = RouteSimulator(listOf(a, b), { a.distanceTo(b) / 2.0 })
        val mid = sim.advance(1.0)
        assertThat(kotlin.math.abs(mid.lng)).isWithin(0.000001).of(180.0)
    }

    @Test
    fun `long loop delay preserves distance after many laps`() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.00001, 0.0)
        val length = a.distanceTo(b)
        val sim = RouteSimulator(listOf(a, b), { length }, RouteMode.LOOP)
        sim.advance(20000.5)
        assertThat(sim.progress).isWithin(0.000001).of(length / 2)
    }

    @Test
    fun `sine on short route stays positive mid`() {
        val f = SpeedCurves.sine(2.0, rampMeters = 100.0, totalDistance = 50.0)
        assertThat(f(25.0)).isGreaterThan(0.0)
        assertThat(f(25.0)).isAtMost(2.0)
    }
}

class JitterWalkerTest {

    @Test
    fun `stays within radius`() {
        val w = JitterWalker.deterministic(5.0, seed = 42L)
        repeat(500) { w.step(0.1) }
        // 多次采样都在半径内
        repeat(50) {
            val o = w.step(0.1)
            assertThat(kotlin.math.sqrt(o.x * o.x + o.y * o.y)).isAtMost(5.0)
        }
    }

    @Test
    fun `zero radius is no-op`() {
        val w = JitterWalker.deterministic(0.0, seed = 1L)
        val o = w.step(1.0)
        assertThat(o.x).isEqualTo(0.0)
        assertThat(o.y).isEqualTo(0.0)
    }

    @Test
    fun `random walk is not independent per frame`() {
        // 相邻两帧偏移相关（随机游走），不是每帧独立采样
        val w = JitterWalker.deterministic(10.0, seed = 7L)
        val o1 = w.step(0.05)
        val o2 = w.step(0.05)
        val jump = kotlin.math.sqrt((o2.x - o1.x) * (o2.x - o1.x) + (o2.y - o1.y) * (o2.y - o1.y))
        // 0.05s 步长下跳变应远小于半径（独立采样时可达 2R）
        assertThat(jump).isLessThan(10.0)
    }

    @Test
    fun `apply converts meters to degrees`() {
        val w = JitterWalker.deterministic(0.0, seed = 1L)
        val base = LatLng(45.0, 90.0)
        val moved = w.apply(base, JitterWalker.Offset(0.0, 111.32)) // 北移 111.32m ≈ 0.001°
        assertThat(moved.lat - base.lat).isWithin(1e-5).of(0.001)
    }
}

class JoystickMoverTest {

    @Test
    fun `moves along bearing while pushed`() {
        val m = JoystickMover(speedMps = 10.0)
        val from = LatLng(0.0, 0.0)
        val next = m.advance(from, com.mockgeo.core.model.JoystickVector(0f, -1f), 10.0)
        assertThat(from.distanceTo(next)).isWithin(0.5).of(100.0)
        assertThat(m.currentBearing()).isWithin(0.1f).of(0f)
    }

    @Test
    fun `decays after release`() {
        val m = JoystickMover(speedMps = 10.0, decaySeconds = 0.5)
        val from = LatLng(0.0, 0.0)
        m.advance(from, com.mockgeo.core.model.JoystickVector(0f, -1f), 1.0)
        val idle = com.mockgeo.core.model.JoystickVector(0f, 0f)
        val s1 = m.currentSpeed()
        m.advance(from, idle, 1.0)
        val s2 = m.currentSpeed()
        assertThat(s2).isLessThan(s1)
        // 长时间空闲后归零
        repeat(20) { m.advance(from, idle, 1.0) }
        assertThat(m.currentSpeed()).isEqualTo(0.0)
    }

    @Test
    fun `immediate stop with zero decay`() {
        val m = JoystickMover(speedMps = 10.0, decaySeconds = 0.0)
        val from = LatLng(0.0, 0.0)
        m.advance(from, com.mockgeo.core.model.JoystickVector(1f, 0f), 1.0)
        val pos = m.advance(from, com.mockgeo.core.model.JoystickVector(0f, 0f), 1.0)
        assertThat(m.currentSpeed()).isEqualTo(0.0)
        assertThat(from.distanceTo(pos)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `magnitude scales speed`() {
        val m = JoystickMover(speedMps = 10.0)
        m.advance(LatLng(0.0, 0.0), com.mockgeo.core.model.JoystickVector(0f, -0.5f), 1.0)
        assertThat(m.currentSpeed()).isWithin(1e-6).of(5.0)
    }
}
