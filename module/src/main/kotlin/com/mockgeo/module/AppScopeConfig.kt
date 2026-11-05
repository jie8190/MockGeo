package com.mockgeo.module

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.mockgeo.core.model.ControlPlane

internal data class ScopeState(
    val enabled: Boolean, val hideMock: Boolean, val lat: Double, val lng: Double,
    val speed: Float, val satellites: Int, val atMs: Long,
    val elapsedMs: Long = -1, val bootCount: Int = -1, val session: Long = 0,
    val altitude: Double = 0.0, val bearing: Float = 0f,
    val exempt: Set<String> = emptySet(),
    val stepCadenceSpm: Int = 0,
) {
    /** 当前包是否应被注入（模拟开启且不在豁免名单）。 */
    fun activeFor(packageName: String?): Boolean =
        enabled && (packageName.isNullOrBlank() || packageName !in exempt)
    fun isFresh(nowElapsed: Long, currentBoot: Int): Boolean =
        bootCount >= 0 && currentBoot == bootCount && elapsedMs >= 0 &&
            nowElapsed - elapsedMs in 0 until ControlPlane.STATUS_FRESH_MS

    companion object { val DISABLED = ScopeState(false, false, 0.0, 0.0, 0f, 0, 0L) }
}

/** A persisted mirror is a lease, never an authority to keep injecting indefinitely. */
internal object AppScopeConfig {
    private const val CACHE_MS = 200L
    @Volatile private var cached: ScopeState? = null
    @Volatile private var cachedAt = -1L
    @Volatile private var boot = -1

    fun snapshot(context: Context?): ScopeState {
        val now = SystemClock.elapsedRealtime()
        val hit = cached
        if (hit != null && now - cachedAt in 0 until CACHE_MS) {
            return if (hit.isFresh(now, boot)) hit else ScopeState.DISABLED
        }
        val fresh = runCatching {
            val resolver = context?.contentResolver ?: return@runCatching null
            boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
            parse(Settings.Global.getString(resolver, ControlPlane.GLOBAL_STATE_KEY))
        }.getOrNull()
        cached = fresh
        cachedAt = now
        return fresh?.takeIf { it.isFresh(now, boot) } ?: ScopeState.DISABLED
    }

    /** Strict numeric-only wire format; incomplete/old/malformed mirrors fail closed. */
    fun parse(json: String?): ScopeState? = runCatching {
        val text = json?.trim() ?: return null
        if (!text.startsWith('{') || !text.endsWith('}')) return null
        val fields = mutableMapOf<String, Double>()
        var exempt: Set<String> = emptySet()
        val body = text.substring(1, text.length - 1)
        var numericPart = body
        val exemptions = X_FIELD.findAll(body).toList()
        if (exemptions.size > 1) return null
        exemptions.singleOrNull()?.let { match ->
            exempt = match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (exempt.size > 256 || exempt.any { !Exemptions.validPackage(it) }) return null
            val before = body.substring(0, match.range.first).trimEnd()
            val after = body.substring(match.range.last + 1).trimStart()
            if (before.isNotEmpty() && !before.endsWith(',')) return null
            if (after.isNotEmpty() && !after.startsWith(',')) return null
            numericPart = if (before.isEmpty()) after.removePrefix(",")
                else if (after.isEmpty()) before.removeSuffix(",") else before + after.removePrefix(",")
        }
        for (part in numericPart.split(',')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return null
            val match = FIELD.matchEntire(trimmed) ?: return null
            val value = match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            if (fields.put(match.groupValues[1], value) != null) return null
        }
        if (fields.keys != KEYS && fields.keys != KEYS + "cad") return null
        val cadence = fields["cad"] ?: 0.0
        if (cadence !in 0.0..300.0 || cadence % 1.0 != 0.0) return null
        fun n(key: String) = fields.getValue(key)
        fun integer(key: String) = n(key) % 1.0 == 0.0
        if (n("e") !in listOf(0.0, 1.0) || n("h") !in listOf(0.0, 1.0) ||
            n("lat") !in -90.0..90.0 || n("lng") !in -180.0..180.0 ||
            n("spd") !in 0.0..Float.MAX_VALUE.toDouble() || n("sat") !in 0.0..64.0 ||
            n("brg") !in 0.0..360.0 || n("boot") !in 0.0..Int.MAX_VALUE.toDouble() ||
            listOf("ts", "et", "session").any { n(it) < 0 || n(it) > 9_007_199_254_740_991.0 } ||
            listOf("sat", "boot", "et", "ts", "session").any { !integer(it) }) return null
        ScopeState(n("e") == 1.0, n("h") == 1.0, n("lat"), n("lng"), n("spd").toFloat(),
            n("sat").toInt(), n("ts").toLong(), n("et").toLong(), n("boot").toInt(),
            n("session").toLong(), n("alt"), n("brg").toFloat(), exempt, cadence.toInt())
    }.getOrNull()

    private val X_FIELD = Regex("\"x\"\\s*:\\s*\"([^\"]*)\"")
    private val FIELD = Regex("\"([a-z]+)\"\\s*:\\s*([-+]?(?:[0-9]+(?:\\.[0-9]+)?)(?:[eE][-+]?[0-9]+)?)")
    private val KEYS = setOf("e", "h", "lat", "lng", "spd", "sat", "ts", "et", "boot", "session", "alt", "brg")
    fun invalidate() { cached = null; cachedAt = -1 }
}
