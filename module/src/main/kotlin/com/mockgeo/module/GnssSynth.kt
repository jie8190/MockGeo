package com.mockgeo.module

import android.location.GnssStatus
import android.os.Build

/** One satellite model shared by status objects and their public getters. */
internal object GnssSynth {
    data class Satellite(val constellation: Int, val svid: Int, val cn0: Float,
        val elevation: Float, val azimuth: Float, val carrierHz: Float)

    fun satellites(count: Int): List<Satellite> = List(count.coerceIn(0, 64)) { index ->
        Satellite(if (index < 32) 1 else 5, index % 32 + 1, 30f + index % 10,
            (15 + index * 11 % 60).toFloat(), (index * 37 % 360).toFloat(),
            if (index < 32) 1_575_420_000f else 1_561_098_000f)
    }

    @Volatile private var errorLogged = false
    fun build(state: ScopeState): GnssStatus? = build(state.satellites)
    fun build(count: Int): GnssStatus? = runCatching {
        val sats = satellites(count)
        if (Build.VERSION.SDK_INT >= 30) {
            // Public API since 30: twelve parameters, not the seven in 0.3.7.
            GnssStatus.Builder().apply {
                sats.forEach { s -> addSatellite(s.constellation, s.svid, s.cn0, s.elevation,
                    s.azimuth, true, true, true, true, s.carrierHz, true, s.cn0 - 2f) }
            }.build()
        } else legacy(sats)
    }.getOrElse {
        if (!errorLogged) { errorLogged = true; ModuleLog.w("GNSS construction unavailable", it) }
        null
    }

    /** Legacy constructor uses packed SVID/constellation/flags, not separate int arrays. */
    private fun legacy(sats: List<Satellite>): GnssStatus {
        val cls = GnssStatus::class.java
        fun constant(name: String): Int = cls.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
        val svidShift = constant("SVID_SHIFT_WIDTH")
        val constellationShift = constant("CONSTELLATION_TYPE_SHIFT_WIDTH")
        val flags = constant("GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA") or
            constant("GNSS_SV_FLAGS_HAS_ALMANAC_DATA") or constant("GNSS_SV_FLAGS_USED_IN_FIX") or
            constant("GNSS_SV_FLAGS_HAS_CARRIER_FREQUENCY")
        val constructor = cls.declaredConstructors.first { c ->
            val p = c.parameterTypes
            p.size in 6..7 && p[0] == Int::class.javaPrimitiveType && p[1] == IntArray::class.java &&
                p.drop(2).all { it == FloatArray::class.java }
        }.apply { isAccessible = true }
        val args = mutableListOf<Any>(sats.size,
            IntArray(sats.size) { (sats[it].svid shl svidShift) or (sats[it].constellation shl constellationShift) or flags },
            sats.map { it.cn0 }.toFloatArray(), sats.map { it.elevation }.toFloatArray(),
            sats.map { it.azimuth }.toFloatArray(), sats.map { it.carrierHz }.toFloatArray())
        if (constructor.parameterCount == 7) args.add(sats.map { it.cn0 - 2f }.toFloatArray())
        return constructor.newInstance(*args.toTypedArray()) as GnssStatus
    }
}
