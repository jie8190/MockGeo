package com.mockgeo.app.geo

import android.content.Context
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng as SdkLatLng
import com.mockgeo.core.model.LatLng
import java.util.LinkedHashMap

/**
 * AMap's own GPS conversion defines this boundary, including its coastal and
 * island coverage. There is no hand-drawn country mask and no dependency on the
 * SDK's coarser isAMapDataAvailable grid.
 *
 * Construction does not access location or initialize privacy state. Callers
 * obtain user consent before using the map/search SDK. Only applicationContext
 * is retained, and each actual conversion creates its own mutable SDK converter.
 *
 * The reverse direction solves the same official forward function. Where the
 * SDK returns an identity, that identity is preserved exactly. At a discontinuous
 * coverage edge, a failed inverse is reported instead of inventing a coordinate.
 */
class AmapCoordinates(context: Context) {
    private val appContext = requireNotNull(context.applicationContext) {
        "Application context is required for map coordinates"
    }
    private val cacheLock = Any()
    private val forwardCache = lru(256)
    private val inverseCache = lru(128)

    fun fromWgs(point: LatLng): LatLng = sdkForward(point)

    fun toWgs(point: LatLng): LatLng {
        synchronized(cacheLock) { inverseCache[point] }?.let {
            return if (it == point) point else it
        }
        val projectedInput = sdkForward(point)
        if (projectedInput == point) return rememberInverse(point, point)

        var estimate = point
        var projected = projectedInput
        var bestEstimate = estimate
        var bestResidual = Double.POSITIVE_INFINITY
        repeat(MAX_INVERSE_ITERATIONS) {
            val residual = projected.distanceTo(point)
            if (!residual.isFinite()) throw conversionFailure()
            if (residual < bestResidual) {
                bestResidual = residual
                bestEstimate = estimate
            }
            if (residual <= TARGET_RESIDUAL_M) return rememberInverse(point, estimate)

            val latitudeError = projected.lat - point.lat
            val longitudeError = LatLng.normalizeLng(projected.lng - point.lng)
            val nextLatitude = estimate.lat - latitudeError
            val nextLongitude = LatLng.normalizeLng(estimate.lng - longitudeError)
            if (nextLatitude !in -90.0..90.0 || !nextLongitude.isFinite()) throw conversionFailure()
            val next = LatLng(nextLatitude, nextLongitude)
            if (next == estimate) return@repeat
            estimate = next
            projected = sdkForward(estimate)
        }
        // Vendor conversion may quantize the result. Permit a small verified
        // residual, but never return an unconverged edge oscillation.
        val finalResidual = projected.distanceTo(point)
        if (finalResidual.isFinite() && finalResidual < bestResidual) {
            bestEstimate = estimate
            bestResidual = finalResidual
        }
        if (bestResidual <= MAX_RESIDUAL_M && sdkForward(bestEstimate).distanceTo(point) <= MAX_RESIDUAL_M) {
            return rememberInverse(point, bestEstimate)
        }
        throw conversionFailure()
    }

    private fun sdkForward(point: LatLng): LatLng {
        synchronized(cacheLock) { forwardCache[point] }?.let {
            return if (it == point) point else it
        }
        val converted = try {
            CoordinateConverter(appContext)
                .from(CoordinateConverter.CoordType.GPS)
                // The model already validates coordinate ranges. false prevents
                // the SDK LatLng constructor from wrapping +180 into -180.
                .coord(SdkLatLng(point.lat, point.lng, false))
                .convert()
        } catch (_: Exception) {
            throw conversionFailure()
        } ?: throw conversionFailure()
        if (converted.latitude !in -90.0..90.0 || converted.longitude !in -180.0..180.0) {
            throw conversionFailure()
        }
        val result = if (converted.latitude == point.lat && converted.longitude == point.lng) point
            else LatLng(converted.latitude, converted.longitude)
        synchronized(cacheLock) { forwardCache[point] = result }
        return result
    }

    private fun rememberInverse(input: LatLng, result: LatLng): LatLng {
        synchronized(cacheLock) { inverseCache[input] = result }
        return result
    }

    private fun conversionFailure() = IllegalStateException("高德坐标转换未能确认，请重试此位置")

    private fun lru(limit: Int) = object : LinkedHashMap<LatLng, LatLng>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LatLng, LatLng>?): Boolean = size > limit
    }

    companion object {
        private const val MAX_INVERSE_ITERATIONS = 12
        private const val TARGET_RESIDUAL_M = 0.05
        private const val MAX_RESIDUAL_M = 0.25
    }
}
