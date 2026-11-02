package com.mockgeo.core.data.prefs

import org.json.JSONObject
import org.json.JSONTokener

/** Portable preferences. Runtime engine selection and su executable stay local. */
internal object SettingsJson {
    fun encode(value: AppSettings): String {
        val payload = JSONObject()
            .put("simIntervalMs", value.simIntervalMs)
            .put("gpsAccuracyM", value.gpsAccuracyM)
            .put("jitterEnabled", value.jitterEnabled)
            .put("jitterMeters", value.jitterMeters)
            .put("routeSpeedMps", value.routeSpeedMps)
            .put("stepCadenceSpm", value.stepCadenceSpm)
            .put("historyLimit", value.historyLimit)
            .put("screenKeepOn", value.screenKeepOn)
            .put("theme", value.theme)
            .put("language", value.language)
            .put("networkProviderToo", value.networkProviderToo)
            .put("altitudeM", value.altitudeM)
            .put("satellites", value.satellites)
            .put("sineSpeedCurve", value.sineSpeedCurve)
        return JSONObject().put("format", "mockgeo-settings").put("version", 1)
            .put("settings", payload).toString(2)
    }

    fun decode(json: String): AppSettings {
        require(json.length <= 64 * 1024) { "设置文件过大" }
        val tokener = JSONTokener(json)
        val root = tokener.nextValue() as? JSONObject ?: error("设置文件必须为 JSON 对象")
        require(tokener.nextClean() == '\u0000') { "JSON 后存在额外内容" }
        require(root.opt("format") == "mockgeo-settings") { "不是 MockGeo 设置文件" }
        require(integer(root, "version", 1..1) == 1) { "不支持的设置版本" }
        val payload = root.opt("settings") as? JSONObject ?: error("缺少 settings 对象")
        val theme = string(payload, "theme")
        require(theme in listOf("system", "light", "dark")) { "主题应为 system、light 或 dark" }
        val language = string(payload, "language")
        require(language == "system" || language.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))) {
            "语言代码无效"
        }
        return AppSettings(
            simIntervalMs = integer(payload, "simIntervalMs", 200..2000).toLong(),
            gpsAccuracyM = number(payload, "gpsAccuracyM", 1.0..50.0),
            jitterEnabled = boolean(payload, "jitterEnabled"),
            jitterMeters = number(payload, "jitterMeters", 0.0..50.0),
            routeSpeedMps = number(payload, "routeSpeedMps", 0.1..30.0),
            stepCadenceSpm = integer(payload, "stepCadenceSpm", 0..3000),
            historyLimit = integer(payload, "historyLimit", 10..1000),
            screenKeepOn = boolean(payload, "screenKeepOn"),
            theme = theme,
            language = language,
            networkProviderToo = boolean(payload, "networkProviderToo"),
            altitudeM = number(payload, "altitudeM", -500.0..9000.0),
            satellites = integer(payload, "satellites", 0..64),
            sineSpeedCurve = boolean(payload, "sineSpeedCurve"),
        )
    }

    private fun number(json: JSONObject, key: String, range: ClosedFloatingPointRange<Double>): Double {
        val number = (json.opt(key) as? Number)?.toDouble() ?: error("$key 必须为数字")
        require(number.isFinite() && number in range) { "$key 超出允许范围" }
        return number
    }

    private fun integer(json: JSONObject, key: String, range: IntRange): Int {
        val number = number(json, key, range.first.toDouble()..range.last.toDouble())
        require(number == number.toInt().toDouble()) { "$key 必须为整数" }
        return number.toInt()
    }

    private fun boolean(json: JSONObject, key: String): Boolean =
        json.opt(key) as? Boolean ?: error("$key 必须为 true 或 false")

    private fun string(json: JSONObject, key: String): String =
        json.opt(key) as? String ?: error("$key 必须为文本")
}
