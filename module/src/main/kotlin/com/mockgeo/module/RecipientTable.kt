package com.mockgeo.module

import java.util.concurrent.ConcurrentHashMap

/** Subscription state survives OEM reconstruction of an interface from its Binder token. */
internal class RecipientTable<K : Any> {
    class Subscription(val uid: Int, val provider: String, maximum: Int) {
        val quota = CallbackQuota(maximum)
    }
    data class Change<K>(val key: K, val value: Subscription, val previous: Subscription?)
    private val entries = ConcurrentHashMap<K, ConcurrentHashMap<String, Subscription>>()
    fun publish(key: K, value: Subscription): Change<K> {
        val map = entries.computeIfAbsent(key) { ConcurrentHashMap() }
        return Change(key, value, map.put(value.provider, value))
    }
    fun find(key: K, provider: String?): Subscription? {
        val map = entries[key] ?: return null
        return provider?.let { map[it] } ?: map.values.singleOrNull()
    }
    fun rollback(change: Change<K>) {
        val map = entries[change.key] ?: return
        if (change.previous == null) map.remove(change.value.provider, change.value)
        else map.replace(change.value.provider, change.value, change.previous)
    }
    fun remove(key: K) { entries.remove(key) }
}
