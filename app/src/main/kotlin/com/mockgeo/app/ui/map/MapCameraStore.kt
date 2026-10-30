package com.mockgeo.app.ui.map

import android.content.Context
import com.mockgeo.core.model.LatLng
import java.util.concurrent.ConcurrentHashMap

/** Persists a camera snapshot, not a mock location. No Activity or map View is retained. */
internal class MapCameraStore(context: Context, private val provider: String) {
    data class Camera(val position: LatLng, val zoom: Double)
    private val preferences = context.applicationContext.getSharedPreferences("map_camera_v2", Context.MODE_PRIVATE)

    fun load(defaultZoom: Double): Camera {
        val livePosition = latestPosition
        val lat = livePosition?.lat ?: preferences.getString("last_lat", null)?.toDoubleOrNull()
            ?: preferences.getString("${provider}_lat", null)?.toDoubleOrNull() ?: 39.9042
        val lng = livePosition?.lng ?: preferences.getString("last_lng", null)?.toDoubleOrNull()
            ?: preferences.getString("${provider}_lng", null)?.toDoubleOrNull() ?: 116.4074
        val zoom = (latestZoom[provider] ?: preferences.getString("${provider}_zoom", null)?.toDoubleOrNull())
            ?.takeIf { it.isFinite() && it in 2.0..20.0 } ?: defaultZoom
        val position = runCatching { LatLng(lat, lng) }.getOrDefault(LatLng(39.9042, 116.4074))
        return Camera(position, zoom)
    }

    fun remember(position: LatLng, zoom: Double) {
        latestPosition = position
        if (zoom.isFinite()) latestZoom[provider] = zoom
    }

    fun save(position: LatLng, zoom: Double) {
        if (!zoom.isFinite()) return
        remember(position, zoom)
        preferences.edit()
            .putString("${provider}_lat", position.lat.toString())
            .putString("${provider}_lng", position.lng.toString())
            .putString("${provider}_zoom", zoom.toString())
            .putString("last_lat", position.lat.toString())
            .putString("last_lng", position.lng.toString())
            .apply()
    }

    companion object {
        // A provider switch creates the new view before Compose disposes the old
        // one. The live snapshot prevents that ordering from restoring stale disk state.
        @Volatile private var latestPosition: LatLng? = null
        private val latestZoom = ConcurrentHashMap<String, Double>()
    }
}
