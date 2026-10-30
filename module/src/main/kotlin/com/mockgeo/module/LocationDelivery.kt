package com.mockgeo.module

import android.location.Location
import android.os.Bundle
import android.os.IBinder
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Rewrites per-recipient copies, never provider caches shared by exempt consumers. */
internal object LocationDelivery {
    private val recipients = RecipientTable<IBinder>()
    private val deaths = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()
    private val delivering = ThreadLocal.withInitial { mutableSetOf<IBinder>() }
    private val directProxyCalls = AtomicInteger()
    fun config(uid: Int): ModuleConfig? = ModuleConfig.snapshot().takeIf {
        it.enabled && !Exemptions.isInfrastructureUid(uid) && !Exemptions.isUidExempt(uid)
    }

    fun rewrite(value: Any?, provider: String?, cfg: ModuleConfig): Any? = when (value) {
        is Location -> FakeLocationFactory.fromReal(value, cfg).apply {
            if (provider != null && provider != ProviderResolver.PASSIVE) this.provider = provider
        }
        is List<*> -> value.map { rewrite(it, provider, cfg) }
        else -> if (value?.javaClass?.name == "android.location.LocationResult") {
            result(locations(value).map { rewrite(it, provider, cfg) as Location }) ?: value
        } else value
    }
    fun locations(value: Any?): List<Location> = when (value) {
        is Location -> listOf(value)
        is List<*> -> value.filterIsInstance<Location>()
        else -> if (value?.javaClass?.name == "android.location.LocationResult")
            (value.javaClass.getMethod("asList").invoke(value) as List<*>).filterIsInstance<Location>() else emptyList()
    }
    fun result(locations: List<Location>): Any? = runCatching {
        val cls = Class.forName("android.location.LocationResult")
        val create = cls.methods.first { it.name in setOf("create", "wrap") &&
            it.parameterTypes.contentEquals(arrayOf(List::class.java)) }
        create.invoke(null, locations)
    }.getOrNull()

    fun listener(candidate: Any?, provider: String, uid: Int, maxUpdates: Int): Any? {
        val iface = Ipc.type(candidate, "android.location.ILocationListener") ?: return null
        val key = candidate?.let(Ipc::binder) ?: return null
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), ListenerHandler(candidate, key,
            RecipientTable.Subscription(uid, provider, maxUpdates)))
    }

    private class ListenerHandler(val original: Any, val key: IBinder, val prepared: RecipientTable.Subscription) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, input: Array<out Any?>?): Any? {
            val args: Array<Any?> = input?.map { it }?.toTypedArray() ?: emptyArray()
            return if (method.name == "onLocationChanged") {
                val subscription = recipients.find(key, prepared.provider) ?: prepared
                deliver(key, subscription, args) { next -> Ipc.invoke { method.invoke(original, *next) } }
            } else Ipc.invoke { method.invoke(original, *args) }
        }
    }

    /** Called at the provider manager boundary, after LMS permission/identity validation. */
    fun registerValidated(candidate: Any?, provider: String, uid: Int, maximum: Int): RecipientTable.Change<IBinder>? {
        if (Ipc.type(candidate, "android.location.ILocationListener") == null || candidate == null) return null
        val key = Ipc.binder(candidate) ?: return null
        val handler = if (Proxy.isProxyClass(candidate.javaClass)) Proxy.getInvocationHandler(candidate) as? ListenerHandler else null
        val subscription = handler?.prepared ?: recipients.find(key, provider)?.takeIf { it.uid == uid }
            ?: RecipientTable.Subscription(uid, provider, maximum)
        val change = recipients.publish(key, subscription)
        if (!deaths.containsKey(key)) {
            val death = IBinder.DeathRecipient { recipients.remove(key); deaths.remove(key) }
            if (deaths.putIfAbsent(key, death) == null) runCatching { key.linkToDeath(death, 0) }
                .onFailure { recipients.remove(key); deaths.remove(key, death) }
        }
        return change
    }
    fun rollback(change: RecipientTable.Change<IBinder>?) { if (change != null) recipients.rollback(change) }
    fun unregister(candidate: Any?) {
        val key = candidate?.let(Ipc::binder) ?: return
        recipients.remove(key)
        deaths.remove(key)?.let { runCatching { key.unlinkToDeath(it, 0) } }
    }
    fun outgoing(receiver: Any?, args: Array<Any?>, forward: (Array<Any?>) -> Any?): Any? {
        val key = receiver?.let(Ipc::binder) ?: return forward(args)
        val provider = locations(args.firstOrNull()).firstOrNull()?.provider
        val subscription = recipients.find(key, provider) ?: return forward(args)
        if (delivering.get()?.contains(key) != true && directProxyCalls.incrementAndGet() == 1) {
            ModuleLog.d("recipient registry handled a reconstructed Binder callback")
        }
        return deliver(key, subscription, args, forward)
    }
    private fun deliver(key: IBinder, subscription: RecipientTable.Subscription, args: Array<Any?>,
                        forward: (Array<Any?>) -> Any?): Any? {
        if (args.isEmpty()) return forward(args)
        val active = delivering.get() ?: return forward(args)
        if (!active.add(key)) return forward(args) // wrapper -> Binder proxy is one delivery
        try {
            val old = args[0]
            val fixes = locations(old)
            val accepted = subscription.quota.claim(fixes.size)
            if (fixes.isNotEmpty() && accepted == 0) { Ipc.acknowledge(args.getOrNull(1)); return null }
            val next = args.copyOf()
            if (accepted < fixes.size) next[0] = when (old) {
                is List<*> -> fixes.take(accepted)
                is Location -> old
                else -> result(fixes.take(accepted)) ?: old
            }
            config(subscription.uid)?.let { next[0] = rewrite(next[0], subscription.provider, it) }
            return forward(next)
        } finally { active.remove(key) }
    }
}

internal class CallbackQuota(private val maximum: Int) {
    private val delivered = AtomicInteger()
    fun claim(wanted: Int): Int {
        if (wanted <= 0) return 0
        while (true) {
            val old = delivered.get()
            val count = minOf(wanted, (maximum - old).coerceAtLeast(0))
            if (count == 0 || delivered.compareAndSet(old, old + count)) return count
        }
    }
}

internal object Ipc {
    fun type(candidate: Any?, vararg names: String): Class<*>? = names.firstNotNullOfOrNull { name ->
        runCatching { Class.forName(name).takeIf { it.isInstance(candidate) } }.getOrNull()
    }
    fun binder(candidate: Any): android.os.IBinder? = when (candidate) {
        is android.os.IBinder -> candidate
        is android.os.IInterface -> candidate.asBinder()
        else -> null
    }
    fun invoke(call: () -> Any?): Any? = try { call() }
        catch (error: InvocationTargetException) { throw error.targetException }
    fun acknowledge(callback: Any?) {
        if (callback == null) return
        runCatching { Class.forName("android.os.IRemoteCallback")
            .getMethod("sendResult", Bundle::class.java).invoke(callback, null) }
    }
}
