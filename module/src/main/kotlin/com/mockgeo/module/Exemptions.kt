package com.mockgeo.module

import android.os.Binder
import android.os.SystemClock
import java.io.File

/** Root-managed policy; private app names are never logged. Shared UIDs are exempt together. */
internal object Exemptions {
    const val FILE = "/data/misc/mockgeo/exempt.txt"
    private const val CACHE_MS = 1_000L
    private var names: Set<String> = emptySet()
    private var checkedAt = -1L
    private val uids = mutableMapOf<Int, Pair<Long, Boolean>>()
    private val infrastructure = mutableMapOf<Int, Pair<Long, Boolean>>()
    private var warned = false

    @Synchronized fun list(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (checkedAt >= 0 && now - checkedAt in 0 until CACHE_MS) return names
        checkedAt = now
        try {
            val file = File(FILE)
            val fresh = if (!file.exists()) emptySet() else {
                require(file.length() <= 16_384) { "exemption file too large" }
                parse(file.readText())
            }
            if (fresh != names) { names = fresh; uids.clear() }
            warned = false
        } catch (error: Throwable) {
            // A bad edit or transient read failure must not silently revoke exemptions.
            if (!warned) { warned = true; ModuleLog.w("exemption policy unreadable; retaining previous policy") }
        }
        return names
    }

    fun parse(text: String): Set<String> {
        require(text.length <= 16_384)
        val result = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
        require(result.size <= 256 && result.all(::validPackage)) { "invalid exemption policy" }
        return result
    }
    fun validPackage(value: String): Boolean = value.length <= 255 && PACKAGE.matches(value)
    private val PACKAGE = Regex("[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)*")

    @Synchronized fun isUidExempt(uid: Int): Boolean {
        val policy = list()
        if (policy.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        uids[uid]?.takeIf { now - it.first in 0 until 5_000 }?.let { return it.second }
        val exempt = runCatching {
            val ctx = StatusWriter.systemContextOrNull() ?: return@runCatching true
            val packages = ctx.packageManager.getPackagesForUid(uid) ?: return@runCatching true
            packages.any { it in policy }
        }.getOrDefault(true)
        if (uids.size >= 256) uids.clear()
        uids[uid] = now to exempt
        return exempt
    }
    fun isCallerExempt() = isUidExempt(Binder.getCallingUid())
    @Synchronized fun isInfrastructureUid(uid: Int): Boolean {
        if (uid < 10_000) return true
        val now = SystemClock.elapsedRealtime()
        infrastructure[uid]?.takeIf { now - it.first in 0 until 5_000 }?.let { return it.second }
        val result = runCatching {
            StatusWriter.systemContextOrNull()?.checkPermission("android.permission.LOCATION_HARDWARE", -1, uid) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (infrastructure.size >= 256) infrastructure.clear()
        infrastructure[uid] = now to result
        return result
    }
    fun isExempt(packageName: String?) = packageName != null && packageName in list()
    @Synchronized fun invalidate() { checkedAt = -1; uids.clear() }
}
