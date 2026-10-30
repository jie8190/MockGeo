package com.mockgeo.module

import android.os.Binder
import android.os.IBinder
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Native Binder callbacks, with recipient identity retained for the whole subscription. */
internal object GnssPusher {
    class Prepared(val original: Any, val iface: Class<*>, val uid: Int, val nmea: Boolean, val key: IBinder) {
        val proxy: Any = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val isEvent = method.name in setOf("onGnssStarted", "onGnssStopped", "onStarted", "onStopped",
                "onFirstFix", "onSvStatusChanged", "onSatelliteStatusChanged", "onNmeaReceived")
            if (isEvent && LocationDelivery.config(uid) != null) null
            else Ipc.invoke { method.invoke(original, *(args ?: emptyArray())) }
        }
    }
    private class Entry(val target: Prepared) {
        var started = false
        var firstFix = false
        var failures = 0
        var death: IBinder.DeathRecipient? = null
        fun method(vararg names: String) = target.iface.methods.firstOrNull { it.name in names }
        val startMethod = method("onGnssStarted", "onStarted")
        val stopMethod = method("onGnssStopped", "onStopped")
        val fixMethod = method("onFirstFix")
        val statusMethod = method("onSvStatusChanged", "onSatelliteStatusChanged")
        val nmeaMethod = method("onNmeaReceived")
    }
    private val gnss = ConcurrentHashMap<IBinder, Entry>()
    private val nmea = ConcurrentHashMap<IBinder, Entry>()

    fun prepare(candidate: Any?, isNmea: Boolean, uid: Int): Prepared? {
        if (candidate == null) return null
        val iface = if (isNmea) Ipc.type(candidate, "android.location.IGnssNmeaListener", "android.location.INmeaListener", "android.location.IGnssStatusListener")
            else Ipc.type(candidate, "android.location.IGnssStatusListener")
        iface ?: return null
        val key = Ipc.binder(candidate) ?: return null
        return Prepared(candidate, iface, uid, isNmea, key)
    }
    fun register(target: Prepared) {
        val table = if (target.nmea) nmea else gnss
        val entry = Entry(target)
        if (target.nmea && entry.nmeaMethod == null || !target.nmea && entry.statusMethod == null) return
        table.put(target.key, entry)?.let(::unlink)
        val death = IBinder.DeathRecipient { table.remove(target.key, entry) }
        entry.death = death
        runCatching { target.key.linkToDeath(death, 0) }.onFailure { table.remove(target.key, entry) }
        ModuleLog.d("${if (target.nmea) "nmea" else "gnss"} listener registered (${table.size})")
    }
    fun unregister(candidate: Any?, isNmea: Boolean) {
        val key = candidate?.let(Ipc::binder) ?: return
        (if (isNmea) nmea else gnss).remove(key)?.let(::unlink)
    }
    private fun unlink(entry: Entry) { entry.death?.let { runCatching { entry.target.key.unlinkToDeath(it, 0) } } }

    /** Keep live subscriptions across STOP/START, but restart their synthetic event lifecycle. */
    fun onStopped() {
        gnss.values.forEach { entry -> synchronized(entry) { reset(entry) } }
    }
    private fun reset(entry: Entry) {
        if (entry.started) runCatching { entry.stopMethod?.invoke(entry.target.original) }
        entry.started = false; entry.firstFix = false; entry.failures = 0
    }

    fun pushOnce(ignored: ModuleConfig) {
        val identity = Binder.clearCallingIdentity()
        try {
            val statuses = mutableMapOf<Int, android.location.GnssStatus?>()
            for ((key, entry) in gnss) synchronized(entry) {
                if (gnss[key] !== entry) return@synchronized
                val cfg = LocationDelivery.config(entry.target.uid)
                if (cfg == null) { reset(entry); return@synchronized }
                val status = statuses.getOrPut(cfg.satellites) { GnssSynth.build(cfg.satellites) } ?: return@synchronized
                runCatching {
                    if (!entry.started) { entry.startMethod?.invoke(entry.target.original); entry.started = true }
                    if (!entry.firstFix && cfg.satellites > 0) { entry.fixMethod?.invoke(entry.target.original, 0); entry.firstFix = true }
                    if (LocationDelivery.config(entry.target.uid) != null && gnss[key] === entry)
                        entry.statusMethod!!.invoke(entry.target.original, status)
                    entry.failures = 0
                }.onFailure {
                    if (++entry.failures >= 5 && gnss.remove(key, entry)) unlink(entry)
                }
            }
            for ((key, entry) in nmea) {
                val cfg = LocationDelivery.config(entry.target.uid) ?: continue
                if (nmea[key] !== entry) continue
                val now = System.currentTimeMillis()
                runCatching {
                    entry.nmeaMethod!!.invoke(entry.target.original, now, FakeEvidence.nmeaGga(cfg, now))
                    if (LocationDelivery.config(entry.target.uid) != null && nmea[key] === entry)
                        entry.nmeaMethod.invoke(entry.target.original, now, FakeEvidence.nmeaRmc(cfg, now))
                    entry.failures = 0
                }.onFailure {
                    if (++entry.failures >= 5 && nmea.remove(key, entry)) unlink(entry)
                }
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
}
