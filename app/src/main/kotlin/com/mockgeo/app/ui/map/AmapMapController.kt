package com.mockgeo.app.ui.map

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng as AmapLatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.mockgeo.app.BuildConfig
import com.mockgeo.app.geo.AmapCoordinates
import com.mockgeo.app.geo.AmapKeyStore
import com.mockgeo.core.model.LatLng
import kotlin.math.hypot

/** Native AMap renderer. The public boundary remains WGS84, including marker drag events. */
class AmapMapController(private val context: Context) : BaseMapController() {
    override val providerId = "amap"
    private val container = FrameLayout(context)
    override val view: View get() = container
    private val handler = Handler(Looper.getMainLooper())
    private val saveCamera = Runnable { persistCamera() }
    private val cameraStore = MapCameraStore(context, providerId)
    private val coordinates = AmapCoordinates(context)
    private var nativeView: MapView? = null
    private var nativeMap: AMap? = null
    private var disposed = false
    private var resumed = false
    private var draggingHandle = false
    private var touchActive = false
    private var startedAt = SystemClock.elapsedRealtime()
    private var lastFollowAt = 0L
    private var cameraCommand = 0L
    private var wantedSatellite = false
    private var wantedDark = false
    private val markerPositions = linkedMapOf<String, LatLng>()
    private val markers = linkedMapOf<String, Marker>()
    private val routePoints = mutableListOf<List<LatLng>>()
    private val lines = mutableListOf<Polyline>()
    private val handles = mutableListOf<Marker>()
    private var handlePoints = emptyList<LatLng>()
    private var editingHandles = false
    private data class HandleTag(val index: Int)
    private val loadTimeout = Runnable {
        if (!disposed && _status.value.firstFrameMs == null) {
            _status.value = MapStatus(false, null, "高德地图暂未加载完成，请重试或切换开放地图")
        }
    }

