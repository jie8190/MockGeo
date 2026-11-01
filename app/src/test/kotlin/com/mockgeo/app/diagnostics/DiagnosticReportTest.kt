package com.mockgeo.app.diagnostics

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.engine.SimLogger
import com.mockgeo.core.model.EngineState
import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.SimConfig
import org.junit.jupiter.api.Test

class DiagnosticReportTest {
    @Test fun `known secrets are removed from metadata and errors`() {
        val key = "privateMapKeyForUnitTest"
        val report = DiagnosticReport.render(listOf("fixture" to "failure $key"), emptyList(),
            listOf(SimLogger.LogLine(0, 42, 6, "Engine", "connection failed", "key=$key")), listOf(key))
        assertThat(report).doesNotContain(key)
        assertThat(report).contains("request=42")
        assertThat(report).contains("connection failed")
    }

    @Test fun `URL credentials bearer tokens and named coordinates are filtered`() {
        val cleaned = DiagnosticRedactor(emptyList()).clean(
            "url=https://example.test/?secret=opaque-value token=abc123 password=\"not for mail\" " +
                "Authorization: Bearer opaque.jwt-value lat=39.908700 lng=116.397500; 31.123456, 121.654321 " +
                "{\"lat\":\"30.654321\",\"longitude\":120.123456}")
        listOf("example.test", "opaque-value", "abc123", "not for mail", "opaque.jwt-value",
            "39.908700", "116.397500", "31.123456", "121.654321", "30.654321", "120.123456").forEach { assertThat(cleaned).doesNotContain(it) }
    }

    @Test fun `running state does not serialize the location configuration`() {
        val state = EngineState.Running(123, SimConfig(position = LatLng(39.9087, 116.3975)))
        assertThat(DiagnosticReport.stateLabel(state)).isEqualTo("运行中")
        assertThat(DiagnosticReport.stateLabel(EngineState.Stopping(4, state.cfg))).isEqualTo("停止中")
    }

    @Test fun `report keeps recent timestamps and bounded log history`() {
        val lines = (0..500).map { SimLogger.LogLine(it.toLong(), it.toLong(), 4, "Engine", "event-$it") }
        val report = DiagnosticReport.render(listOf("版本" to "0.4.5"), emptyList(), lines, emptyList())
        assertThat(report).doesNotContain("event-0\n")
        assertThat(report).contains("event-500")
        assertThat(report).contains("1970-01-01T00:00:00.500Z")
        assertThat(report).contains("版本：0.4.5")
    }
}
