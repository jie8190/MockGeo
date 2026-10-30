package com.mockgeo.core.route

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GpxTest {

    @Test
    fun `parse basic track with ele`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test">
              <trk><name>test route</name>
                <trkseg>
                  <trkpt lat="39.9" lon="116.4"><ele>50.5</ele></trkpt>
                  <trkpt lat="39.91" lon="116.41"><ele>51.5</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val track = Gpx.parse(xml)
        assertThat(track).isNotNull()
        assertThat(track!!.name).isEqualTo("test route")
        assertThat(track.points).hasSize(2)
        assertThat(track.points[0].lat).isEqualTo(39.9)
        assertThat(track.points[0].altitude).isEqualTo(50.5)
        assertThat(track.points[1].seq).isEqualTo(1)
    }

    @Test
    fun `parse self closing trkpt`() {
        val xml = """
            <gpx><trk><trkseg>
              <trkpt lat="1.0" lon="2.0"/>
              <trkpt lat="3.0" lon="4.0"/>
            </trkseg></trk></gpx>
        """.trimIndent()
        val track = Gpx.parse(xml)
        assertThat(track!!.points).hasSize(2)
        assertThat(track.points[0].altitude).isNull()
    }

    @Test
    fun `parse with time computes speed`() {
        val xml = """
            <gpx><trk><trkseg>
              <trkpt lat="0.0" lon="0.0"><time>2026-01-01T00:00:00Z</time></trkpt>
              <trkpt lat="0.008993" lon="0.0"><time>2026-01-01T00:05:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val track = Gpx.parse(xml)
        // 1000m / 300s ≈ 3.33 m/s
        assertThat(track!!.points[1].speedMps!!).isWithin(0.05).of(1000.0 / 300.0)
    }

    @Test
    fun `parse skips invalid points`() {
        val xml = """
            <gpx><trk><trkseg>
              <trkpt lat="999" lon="0"/>
              <trkpt lat="1.0" lon="2.0"/>
            </trkseg></trk></gpx>
        """.trimIndent()
        val track = Gpx.parse(xml)
        assertThat(track!!.points).hasSize(1)
    }

    @Test
    fun `parse empty returns null`() {
        assertThat(Gpx.parse("<gpx></gpx>")).isNull()
        assertThat(Gpx.parse("")).isNull()
        assertThat(Gpx.parse("not xml at all")).isNull()
    }

    @Test
    fun `export and reparse round trip`() {
        val points = listOf(
            com.mockgeo.core.model.RoutePoint(seq = 0, lat = 39.9, lng = 116.4, altitude = 50.0),
            com.mockgeo.core.model.RoutePoint(seq = 1, lat = 39.91, lng = 116.41, altitude = null),
        )
        val xml = Gpx.export("round trip", points)
        val track = Gpx.parse(xml)
        assertThat(track!!.name).isEqualTo("round trip")
        assertThat(track.points).hasSize(2)
        assertThat(track.points[0].lat).isEqualTo(39.9)
        assertThat(track.points[0].altitude).isEqualTo(50.0)
        assertThat(track.points[1].altitude).isNull()
    }

    @Test
    fun `export escapes xml entities`() {
        val xml = Gpx.export("a<b>&\"c\"", emptyList())
        assertThat(xml).contains("a&lt;b&gt;&amp;&quot;c&quot;")
    }

    @Test
    fun `waypoint export`() {
        val xml = Gpx.exportWaypoint("home", com.mockgeo.core.model.LatLng(1.5, 2.5))
        assertThat(xml).contains("lat=\"1.5000000\"")
        assertThat(xml).contains("lon=\"2.5000000\"")
        assertThat(xml).contains("<name>home</name>")
    }
    @Test
    fun `namespace single quote entities and speed extension round trip`() {
        val xml = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"><g:trk>
            <g:name>A &amp; B</g:name><g:trkseg><g:trkpt lon='2.5' lat='1.5'>
            <g:extensions><speed>3.25</speed></g:extensions></g:trkpt></g:trkseg>
            </g:trk></g:gpx>"""
        val track = Gpx.parse(xml)!!
        assertThat(track.name).isEqualTo("A & B")
        assertThat(track.points.single().speedMps).isEqualTo(3.25)
        val roundTrip = Gpx.parse(Gpx.export(track.name!!, track.points))!!
        assertThat(roundTrip.name).isEqualTo("A & B")
        assertThat(roundTrip.points.single().speedMps).isEqualTo(3.25)
    }

    @Test
    fun `GPX numbers are independent of system locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val xml = Gpx.exportWaypoint("place", com.mockgeo.core.model.LatLng(1.5, 2.5))
            val track = Gpx.parse(xml)!!
            assertThat(track.points.single().lat).isEqualTo(1.5)
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test
    fun `reject external entity input and malformed XML`() {
        assertThat(Gpx.parse("""<!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <gpx><wpt lat="1" lon="2"><name>&x;</name></wpt></gpx>""")).isNull()
        assertThat(Gpx.parse("<gpx><trkpt lat='1' lon='2'></gpx>")).isNull()
    }

}
