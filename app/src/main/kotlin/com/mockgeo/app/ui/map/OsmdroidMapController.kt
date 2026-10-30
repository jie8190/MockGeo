package com.mockgeo.app.ui.map

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.mockgeo.core.model.LatLng
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.hypot

class OsmdroidMapController(
    private val context: Context,
    private val mapView: MapView = createMapView(context),
) : BaseMapController() {
    override val providerId = "osm"
    override val view: View get() = mapView
    private val markers = mutableMapOf<String, Marker>()
    private val routeOverlays = mutableListOf<Polyline>()
    private val handles = mutableListOf<Marker>()
    private var handlePoints = emptyList<LatLng>()
    private var editingHandles = false
    private val cameraStore = MapCameraStore(context, providerId)
    private val handler = Handler(Looper.getMainLooper())
    private val saveCamera = Runnable { persistCamera() }
    private var loadStarted = SystemClock.elapsedRealtime()
    private var firstFrame: Long? = null
    private var darkStyle = false
    private var satelliteRequested = false
    private var disposed = false
    private var resumed = false
    private var draggingHandle = false
    private var touchActive = false
    private var lastFollowAt = 0L
    private var cameraCommand = 0L
    private val tileMonitor = Runnable { updateTileStatus() }
    private val slowCheck = object : Runnable {
        override fun run() {
            if (!disposed && resumed) {
                updateTileStatus()
                handler.postDelayed(this, 1_000)
            }
        }
    }

    init {
        // Density scaling must precede camera setup and the first tile request.
        mapView.setTilesScaledToDpi(true)
        if (mapView.tileProvider !is OsmTileProvider) mapView.setTileProvider(OsmTileProvider(context, osmTileSource()))
        mapView.setTileSource(osmTileSource())
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.setMinZoomLevel(2.0)
        mapView.setMaxZoomLevel(19.0)
        val initial = cameraStore.load(13.0)
        mapView.controller.setZoom(initial.zoom.coerceIn(2.0, 19.0))
        mapView.controller.setCenter(initial.position.geo())
        _cameraPosition.value = initial.position
        mapView.overlays.add(GestureOverlay())
        installTouchObserver()
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { cameraChanged(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { cameraChanged(); return false }
        })
        attachTileMonitor()
    }

    private fun cameraChanged() {
        if (disposed) return
        val center = mapView.mapCenter
        _cameraPosition.value = LatLng(center.latitude, center.longitude)
        cameraStore.remember(_cameraPosition.value, mapView.zoomLevelDouble)
        if (firstFrame != null) loadStarted = SystemClock.elapsedRealtime()
        handler.removeCallbacks(saveCamera)
        handler.postDelayed(saveCamera, 700)
    }

    private fun persistCamera() {
        if (disposed) return
        val center = mapView.mapCenter
        cameraStore.save(LatLng(center.latitude, center.longitude), mapView.zoomLevelDouble)
    }

    private fun attachTileMonitor() {
        mapView.overlayManager.tilesOverlay.tileStates.runAfters.add(tileMonitor)
        setStyle(satelliteRequested, darkStyle)
    }

    private fun updateTileStatus() {
        if (disposed) return
        val tiles = mapView.overlayManager.tilesOverlay.tileStates
        val available = tiles.upToDate + tiles.expired + tiles.scaled
        val elapsed = SystemClock.elapsedRealtime() - loadStarted
        if (available > 0 && firstFrame == null) firstFrame = elapsed
        val missing = tiles.total == 0 || tiles.notFound > 0
        val detail = when {
            satelliteRequested -> "开放地图提供标准底图；卫星图可切换到高德地图"
            missing && elapsed > 8_000 && available > 0 -> "部分底图仍在加载，已保留可用地图"
            missing && elapsed > 8_000 -> "地图加载较慢，可稍后重试"
            else -> null
        }
        _status.value = MapStatus(loading = missing, firstFrameMs = firstFrame, detail = detail)
    }

    private fun installTouchObserver() {
        var downX = 0f
        var downY = 0f
        var sent = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchActive = true; downX = event.x; downY = event.y; sent = false
                    cameraCommand++
                }
                MotionEvent.ACTION_UP -> touchActive = false
                MotionEvent.ACTION_CANCEL -> { touchActive = false; cancelHandleDrag() }
                MotionEvent.ACTION_POINTER_DOWN -> if (!sent) {
                    sent = true; userGesture(); mapView.controller.stopAnimation(false)
                }
                MotionEvent.ACTION_MOVE -> if (!sent && hypot(event.x - downX, event.y - downY) > slop) {
                    sent = true; userGesture(); mapView.controller.stopAnimation(false)
                }
            }
            false
        }
    }

    private inner class GestureOverlay : Overlay() {
        override fun onLongPress(event: MotionEvent?, map: MapView?): Boolean {
            if (event == null || map == null || draggingHandle) return false
            val point = map.projection.fromPixels(event.x.toInt(), event.y.toInt())
            _longPressEvents.tryEmit(LatLng(point.latitude, point.longitude))
            return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent?, map: MapView?): Boolean {
            if (event == null || map == null || draggingHandle) return false
            val point = map.projection.fromPixels(event.x.toInt(), event.y.toInt())
            _tapEvents.tryEmit(LatLng(point.latitude, point.longitude))
            return false
        }
    }

    override fun moveTo(pos: LatLng, zoom: Double?, animate: Boolean) {
        if (disposed || draggingHandle || touchActive) return
        cameraCommand++
        val now = SystemClock.elapsedRealtime()
        if (following && zoom == null) {
            if (now - lastFollowAt < 500) return
            lastFollowAt = now
        }
        val current = _cameraPosition.value
        if (current.distanceTo(pos) < 0.2 && zoom == null) return
        zoom?.let { mapView.controller.setZoom(it.coerceIn(2.0, 19.0)) }
        // Long jumps should not fetch all intermediate viewports. Follow updates
        // also avoid stacking a one-second animator every simulation tick.
        if (animate && !following && current.distanceTo(pos) < 5_000) {
            mapView.controller.animateTo(pos.geo(), null, 250L)
        } else mapView.controller.setCenter(pos.geo())
    }

    override fun setMarker(pos: LatLng?, tag: String) {
        if (disposed) return
        val old = markers[tag]
        if (pos == null) {
            old?.let { mapView.overlays.remove(it); it.onDetach(mapView) }
            markers.remove(tag)
        } else if (old != null) old.position = pos.geo()
        else {
            val marker = Marker(mapView).apply {
                position = pos.geo()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = BitmapDrawable(context.resources, MapMarkerIcons.pin(context, tag == "simulation"))
                title = if (tag == "simulation") "当前模拟位置" else "选中位置"
                setPanToView(false)
            }
            markers[tag] = marker
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    override fun addRouteOverlay(points: List<LatLng>) {
        if (disposed || points.size < 2) return
        val line = Polyline(mapView).apply {
            outlinePaint.color = Color.rgb(36, 107, 253)
            outlinePaint.strokeWidth = 3f * context.resources.displayMetrics.density
            outlinePaint.style = Paint.Style.STROKE
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.isAntiAlias = true
            setPoints(points.map { it.geo() })
        }
        mapView.overlays.add(0, line)
        routeOverlays += line
        mapView.invalidate()
    }

    override fun clearRouteOverlays() {
        routeOverlays.forEach { mapView.overlays.remove(it) }
        routeOverlays.clear()
        mapView.invalidate()
    }

    override fun setRouteHandles(points: List<LatLng>, editing: Boolean) {
        if (disposed) return
        if (points == handlePoints && editing == editingHandles) return
        handlePoints = points.toList()
        editingHandles = editing
        if (!draggingHandle) drawHandles()
    }

    private fun cancelHandleDrag() {
        if (!draggingHandle) return
        draggingHandle = false
        handler.post { if (!disposed && !draggingHandle) drawHandles() }
    }

    private fun drawHandles() {
        handles.forEach { mapView.overlays.remove(it); it.onDetach(mapView) }
        handles.clear()
        if (editingHandles) handlePoints.forEachIndexed { index, point ->
            val handle = Marker(mapView).apply {
                position = point.geo()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = BitmapDrawable(context.resources, MapMarkerIcons.routePoint(context, index + 1,
                    index == 0, index == handlePoints.lastIndex))
                isDraggable = true
                setPanToView(false)
                setOnMarkerClickListener { _, _ -> _routeEdits.tryEmit(RouteMapEdit.Select(index)); true }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {
                        if (disposed || marker !in handles) return
                        draggingHandle = true
                        cameraCommand++
                        userGesture()
                    }
                    override fun onMarkerDrag(marker: Marker) = Unit
                    override fun onMarkerDragEnd(marker: Marker) {
                        if (disposed || !draggingHandle || marker !in handles) return
                        draggingHandle = false
                        _routeEdits.tryEmit(RouteMapEdit.Move(index,
                            LatLng(marker.position.latitude, marker.position.longitude)))
                    }
                })
            }
            handles += handle
            mapView.overlays.add(handle)
        }
        mapView.invalidate()
    }

    override fun fitRoute(points: List<LatLng>) {
        if (disposed || points.isEmpty()) return
        userGesture()
        if (points.size == 1) { moveTo(points.first(), 15.0, false); return }
        val command = ++cameraCommand
        mapView.post {
            if (!disposed && command == cameraCommand) mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points.map { it.geo() }),
                false, (80 * context.resources.displayMetrics.density).toInt())
        }
    }

    override fun zoomIn() { if (!disposed) { cameraCommand++; mapView.controller.zoomIn() } }
    override fun zoomOut() { if (!disposed) { cameraCommand++; mapView.controller.zoomOut() } }
    override fun onResume() {
        if (disposed || resumed) return
        resumed = true
        mapView.onResume()
        handler.post(slowCheck)
    }
    override fun onPause() {
        if (disposed) return
        cameraCommand++
        mapView.controller.stopAnimation(false)
        persistCamera()
        resumed = false
        touchActive = false
        cancelHandleDrag()
        handler.removeCallbacks(slowCheck)
        handler.removeCallbacks(saveCamera)
        mapView.onPause()
    }
    override fun dispose() {
        if (disposed) return
        onPause()
        mapView.overlayManager.tilesOverlay.tileStates.runAfters.remove(tileMonitor)
        disposed = true
        handler.removeCallbacksAndMessages(null)
        mapView.onDetach()
        markers.clear(); handles.clear(); routeOverlays.clear()
    }
    override fun setStyle(satellite: Boolean, dark: Boolean) {
        satelliteRequested = satellite
        darkStyle = dark
        mapView.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        mapView.overlayManager.tilesOverlay.loadingBackgroundColor = if (dark) 0xff202630.toInt() else 0xffedf1f4.toInt()
        mapView.invalidate()
    }
    override fun retry() {
        if (disposed) return
        cameraCommand++
        touchActive = false
        cancelHandleDrag()
        // Recreate only download queues/backoff. The source key and SQL disk cache
        // stay intact; no bulk cache deletion or speculative fetch is performed.
        mapView.overlayManager.tilesOverlay.tileStates.runAfters.remove(tileMonitor)
        mapView.setTileProvider(OsmTileProvider(context, osmTileSource()))
        mapView.setTilesScaledToDpi(true)
        loadStarted = SystemClock.elapsedRealtime()
        _status.value = MapStatus(true, firstFrame)
        attachTileMonitor()
        mapView.invalidate()
    }

    private fun LatLng.geo() = GeoPoint(lat, lng)
    companion object {
        private fun createMapView(context: Context): MapView {
            Configuration.getInstance().apply {
                tileDownloadThreads = 2
                tileDownloadMaxQueueSize = 40
                tileFileSystemMaxQueueSize = 40
                cacheMapTileCount = 48
                cacheMapTileOvershoot = 16
            }
            return MapView(context, OsmTileProvider(context, osmTileSource()))
        }
    }
}
