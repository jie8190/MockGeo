package com.mockgeo.module

/** Ancillary hooks must never make a partially installed location engine appear ready. */
internal object HookReadiness {
    private val required = linkedMapOf(
        "control" to setOf("sendExtraCommand"),
        "last" to setOf("getLastLocation", "getLastLocationWithPackage"),
        "current" to setOf("getCurrentLocation"),
        "subscribe" to setOf("registerLocationListener", "requestLocationUpdates", "requestLocationUpdatesListener"),
        "unsubscribe" to setOf("unregisterLocationListener", "removeUpdates"),
        "delivery" to setOf("onLocationChanged", "listenerTransport"),
    )

    fun missing(installed: Set<String>, apiLevel: Int = 30): List<String> = required.filter { (group, names) ->
        (group != "current" || apiLevel >= 30) && names.none { it in installed }
    }.keys.toList()
}
