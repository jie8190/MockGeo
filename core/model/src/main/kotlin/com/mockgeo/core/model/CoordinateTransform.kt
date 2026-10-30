package com.mockgeo.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 / GCJ-02 conversion for the map boundary. Stored and simulated positions
 * remain WGS84; convert only when sending coordinates to or reading them from a
 * map that explicitly uses GCJ-02.
 *
 * The hand-drawn mainland and Hainan polygons below are conservative conversion
 * coverage, NOT authoritative national boundaries. They deliberately omit some
 * border/coastal locations rather than applying the mainland offset to nearby
 * countries. Outside coverage both directions return the original point exactly.
 * This is substantially narrower than the common 72..138 / 0..56 rectangle, which
 * incorrectly shifts Korea, Japan, Vietnam, Nepal, Mongolia and eastern Russia.
 *
 * Interior covered positions use the ordinary GCJ transform and an iterative
 * inverse. Within roughly one offset of the approximate coverage edge, an
 * offshore GCJ coordinate is inherently ambiguous with an untouched WGS point;
 * this helper favors leaving it untouched. Boundary-sensitive applications need
 * an authoritative coverage dataset or the map provider's own coordinate API.
 */
object CoordinateTransform {
    private const val AXIS = 6_378_245.0
    private const val ECCENTRICITY_SQUARED = 0.00669342162296594323
    private const val INVERSE_TOLERANCE_DEGREES = 1e-9
    private const val EDGE_EPSILON = 1e-10

    fun wgsToGcj(point: LatLng): LatLng =
        if (inCoverage(point)) forwardUnchecked(point) else point

    fun gcjToWgs(point: LatLng): LatLng {
        if (!inCoverage(point)) return point
        var estimate = point
        repeat(12) {
            // Do not switch the coverage test inside the solver: doing so can
            // oscillate across a boundary instead of converging to a position.
            val projected = forwardUnchecked(estimate)
            val latitudeError = projected.lat - point.lat
            val longitudeError = projected.lng - point.lng
            if (abs(latitudeError) <= INVERSE_TOLERANCE_DEGREES &&
                abs(longitudeError) <= INVERSE_TOLERANCE_DEGREES
            ) return if (inCoverage(estimate)) estimate else point
            estimate = LatLng(estimate.lat - latitudeError, estimate.lng - longitudeError)
        }
        // Reject an ambiguous boundary result or a failed inverse rather than
        // silently returning a coordinate from a different coverage region.
        return if (inCoverage(estimate) && forwardUnchecked(estimate).distanceTo(point) < 0.05) {
            estimate
        } else point
    }

    private fun forwardUnchecked(point: LatLng): LatLng {
        val x = point.lng - 105.0
        val y = point.lat - 35.0
        val latitudeRadians = point.lat * PI / 180.0
        val latitudeSin = sin(latitudeRadians)
        val magic = 1.0 - ECCENTRICITY_SQUARED * latitudeSin * latitudeSin
        val root = sqrt(magic)
        val latitudeOffset = latitudeWave(x, y) * 180.0 /
            (AXIS * (1.0 - ECCENTRICITY_SQUARED) / (magic * root) * PI)
        val longitudeOffset = longitudeWave(x, y) * 180.0 /
            (AXIS / root * cos(latitudeRadians) * PI)
        return LatLng(point.lat + latitudeOffset, point.lng + longitudeOffset)
    }

