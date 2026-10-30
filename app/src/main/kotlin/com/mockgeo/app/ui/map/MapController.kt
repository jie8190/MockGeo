package com.mockgeo.app.ui.map

import android.content.Context
import android.view.View
import com.amap.api.maps.MapsInitializer
import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** All public coordinates are WGS84, irrespective of the renderer's coordinate system. */
data class MapStatus(
    val loading: Boolean = false,
    val firstFrameMs: Long? = null,
    val detail: String? = null,
)

sealed interface RouteMapEdit {
    data class Move(val index: Int, val position: LatLng) : RouteMapEdit
    data class Select(val index: Int) : RouteMapEdit
}

interface MapController {
    val view: View
    val providerId: String
    val status: StateFlow<MapStatus>
    val tapEvents: SharedFlow<LatLng>
    val longPressEvents: SharedFlow<LatLng>
    val gestureEvents: SharedFlow<Unit>
    val routeEdits: SharedFlow<RouteMapEdit>
    val cameraPosition: StateFlow<LatLng>

    fun moveTo(pos: LatLng, zoom: Double? = null, animate: Boolean = true)
    fun setMarker(pos: LatLng?, tag: String = "selection")
    fun addRouteOverlay(points: List<LatLng>)
    fun clearRouteOverlays()
    fun setRouteHandles(points: List<LatLng>, editing: Boolean)
    fun fitRoute(points: List<LatLng>)
    fun setFollowMode(enabled: Boolean)
    fun zoomIn()
    fun zoomOut()
    fun onResume()
    fun onPause()
    fun dispose()
    fun setStyle(satellite: Boolean, dark: Boolean)
    fun retry()
}

abstract class BaseMapController : MapController {
    protected val _status = MutableStateFlow(MapStatus(loading = true))
    override val status: StateFlow<MapStatus> = _status
    protected val _tapEvents = MutableSharedFlow<LatLng>(extraBufferCapacity = 8)
    override val tapEvents: SharedFlow<LatLng> = _tapEvents
    protected val _longPressEvents = MutableSharedFlow<LatLng>(extraBufferCapacity = 8)
    override val longPressEvents: SharedFlow<LatLng> = _longPressEvents
    protected val _gestureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val gestureEvents: SharedFlow<Unit> = _gestureEvents
    protected val _routeEdits = MutableSharedFlow<RouteMapEdit>(extraBufferCapacity = 16)
    override val routeEdits: SharedFlow<RouteMapEdit> = _routeEdits
    protected val _cameraPosition = MutableStateFlow(LatLng(39.9042, 116.4074))
    override val cameraPosition: StateFlow<LatLng> = _cameraPosition
    protected var following = false

    override fun setFollowMode(enabled: Boolean) { following = enabled }
    protected fun userGesture() {
        following = false
        _gestureEvents.tryEmit(Unit)
    }
}

/** Consent belongs to the UI; callers must obtain it before requesting the amap renderer. */
object MapControllerFactory {
    fun create(context: Context, provider: String): MapController = when (provider) {
        "amap" -> {
            // This branch is reached only after the UI's persisted amapConsent.
            // Do not move these calls to Application startup or the OSM branch.
            MapsInitializer.updatePrivacyShow(context.applicationContext, true, true)
            MapsInitializer.updatePrivacyAgree(context.applicationContext, true)
            AmapMapController(context)
        }
        else -> OsmdroidMapController(context)
    }
}
