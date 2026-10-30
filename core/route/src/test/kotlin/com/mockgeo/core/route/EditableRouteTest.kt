package com.mockgeo.core.route

import com.google.common.truth.Truth.assertThat
import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.RoutePoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EditableRouteTest {
    private val points = listOf(
        RoutePoint(routeId = 7, seq = 10, lat = 30.0, lng = 110.0, altitude = 10.0, speedMps = null),
        RoutePoint(routeId = 7, seq = 20, lat = 30.1, lng = 110.1, altitude = 20.0, speedMps = 1.0),
        RoutePoint(routeId = 7, seq = 30, lat = 30.2, lng = 110.2, altitude = 30.0, speedMps = 2.0),
        RoutePoint(routeId = 7, seq = 40, lat = 30.3, lng = 110.3, altitude = 40.0, speedMps = 3.0),
    )

    @Test fun `reverse keeps speeds on original edges and elevations on original points`() {
        val result = EditableRoute.reverse(points)
        assertThat(result.map { it.lat }).containsExactly(30.3, 30.2, 30.1, 30.0).inOrder()
        assertThat(result.map { it.altitude }).containsExactly(40.0, 30.0, 20.0, 10.0).inOrder()
        assertThat(result.map { it.speedMps }).containsExactly(null, 3.0, 2.0, 1.0).inOrder()
        assertThat(result.map { it.seq }).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(result.map { it.routeId }).containsExactly(7L, 7L, 7L, 7L).inOrder()
    }

    @Test fun `double reverse restores point metadata without changing input`() {
        val original = points.toList()
        val result = EditableRoute.reverse(EditableRoute.reverse(points))
        assertThat(result).isEqualTo(points.mapIndexed { i, point -> point.copy(seq = i) })
        assertThat(points).isEqualTo(original)
    }

    @Test fun `delete a middle point invalidates new joined edge only`() {
        val result = EditableRoute.removeAt(points, 1)
        assertThat(result.map { it.lat }).containsExactly(30.0, 30.2, 30.3).inOrder()
        assertThat(result.map { it.altitude }).containsExactly(10.0, 30.0, 40.0).inOrder()
        assertThat(result.map { it.speedMps }).containsExactly(null, null, 3.0).inOrder()
        assertThat(result.map { it.seq }).containsExactly(0, 1, 2).inOrder()
    }

    @Test fun `delete first point removes its successor incoming speed`() {
        val result = EditableRoute.removeAt(points, 0)
        assertThat(result.map { it.speedMps }).containsExactly(null, 2.0, 3.0).inOrder()
        assertThat(result.first().altitude).isEqualTo(20.0)
    }

    @Test fun `delete last point preserves all surviving edges`() {
        val result = EditableRoute.removeAt(points, points.lastIndex)
        assertThat(result.map { it.speedMps }).containsExactly(null, 1.0, 2.0).inOrder()
    }

    @Test fun `move middle point clears adjacent speeds and preserves unrelated metadata`() {
        val destination = LatLng(31.0, 111.0)
        val result = EditableRoute.movePoint(points, 1, destination)
        assertThat(result[1].lat).isEqualTo(destination.lat)
        assertThat(result[1].lng).isEqualTo(destination.lng)
        assertThat(result.map { it.speedMps }).containsExactly(null, null, null, 3.0).inOrder()
        assertThat(result.map { it.altitude }).containsExactly(10.0, 20.0, 30.0, 40.0).inOrder()
        assertThat(result.map { it.seq }).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(points[1].lat).isEqualTo(30.1)
        assertThat(result.map { it.routeId }).containsExactly(7L, 7L, 7L, 7L).inOrder()
    }

    @Test fun `move endpoint invalidates only the surviving adjacent edge`() {
        val firstMoved = EditableRoute.movePoint(points, 0, LatLng(31.0, 111.0))
        assertThat(firstMoved.map { it.speedMps }).containsExactly(null, null, 2.0, 3.0).inOrder()
        val lastMoved = EditableRoute.movePoint(points, 3, LatLng(31.0, 111.0))
        assertThat(lastMoved.map { it.speedMps }).containsExactly(null, 1.0, 2.0, null).inOrder()
    }

    @Test fun `moving to identical coordinates does not discard valid segment speeds`() {
        val result = EditableRoute.movePoint(points, 2, LatLng(points[2].lat, points[2].lng))
        assertThat(result).isEqualTo(points.mapIndexed { i, point -> point.copy(seq = i) })
    }

    @Test fun `empty and one point drafts can be edited without fabricating an incoming edge`() {
        assertThat(EditableRoute.reverse(emptyList())).isEmpty()
        val single = listOf(points[2])
        assertThat(EditableRoute.reverse(single).single().speedMps).isNull()
        assertThat(EditableRoute.removeAt(single, 0)).isEmpty()
        assertThat(EditableRoute.movePoint(single, 0, LatLng(32.0, 112.0)).single().speedMps).isNull()
    }

    @Test fun `invalid point indices are rejected`() {
        assertThrows<IllegalArgumentException> { EditableRoute.removeAt(points, -1) }
        assertThrows<IllegalArgumentException> { EditableRoute.removeAt(points, points.size) }
        assertThrows<IllegalArgumentException> { EditableRoute.movePoint(emptyList(), 0, LatLng(0.0, 0.0)) }
    }
}
