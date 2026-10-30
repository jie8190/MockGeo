package com.mockgeo.core.route

import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.RoutePoint

/**
 * Immutable edits in displayed list order. Every result has contiguous seq values.
 *
 * A point's speed belongs to the edge arriving at that point, as in GPX time-derived
 * speeds. The first point has no incoming edge, so its speed is always null.
 * Elevation and route identity belong to the point itself and survive these edits.
 */
object EditableRoute {
    /** Reverse point order and transfer recorded speeds to the same geometric edges. */
    fun reverse(points: List<RoutePoint>): List<RoutePoint> = points.indices.map { newIndex ->
        val oldIndex = points.lastIndex - newIndex
        points[oldIndex].copy(
            seq = newIndex,
            speedMps = if (newIndex == 0) null else points[oldIndex + 1].speedMps,
        )
    }

    /** Delete one point. The newly joined edge has no reliable recorded speed. */
    fun removeAt(points: List<RoutePoint>, index: Int): List<RoutePoint> {
        require(index in points.indices) { "路线点不存在：$index" }
        return points.filterIndexed { i, _ -> i != index }.mapIndexed { newIndex, point ->
            point.copy(
                seq = newIndex,
                speedMps = if (newIndex == 0 || newIndex == index) null else point.speedMps,
            )
        }
    }

    /** Move a point on the map; invalidate only the two incident edges' recorded speeds. */
    fun movePoint(points: List<RoutePoint>, index: Int, position: LatLng): List<RoutePoint> {
        require(index in points.indices) { "路线点不存在：$index" }
        val original = points[index]
        val changed = original.lat != position.lat || original.lng != position.lng
        return points.mapIndexed { currentIndex, point ->
            point.copy(
                seq = currentIndex,
                lat = if (currentIndex == index) position.lat else point.lat,
                lng = if (currentIndex == index) position.lng else point.lng,
                speedMps = if (currentIndex == 0 ||
                    (changed && (currentIndex == index || currentIndex == index + 1))) null else point.speedMps,
            )
        }
    }
}