    init {
        container.setBackgroundColor(0xffedf1f4.toInt())
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBranding() }
        initialize()
    }

    private fun initialize() {
        if (disposed) return
        startedAt = SystemClock.elapsedRealtime()
        _status.value = MapStatus(true)
        try {
            val apiKey = AmapKeyStore.effectiveApiKey(context)
            check(apiKey.isNotBlank()) { "Missing map key" }
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            MapsInitializer.setApiKey(apiKey)
            val mapView = MapView(context)
            nativeView = mapView
            mapView.onCreate(Bundle())
            container.removeAllViews()
            container.addView(mapView, FrameLayout.LayoutParams(-1, -1))
            val map = mapView.map
            nativeMap = map
            map.uiSettings.apply {
                isZoomControlsEnabled = false
                isCompassEnabled = false
                isScaleControlsEnabled = true
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
            map.setOnMapLoadedListener {
                if (isCurrent(map)) {
                    handler.removeCallbacks(loadTimeout)
                    _status.value = MapStatus(false, SystemClock.elapsedRealtime() - startedAt)
                    placeBranding()
                }
            }
            map.setOnMapClickListener { position ->
                if (isCurrent(map) && !draggingHandle) position.wgsOrNull()?.let { _tapEvents.tryEmit(it) }
            }
            map.setOnMapLongClickListener { position ->
                if (isCurrent(map) && !draggingHandle) position.wgsOrNull()?.let { _longPressEvents.tryEmit(it) }
            }
            map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                override fun onCameraChange(position: CameraPosition) {
                    if (!isCurrent(map)) return
                    _cameraPosition.value = position.target.wgsOrNull() ?: return
                    cameraStore.remember(_cameraPosition.value, position.zoom.toDouble())
                }
                override fun onCameraChangeFinish(position: CameraPosition) {
                    if (!isCurrent(map)) return
                    _cameraPosition.value = position.target.wgsOrNull() ?: return
                    cameraStore.remember(_cameraPosition.value, position.zoom.toDouble())
                    handler.removeCallbacks(saveCamera)
                    handler.postDelayed(saveCamera, 700)
                }
            })
            installTouchObserver(map)
            map.setOnMarkerClickListener { marker ->
                if (!isCurrent(map)) return@setOnMarkerClickListener true
                val handle = marker.getObject() as? HandleTag
                if (handle != null) _routeEdits.tryEmit(RouteMapEdit.Select(handle.index))
                true // The app's location card owns details; do not open SDK info windows.
            }
            map.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) {
                    if (!isCurrent(map) || marker !in handles) return
                    draggingHandle = true
                    cameraCommand++
                    userGesture()
                }
                override fun onMarkerDrag(marker: Marker) = Unit
                override fun onMarkerDragEnd(marker: Marker) {
                    if (!isCurrent(map) || !draggingHandle || marker !in handles) return
                    draggingHandle = false
                    val handle = marker.getObject() as? HandleTag ?: return
                    val point = marker.position.wgsOrNull()
                    if (point != null) _routeEdits.tryEmit(RouteMapEdit.Move(handle.index, point))
                    else handler.post { if (isCurrent(map)) drawHandles() }
                }
            })
            val initial = cameraStore.load(14.0)
            _cameraPosition.value = initial.position
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(initial.position.amap(), initial.zoom.toFloat()))
            setStyle(wantedSatellite, wantedDark)
            markerPositions.forEach { (tag, point) -> drawMarker(point, tag) }
            routePoints.forEach { drawRoute(it) }
            drawHandles()
            if (resumed) mapView.onResume()
            handler.removeCallbacks(loadTimeout)
            handler.postDelayed(loadTimeout, 15_000)
        } catch (error: Throwable) {
            // Do not expose the API key through exception text or a diagnostic log.
            android.util.Log.w("MockGeoMap", "AMap initialization failed: ${error.javaClass.simpleName}")
            releaseNativeView()
            markers.clear(); handles.clear(); lines.clear()
            container.removeAllViews()
            container.addView(TextView(context).apply {
                text = "地图暂时无法加载"
                textSize = 16f
                setTextColor(0xff64748b.toInt())
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            _status.value = MapStatus(false, null,
                if (!AmapKeyStore.hasAvailableKey(context)) "尚未配置高德地图 Key，可切换开放地图"
                else "高德地图初始化失败，可重试或切换开放地图")
        }
    }

    private fun installTouchObserver(map: AMap) {
        var downX = 0f
        var downY = 0f
        var emitted = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        map.setOnMapTouchListener { event ->
            if (!isCurrent(map)) return@setOnMapTouchListener
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchActive = true; downX = event.x; downY = event.y; emitted = false
                    cameraCommand++
                }
                MotionEvent.ACTION_UP -> touchActive = false
                MotionEvent.ACTION_CANCEL -> { touchActive = false; cancelHandleDrag() }
                MotionEvent.ACTION_POINTER_DOWN -> if (!emitted) { emitted = true; userGesture() }
                MotionEvent.ACTION_MOVE -> if (!emitted && hypot(event.x - downX, event.y - downY) > slop) {
                    emitted = true
                    userGesture()
                    map.stopAnimation()
                }
            }
        }
    }

    private fun isCurrent(map: AMap): Boolean = !disposed && nativeMap === map

    private fun cancelHandleDrag() {
        if (!draggingHandle) return
        draggingHandle = false
        handler.post { if (!disposed && !draggingHandle) drawHandles() }
    }

    private fun releaseNativeView() {
        // Invalidate callbacks before stopAnimation/onDestroy can synchronously or
        // asynchronously deliver a final camera update from the retiring renderer.
        cameraCommand++
        val oldMap = nativeMap
        val oldView = nativeView
        nativeMap = null
        nativeView = null
        runCatching { oldMap?.stopAnimation() }
        runCatching { oldView?.onPause() }
        runCatching { oldView?.onDestroy() }
    }

    private fun placeBranding() {
        val map = nativeMap ?: return
        if (container.height <= 0) return
        // Keep the official logo above the app's bottom control card, never hide it.
        val margin = (300 * context.resources.displayMetrics.density).toInt().coerceAtMost(container.height / 2)
        map.uiSettings.setLogoBottomMargin(margin)
    }

    private fun persistCamera() {
        if (disposed) return
        nativeMap?.cameraPosition?.let {
            val point = it.target.wgsOrNull() ?: return
            cameraStore.save(point, it.zoom.toDouble())
        }
    }

    override fun moveTo(pos: LatLng, zoom: Double?, animate: Boolean) {
        if (disposed || draggingHandle || touchActive) return
        val map = nativeMap ?: return
        cameraCommand++
        val now = SystemClock.elapsedRealtime()
        if (following && zoom == null) {
            if (now - lastFollowAt < 500) return
            lastFollowAt = now
        }
        val distance = _cameraPosition.value.distanceTo(pos)
        if (distance < 0.2 && zoom == null) return
        val update = if (zoom == null) CameraUpdateFactory.newLatLng(pos.amap())
            else CameraUpdateFactory.newLatLngZoom(pos.amap(), zoom.coerceIn(2.0, 20.0).toFloat())
        map.stopAnimation()
        if (animate && !following && distance < 5_000) map.animateCamera(update, 250L, null)
        else map.moveCamera(update)
    }

    override fun setMarker(pos: LatLng?, tag: String) {
        if (disposed) return
        if (pos == null) { markerPositions.remove(tag); markers.remove(tag)?.remove() }
        else { markerPositions[tag] = pos; drawMarker(pos, tag) }
    }

    private fun drawMarker(point: LatLng, tag: String) {
        val map = nativeMap ?: return
        val marker = markers[tag]
        if (marker != null) marker.position = point.amap()
        else map.addMarker(MarkerOptions().position(point.amap()).anchor(0.5f, 1f).zIndex(5f)
            .icon(BitmapDescriptorFactory.fromBitmap(MapMarkerIcons.pin(context, tag == "simulation"))))
            ?.let { markers[tag] = it }
    }

    override fun addRouteOverlay(points: List<LatLng>) {
        if (disposed || points.size < 2) return
        routePoints += points.toList()
        drawRoute(points)
    }
    private fun drawRoute(points: List<LatLng>) {
        nativeMap?.addPolyline(PolylineOptions().addAll(points.map { it.amap() })
            .color(Color.rgb(36, 107, 253)).width(4 * context.resources.displayMetrics.density).zIndex(2f))
            ?.let { lines += it }
    }
    override fun clearRouteOverlays() {
        lines.forEach { it.remove() }; lines.clear(); routePoints.clear()
    }
    override fun setRouteHandles(points: List<LatLng>, editing: Boolean) {
        if (disposed) return
        if (points == handlePoints && editing == editingHandles) return
        handlePoints = points.toList()
        editingHandles = editing
        if (!draggingHandle) drawHandles()
    }
    private fun drawHandles() {
        handles.forEach { it.remove() }; handles.clear()
        val map = nativeMap ?: return
        if (!editingHandles) return
        handlePoints.forEachIndexed { index, point ->
            val marker = map.addMarker(MarkerOptions().position(point.amap()).anchor(0.5f, 0.5f)
                .zIndex(10f).draggable(true).icon(BitmapDescriptorFactory.fromBitmap(
                    MapMarkerIcons.routePoint(context, index + 1, index == 0, index == handlePoints.lastIndex))))
            marker?.let { it.setObject(HandleTag(index)); handles += it }
        }
    }

    override fun fitRoute(points: List<LatLng>) {
        if (disposed || points.isEmpty()) return
        userGesture()
        val distinct = points.distinct()
        if (distinct.size == 1) { moveTo(distinct.first(), 15.0, false); return }
        val map = nativeMap ?: return
        val command = ++cameraCommand
        container.post {
            if (!isCurrent(map) || command != cameraCommand || container.width <= 0 || container.height <= 0) return@post
            val bounds = LatLngBounds.builder().apply { distinct.forEach { include(it.amap()) } }.build()
            val density = context.resources.displayMetrics.density
            val side = (32 * density).toInt()
            val top = (125 * density).toInt().coerceAtMost(container.height / 3)
            val bottom = (300 * density).toInt().coerceAtMost(container.height / 2)
            val update = CameraUpdateFactory.newLatLngBoundsRect(bounds, side, side, top, bottom)
            map.moveCamera(update)
        }
    }

    override fun zoomIn() { cameraCommand++; nativeMap?.animateCamera(CameraUpdateFactory.zoomIn()) }
    override fun zoomOut() { cameraCommand++; nativeMap?.animateCamera(CameraUpdateFactory.zoomOut()) }
    override fun setStyle(satellite: Boolean, dark: Boolean) {
        wantedSatellite = satellite; wantedDark = dark
        nativeMap?.mapType = when { satellite -> AMap.MAP_TYPE_SATELLITE; dark -> AMap.MAP_TYPE_NIGHT; else -> AMap.MAP_TYPE_NORMAL }
    }
    override fun onResume() {
        if (disposed || resumed) return
        resumed = true
        nativeView?.onResume()
    }
    override fun onPause() {
        if (disposed) return
        cameraCommand++
        handler.removeCallbacks(saveCamera)
        persistCamera()
        resumed = false
        touchActive = false
        cancelHandleDrag()
        nativeMap?.stopAnimation()
        nativeView?.onPause()
    }
    override fun dispose() {
        if (disposed) return
        onPause()
        disposed = true
        handler.removeCallbacksAndMessages(null)
        releaseNativeView()
        markers.clear(); handles.clear(); lines.clear()
        container.removeAllViews()
    }
    override fun retry() {
        if (disposed) return
        handler.removeCallbacks(saveCamera)
        persistCamera()
        handler.removeCallbacks(loadTimeout)
        touchActive = false
        draggingHandle = false
        lastFollowAt = 0L
        releaseNativeView()
        markers.clear(); handles.clear(); lines.clear()
        initialize()
    }

    private fun LatLng.amap(): AmapLatLng = coordinates.fromWgs(this).let { AmapLatLng(it.lat, it.lng, false) }

    private fun AmapLatLng.wgsOrNull(): LatLng? = try {
        coordinates.toWgs(LatLng(latitude, longitude))
    } catch (_: IllegalStateException) {
        // A discontinuous vendor coverage edge must not crash a camera callback
        // or be saved as an unverified WGS84 location.
        _status.value = _status.value.copy(detail = "此位置的地图坐标暂无法确认，请重试")
        null
    }
}
