package com.mockgeo.module

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock

/**
 * 假 Location 工厂。字段填法对齐 docs/08 §5 与 HLocation 的实测表（docs/03 §3）：
 * lat/lng/alt/accuracy/speed/bearing 来自配置快照；
 * time=currentTimeMillis、elapsedRealtimeNanos=SystemClock（单调）；
 * extras 写 satellites。
 *
 * Output format is explicit in the session configuration. The default preserves
 * Android's mock flag; optional compatibility testing changes only generated fixes.
 */
object FakeLocationFactory {

    fun create(provider: String, cfg: ModuleConfig): Location {
        val loc = Location(provider)
        fill(loc, cfg)
        return loc
    }

    /** 以真实 Location 为模板（保留 provider 等上下文），覆盖位置字段。 */
    fun fromReal(real: Location, cfg: ModuleConfig): Location {
        val loc = Location(real)
        fill(loc, cfg)
        return loc
    }

    private fun fill(loc: Location, cfg: ModuleConfig) {
        loc.latitude = cfg.lat
        loc.longitude = cfg.lng
        loc.altitude = cfg.altitude
        loc.accuracy = cfg.accuracyM
        loc.speed = cfg.speedMps
        loc.bearing = cfg.bearingDeg
        loc.verticalAccuracyMeters = 1.0f
        loc.speedAccuracyMetersPerSecond = 0.5f
        loc.bearingAccuracyDegrees = 1.0f
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        loc.setExtras(Bundle().apply { putInt("satellites", cfg.satellites) })
        markMock(loc, cfg.reportMock)
    }

    /** API 31+ setMock; older systems use their equivalent flag setter. */
    private fun markMock(loc: Location, marked: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                loc.setMock(marked)
            } else {
                val m = Location::class.java.getMethod("setIsFromMockProvider", Boolean::class.javaPrimitiveType)
                m.invoke(loc, marked)
            }
        } catch (t: Throwable) {
            // 标记失败不致命：位置仍然发出（字段本身已完整）
            ModuleLog.w("markMock failed", t)
        }
    }
}
