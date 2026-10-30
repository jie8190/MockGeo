package com.mockgeo.core.data.prefs

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SettingsJsonTest {
    @Test fun `backup round trip retains preferences but not device engine`() {
        val original = AppSettings(engineId = "xposed", simIntervalMs = 250, gpsAccuracyM = 4.2,
            jitterEnabled = true, jitterMeters = 12.5, routeSpeedMps = 7.0, stepCadenceSpm = 120,
            historyLimit = 420, screenKeepOn = true, theme = "dark", language = "zh-CN",
            networkProviderToo = true, altitudeM = 8848.0, satellites = 32, sineSpeedCurve = false)
        val encoded = SettingsJson.encode(original)
        assertFalse(encoded.contains("engineId"))
        assertEquals(original.copy(engineId = "provider"), SettingsJson.decode(encoded))
    }

    @Test fun `invalid final field rejects whole document`() {
        val root = JSONObject(SettingsJson.encode(AppSettings()))
        root.getJSONObject("settings").put("satellites", 99)
        assertThrows(IllegalArgumentException::class.java) { SettingsJson.decode(root.toString()) }
    }

    @Test fun `fractional integer and coerced booleans are rejected`() {
        val root = JSONObject(SettingsJson.encode(AppSettings()))
        root.getJSONObject("settings").put("historyLimit", 40.5)
        assertThrows(IllegalArgumentException::class.java) { SettingsJson.decode(root.toString()) }
        root.getJSONObject("settings").put("historyLimit", 40).put("jitterEnabled", "false")
        assertThrows(IllegalStateException::class.java) { SettingsJson.decode(root.toString()) }
    }

    @Test fun `missing field and unsupported theme are rejected`() {
        val root = JSONObject(SettingsJson.encode(AppSettings()))
        root.getJSONObject("settings").remove("routeSpeedMps")
        assertThrows(IllegalStateException::class.java) { SettingsJson.decode(root.toString()) }
        root.getJSONObject("settings").put("routeSpeedMps", 1.4).put("theme", "unknown")
        assertThrows(IllegalArgumentException::class.java) { SettingsJson.decode(root.toString()) }
    }

    @Test fun `format version and trailing content are checked`() {
        val encoded = SettingsJson.encode(AppSettings())
        val root = JSONObject(encoded).put("version", 2)
        assertThrows(IllegalArgumentException::class.java) { SettingsJson.decode(root.toString()) }
        assertThrows(IllegalArgumentException::class.java) { SettingsJson.decode(encoded + " garbage") }
    }
}
