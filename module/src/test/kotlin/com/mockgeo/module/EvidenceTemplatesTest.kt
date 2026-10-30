package com.mockgeo.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 纯逻辑层单测：身份判定、证据合成（NMEA/BSSID/小区/步数）、应用作用域配置解析。 */
class EvidenceTemplatesTest {

    @BeforeEach
    fun reset() {
        ModuleConfig.resetForTest()
        AppScopeConfig.invalidate()
    }

    private fun config(
        lat: Double = 39.9087,
        lng: Double = 116.3975,
        speed: Float = 1.2f,
        reportMock: Boolean = true,
    ) = ModuleConfig.apply(1) {
        it.with(lat = lat, lng = lng, speedMps = speed, bearingDeg = 45f, altitude = 50.0,
            enabled = true, reportMock = reportMock)
    }

    private fun freshConfig(
        lat: Double = 39.9087,
        lng: Double = 116.3975,
        speed: Float = 1.2f,
        reportMock: Boolean = true,
    ): ModuleConfig {
        ModuleConfig.resetForTest()
        return config(lat, lng, speed, reportMock)
    }

    // ---- 身份判定 ----

    @Test
    fun `hide mock only when enabled and compatibility format`() {
        assertThat(IdentityGuard.shouldHideMock(freshConfig(reportMock = true))).isFalse()
        assertThat(IdentityGuard.shouldHideMock(freshConfig(reportMock = false))).isTrue()
        ModuleConfig.setEnabled(false)
        assertThat(IdentityGuard.shouldHideMock(ModuleConfig.snapshot())).isFalse()
    }

    // ---- NMEA ----

    @Test
    fun `nmea checksum matches known sentence`() {
        val body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertThat(FakeEvidence.checksum(body)).isEqualTo("47")
    }

    @Test
    fun `dmm text uses two and three digit degrees`() {
        assertThat(FakeEvidence.dmmText(39.9087, true)).isEqualTo("3954.5220")
        assertThat(FakeEvidence.dmmText(116.3975, false)).isEqualTo("11623.8500")
        assertThat(FakeEvidence.dmmText(-33.8688, true)).isEqualTo("3352.1280")
    }

    @Test
    fun `hemispheres`() {
        assertThat(FakeEvidence.hemisphere(-33.8, true)).isEqualTo("S")
        assertThat(FakeEvidence.hemisphere(39.9, true)).isEqualTo("N")
        assertThat(FakeEvidence.hemisphere(116.4, false)).isEqualTo("E")
        assertThat(FakeEvidence.hemisphere(-73.9, false)).isEqualTo("W")
    }

    @Test
    fun `gga sentence is well formed and self consistent`() {
        val sentence = FakeEvidence.nmeaGga(39.9087, 116.3975, 50.0, 1_700_000_000_000L)
        assertThat(sentence).startsWith("\$GPGGA,")
        assertThat(sentence).endsWith("\r\n")
        val star = sentence.indexOf('*')
        val body = sentence.substring(1, star)
        assertThat(sentence.substring(star + 1, star + 3)).isEqualTo(FakeEvidence.checksum(body))
        assertThat(body).contains("3954.5220,N")
        assertThat(body).contains("11623.8500,E")
    }

    @Test
    fun `rmc sentence converts speed to knots`() {
        val sentence = FakeEvidence.nmeaRmc(39.9087, 116.3975, 1.0f, 90f, 1_700_000_000_000L)
        // 1 m/s ≈ 1.94 节
        assertThat(sentence).contains(",1.9,90.0,")
    }

    // ---- 证据确定性 ----

    @Test
    fun `bssid is deterministic per coordinate and locally administered`() {
        val a = FakeEvidence.bssid(39.9087, 116.3975, 0)
        val b = FakeEvidence.bssid(39.9087, 116.3975, 0)
        val c = FakeEvidence.bssid(31.2304, 121.4737, 0)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a).startsWith("02:")
        assertThat(a.split(":")).hasSize(6)
    }

    @Test
    fun `cell identity stays in valid ranges`() {
        val id = FakeEvidence.cellIdentity(39.9087, 116.3975)
        assertThat(id.mcc).isEqualTo(460)
        assertThat(id.mnc).isIn(0..99)
        assertThat(id.pci).isIn(0..502)
        assertThat(id.tac).isIn(0..65535)
        assertThat(id.ci).isAtLeast(0)
    }

    @Test
    fun `steps advance with time only while moving`() {
        val moving = freshConfig(speed = 1.0f)
        val still = freshConfig(speed = 0f)
        assertThat(FakeEvidence.steps(moving, 60_000L)).isEqualTo(60)
        assertThat(FakeEvidence.steps(still, 60_000L)).isEqualTo(0)
    }

    // ---- 应用作用域配置解析 ----

    @Test
    fun `scope state parses published json`() {
        val json = """{"e":1,"h":1,"lat":39.9,"lng":116.4,"spd":1.2,"sat":12,"ts":1700000000000,"et":1000,"boot":4,"session":10,"alt":50,"brg":90}"""
        val state = AppScopeConfig.parse(json)
        assertThat(state).isNotNull()
        assertThat(state!!.enabled).isTrue()
        assertThat(state.hideMock).isTrue()
        assertThat(state.satellites).isEqualTo(12)
        assertThat(state.lat).isWithin(1e-6).of(39.9)
    }

    @Test
    fun `scope state rejects malformed payloads`() {
        assertThat(AppScopeConfig.parse(null)).isNull()
        assertThat(AppScopeConfig.parse("")).isNull()
        assertThat(AppScopeConfig.parse("not json")).isNull()
    }
    @Test
    fun `scope state carries exemption list and gates by package`() {
        val json = """{"e":1,"h":1,"lat":39.9,"lng":116.4,"spd":1.2,"sat":12,"ts":1700000000000,"et":1,"boot":1,"session":1,"alt":50,"brg":90,"x":"com.a,com.b"}"""
        val state = AppScopeConfig.parse(json)
        assertThat(state).isNotNull()
        assertThat(state!!.exempt).containsExactly("com.a", "com.b")
        assertThat(state.activeFor("com.a")).isFalse()
        assertThat(state.activeFor("com.c")).isTrue()
        assertThat(state.activeFor(null)).isTrue()
    }

    @Test
    fun `scope state without exemption list is active for everyone`() {
        val json = """{"e":1,"h":0,"lat":39.9,"lng":116.4,"spd":0,"sat":12,"ts":1700000000000,"et":1,"boot":1,"session":1,"alt":0,"brg":0}"""
        val state = AppScopeConfig.parse(json)!!
        assertThat(state.exempt).isEmpty()
        assertThat(state.activeFor("com.any")).isTrue()
    }

}
