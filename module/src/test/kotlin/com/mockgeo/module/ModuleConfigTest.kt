package com.mockgeo.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 模块配置快照测试：revision 单调性（乱序命令丢弃）、fail-safe 默认值。
 * ModuleConfig 是全局单例——每个用例前复位（测试隔离）。
 */
class ModuleConfigTest {

    @BeforeEach
    fun reset() {
        ModuleConfig.resetForTest()
    }

    @Test
    fun `default is disabled (fail-safe)`() {
        // system_server 重启后 enabled 必须为 false（docs/06 §4 第 5 条）
        assertThat(ModuleConfig.snapshot().enabled).isFalse()
        assertThat(ModuleConfig.snapshot().revision).isEqualTo(0)
    }

    @Test
    fun `compatibility output format persists across ordinary updates`() {
        assertThat(ModuleConfig.snapshot().reportMock).isTrue()
        ModuleConfig.apply(1) { it.with(reportMock = false, enabled = true) }
        val next = ModuleConfig.apply(2) { it.with(lat = 39.9, lng = 116.4) }
        assertThat(next.reportMock).isFalse()
        assertThat(next.enabled).isTrue()
        ModuleConfig.setEnabled(false)
        assertThat(ModuleConfig.snapshot().enabled).isFalse()
    }

    @Test
    fun `apply accepts newer revision`() {
        val applied = ModuleConfig.apply(5) { it.with(lat = 39.9, lng = 116.4, enabled = true) }
        assertThat(applied.revision).isEqualTo(5)
        assertThat(applied.enabled).isTrue()
        assertThat(applied.lat).isEqualTo(39.9)
        assertThat(ModuleConfig.snapshot().enabled).isTrue()
    }

    @Test
    fun `apply rejects stale revision`() {
        ModuleConfig.apply(10) { it.with(enabled = true) }
        val applied = ModuleConfig.apply(5) { it.with(enabled = false) }
        // 旧命令被拒：状态不变
        assertThat(applied.revision).isEqualTo(10)
        assertThat(applied.enabled).isTrue()
        assertThat(ModuleConfig.snapshot().enabled).isTrue()
    }

    @Test
    fun `same revision cannot overwrite an applied command`() {
        ModuleConfig.apply(7) { it.with(enabled = true, lat = 1.0) }
        val applied = ModuleConfig.apply(7) { it.with(enabled = true, lat = 2.0) }
        assertThat(applied.revision).isEqualTo(7)
        assertThat(ModuleConfig.snapshot().lat).isEqualTo(1.0)
    }

    @Test
    fun `emergency stop advances revision and rejects older replay`() {
        ModuleConfig.apply(3) { it.with(enabled = true) }
        val after = ModuleConfig.setEnabled(false)
        assertThat(after.enabled).isFalse()
        assertThat(after.revision).isEqualTo(4)
        // 幂等：再设 false 不变
        val again = ModuleConfig.setEnabled(false)
        assertThat(again.enabled).isFalse()
    }

    @Test
    fun `with preserves unspecified fields`() {
        ModuleConfig.apply(1) { it.with(lat = 5.0, lng = 6.0, accuracyM = 9f, satellites = 20) }
        val applied = ModuleConfig.apply(2) { it.with(enabled = true) }
        // 未指定的字段保留
        assertThat(applied.lat).isEqualTo(5.0)
        assertThat(applied.lng).isEqualTo(6.0)
        assertThat(applied.accuracyM).isEqualTo(9f)
        assertThat(applied.satellites).isEqualTo(20)
    }
    @Test
    fun `controller death expires lease without system server restart`() {
        ModuleConfig.apply(1, nowMs = 100) { it.with(enabled = true) }
        assertThat(ModuleConfig.leaseRemainingMs(100)).isEqualTo(15_000L)
        assertThat(ModuleConfig.snapshot(14_999).enabled).isTrue()
        assertThat(ModuleConfig.snapshot(15_100).enabled).isFalse()
        assertThat(ModuleConfig.leaseRemainingMs(15_100)).isEqualTo(0L)
        assertThat(ModuleConfig.leaseExpirations).isEqualTo(1L)
        ModuleConfig.snapshot(16_000)
        assertThat(ModuleConfig.leaseExpirations).isEqualTo(1L)
        // Retrying an old SET cannot bring a stopped session back to life.
        ModuleConfig.apply(1, nowMs = 16_000) { it.with(enabled = true) }
        assertThat(ModuleConfig.snapshot(16_000).enabled).isFalse()
    }

    @Test
    fun `fresh controller frame renews lease`() {
        ModuleConfig.apply(1, nowMs = 100) { it.with(enabled = true) }
        ModuleConfig.apply(2, nowMs = 10_000) { it.with(enabled = true) }
        assertThat(ModuleConfig.snapshot(15_100).enabled).isTrue()
        assertThat(ModuleConfig.leaseExpirations).isEqualTo(0L)
        assertThat(ModuleConfig.snapshot(25_000).enabled).isFalse()
    }

    @Test
    fun `concurrent old frame cannot overwrite newer stop`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val old = Thread {
            ModuleConfig.apply(1) {
                entered.countDown()
                release.await()
                it.with(enabled = true)
            }
        }
        old.start()
        entered.await()
        val stop = Thread { ModuleConfig.apply(2) { it.with(enabled = false) } }
        stop.start()
        release.countDown()
        old.join(2000)
        stop.join(2000)
        assertThat(ModuleConfig.snapshot().revision).isEqualTo(2)
        assertThat(ModuleConfig.snapshot().enabled).isFalse()
    }

}
