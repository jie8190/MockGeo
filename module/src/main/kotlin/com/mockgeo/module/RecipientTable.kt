package com.mockgeo.module

import java.util.concurrent.ConcurrentHashMap

/** Subscription state survives OEM reconstruction of an interface from its Binder token. */
internal class RecipientTable<K : Any> {
    class Subscription(val uid: Int, val provider: String, maximum: Int) {
        val quota = CallbackQuota(maximum)
    }
    // Every publication has its own identity, even when a ROM reuses a Subscription.
    class Publication(val subscription: Subscription)
    data class Change<K>(val key: K, val value: Publication, val previous: Publication?)
    private val entries = ConcurrentHashMap<K, Map<String, Publication>>()
    fun publish(key: K, value: Subscription): Change<K> {
        val publication = Publication(value)
        var previous: Publication? = null
        entries.compute(key) { _, map ->
            previous = map?.get(value.provider)
            (map ?: emptyMap()) + (value.provider to publication)
        }
        return Change(key, publication, previous)
    }
    fun find(key: K, provider: String?): Subscription? {
        val map = entries[key] ?: return null
        return (provider?.let { map[it] } ?: map.values.singleOrNull())?.subscription
    }
    fun rollback(change: Change<K>) {
        val provider = change.value.subscription.provider
        entries.computeIfPresent(change.key) { _, map ->
            if (map[provider] !== change.value) map
            else if (change.previous == null) (map - provider).takeIf { it.isNotEmpty() }
            else map + (provider to change.previous)
        }
    }
    fun remove(key: K) { entries.remove(key) }
}
