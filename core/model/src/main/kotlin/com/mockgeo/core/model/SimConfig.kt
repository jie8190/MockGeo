package com.mockgeo.core.model

/**
 * 一次模拟的全部参数（不可变快照）。
 * 速度统一 m/s（UI 显示 km/h 时换算）；bearing 单位度；accuracy 单位米。
 */
data class SimConfig(
    val position: LatLng,
    val altitude: Double = 0.0,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val accuracyM: Float = 5f,
    val speedAccuracyMps: Float = 0.5f,
    val bearingAccuracyDeg: Float = 1.0f,
    val verticalAccuracyM: Float = 1.0f,
    val satellites: Int = 12,
    val jitterEnabled: Boolean = false,
    val jitterMeters: Double = 0.0,
    /** 是否同时向 network provider 报告（仅 ProviderEngine 支持）。 */
    val networkProviderToo: Boolean = false,
    /** LSPosed output format only; official test providers always retain Android's mock flag. */
    val reportMock: Boolean = true,
) {
    init {
        require(accuracyM > 0f) { "accuracy must be > 0" }
        require(satellites in 0..64) { "satellites out of range: $satellites" }
        require(jitterMeters >= 0.0) { "jitterMeters must be >= 0" }
        require(speedMps >= 0f) { "speed must be >= 0" }
    }

    fun withPosition(position: LatLng, bearingDeg: Float? = null): SimConfig = copy(
        position = position,
        bearingDeg = bearingDeg ?: this.bearingDeg,
    )

    companion object {
        /** 抖动半径上限，防止用户输入把坐标抖出合理范围。 */
        const val MAX_JITTER_METERS = 50.0
    }
}