    private fun latitudeWave(x: Double, y: Double): Double =
        -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x)) +
            (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
            (20.0 * sin(y * PI) + 40.0 * sin(y * PI / 3.0)) * 2.0 / 3.0 +
            (160.0 * sin(y * PI / 12.0) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0

    private fun longitudeWave(x: Double, y: Double): Double =
        300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x)) +
            (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
            (20.0 * sin(x * PI) + 40.0 * sin(x * PI / 3.0)) * 2.0 / 3.0 +
            (150.0 * sin(x * PI / 12.0) + 300.0 * sin(x * PI / 30.0)) * 2.0 / 3.0

    private fun inCoverage(point: LatLng): Boolean {
        // Fast rejection only; this rectangle is never the conversion decision.
        if (point.lng !in 75.0..134.0 || point.lat !in 18.0..53.0) return false
        return insidePolygon(point, mainland) || insidePolygon(point, hainan)
    }

    private fun insidePolygon(point: LatLng, polygon: Array<LatLng>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val cross = (point.lng - previous.lng) * (current.lat - previous.lat) -
                (point.lat - previous.lat) * (current.lng - previous.lng)
            if (abs(cross) <= EDGE_EPSILON &&
                point.lng >= minOf(previous.lng, current.lng) - EDGE_EPSILON &&
                point.lng <= maxOf(previous.lng, current.lng) + EDGE_EPSILON &&
                point.lat >= minOf(previous.lat, current.lat) - EDGE_EPSILON &&
                point.lat <= maxOf(previous.lat, current.lat) + EDGE_EPSILON
            ) return false // Boundary points are conservatively left untouched.
            if ((current.lat > point.lat) != (previous.lat > point.lat)) {
                val crossing = (previous.lng - current.lng) * (point.lat - current.lat) /
                    (previous.lat - current.lat) + current.lng
                if (point.lng < crossing) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun vertex(longitude: Double, latitude: Double) = LatLng(latitude, longitude)

    // An intentionally inset, independently drawn coverage outline. In particular,
    // the northeast follows the mainland coast instead of enclosing the Koreas;
    // the southwest stays north/east of Nepal, Bhutan, India, Myanmar and Vietnam.
    private val mainland = arrayOf(
        vertex(75.2, 39.3), vertex(76.4, 40.3), vertex(79.9, 41.5),
        vertex(80.8, 43.0), vertex(81.0, 44.3), vertex(82.5, 44.7),
        vertex(83.6, 45.5), vertex(84.0, 46.0), vertex(86.0, 47.7),
        vertex(88.2, 48.3), vertex(89.5, 47.2), vertex(90.8, 45.0),
        vertex(93.8, 43.0), vertex(96.5, 42.5), vertex(99.0, 42.0),
        vertex(102.0, 42.0), vertex(105.0, 41.5), vertex(108.0, 41.8),
        vertex(110.5, 42.3), vertex(112.2, 43.1), vertex(114.0, 43.5),
        vertex(115.5, 45.0), vertex(117.2, 46.3), vertex(118.7, 47.4),
        vertex(118.8, 49.0), vertex(120.0, 49.8), vertex(120.2, 51.2),
        vertex(121.8, 52.0), vertex(123.2, 52.2), vertex(125.0, 52.3),
        vertex(126.0, 51.0), vertex(126.4, 50.3), vertex(127.1, 49.7),
        vertex(129.2, 49.1), vertex(130.5, 48.4), vertex(133.5, 48.2),
        vertex(133.3, 47.0), vertex(132.5, 46.0), vertex(131.2, 45.1),
        vertex(130.2, 44.6), vertex(130.1, 43.3), vertex(129.3, 42.5),
        vertex(128.1, 42.0), vertex(126.5, 41.7), vertex(125.5, 40.9),
        vertex(124.3, 40.2), vertex(122.7, 39.8), vertex(122.0, 39.0),
        vertex(121.4, 38.8), vertex(120.5, 39.1), vertex(119.0, 39.0),
        vertex(118.0, 38.4), vertex(118.0, 38.0), vertex(120.0, 37.8),
        vertex(122.2, 37.6), vertex(122.5, 37.1), vertex(121.8, 36.8),
        vertex(120.4, 35.9), vertex(119.6, 35.1), vertex(119.2, 34.5),
        vertex(120.0, 33.5), vertex(121.0, 32.5), vertex(121.8, 31.5),
        vertex(121.6, 30.0), vertex(121.2, 28.3), vertex(120.1, 26.5),
        vertex(119.4, 25.5), vertex(119.0, 25.1), vertex(118.8, 24.7),
        vertex(118.2, 24.4), vertex(117.4, 23.8), vertex(116.5, 23.2),
        vertex(115.0, 22.8), vertex(114.6, 22.6), vertex(114.1, 22.5),
        vertex(113.75, 22.5), vertex(113.5, 22.1), vertex(112.0, 21.7),
        vertex(111.0, 21.4), vertex(110.4, 20.4), vertex(110.1, 20.4),
        vertex(109.6, 21.4), vertex(108.4, 21.65), vertex(108.3, 21.7),
        vertex(108.0, 22.1), vertex(107.0, 22.4), vertex(106.2, 22.9),
        vertex(105.3, 23.2), vertex(104.5, 23.3), vertex(103.5, 23.0),
        vertex(102.5, 22.7), vertex(101.8, 22.1), vertex(101.0, 22.3),
        vertex(100.0, 23.0), vertex(99.2, 24.0), vertex(98.8, 25.5),
        vertex(98.3, 27.7), vertex(97.0, 29.0), vertex(95.0, 29.8),
        vertex(93.0, 29.0), vertex(91.0, 28.5), vertex(88.5, 28.5),
        vertex(86.0, 29.5), vertex(84.0, 30.5), vertex(82.2, 31.0),
        vertex(81.0, 32.0), vertex(80.0, 35.5), vertex(78.5, 36.5),
        vertex(76.0, 37.0),
    )

    private val hainan = arrayOf(
        vertex(108.65, 19.0), vertex(108.85, 19.45), vertex(109.2, 19.8),
        vertex(109.75, 20.03), vertex(110.25, 20.10), vertex(110.65, 20.02),
        vertex(111.0, 19.65), vertex(110.95, 19.2), vertex(110.7, 18.75),
        vertex(110.15, 18.45), vertex(109.6, 18.20), vertex(109.1, 18.35),
        vertex(108.75, 18.65),
    )
}
