package com.mockgeo.core.route

import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.RoutePoint
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** GPX 1.1 tracks, routes and waypoints. External entities are never resolved. */
object Gpx {
    data class Track(val points: List<RoutePoint>, val name: String? = null)

    fun parse(xml: String): Track? {
        if (xml.isBlank() || xml.length > 8 * 1024 * 1024 ||
            Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)
        ) return null
        return runCatching {
            val handler = TrackHandler()
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            for (feature in listOf(
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            )) runCatching { factory.setFeature(feature, false) }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
                throw SAXException("External entities are not supported")
            }
            reader.parse(InputSource(StringReader(xml)))
            handler.track()
        }.getOrNull()
    }

    private class TrackHandler : DefaultHandler() {
        private data class Point(val position: LatLng, var altitude: Double? = null,
                                 var time: Long? = null, var speed: Double? = null)
        private val points = mutableListOf<Point>()
        private var current: Point? = null
        private var pointTag: String? = null
        private val text = StringBuilder()
        private var name: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val tag = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
            text.setLength(0)
            if (tag in setOf("trkpt", "rtept", "wpt")) {
                pointTag = tag
                val lat = attributes.getValue("lat")?.toDoubleOrNull()
                val lng = attributes.getValue("lon")?.toDoubleOrNull()
                current = if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                    Point(LatLng(lat, lng))
                } else null
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
            val value = text.toString().trim()
            when (tag) {
                "ele" -> current?.altitude = value.toDoubleOrNull()?.takeIf { it.isFinite() }
                "time" -> current?.time = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
                "speed" -> current?.speed = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                "name" -> if (name == null && value.isNotEmpty()) name = value
            }
            if (tag == pointTag) {
                current?.let { points += it }
                require(points.size <= 100_000) { "Too many GPX points" }
                current = null
                pointTag = null
            }
            text.setLength(0)
        }

        fun track(): Track? {
            if (points.isEmpty()) return null
            return Track(points.mapIndexed { i, point ->
                val previous = points.getOrNull(i - 1)
                val elapsed = if (point.time != null && previous?.time != null) {
                    (point.time!! - previous.time!!) / 1000.0
                } else 0.0
                val speed = point.speed ?: if (elapsed > 0 && previous != null) {
                    previous.position.distanceTo(point.position) / elapsed
                } else null
                RoutePoint(seq = i, lat = point.position.lat, lng = point.position.lng,
                    altitude = point.altitude, speedMps = speed)
            }, name)
        }
    }

    fun export(name: String, points: List<RoutePoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"MockGeo\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:mg=\"https://mockgeo.local/gpx/1\">\n")
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            append("      <trkpt lat=\"").append(fmt(p.lat)).append("\" lon=\"").append(fmt(p.lng)).append("\">")
            p.altitude?.takeIf { it.isFinite() }?.let { append("<ele>").append(fmt(it)).append("</ele>") }
            p.speedMps?.takeIf { it.isFinite() && it >= 0 }?.let {
                append("<extensions><mg:speed>").append(fmt(it)).append("</mg:speed></extensions>")
            }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    fun exportWaypoint(name: String, point: LatLng): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"MockGeo\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <wpt lat=\"").append(fmt(point.lat)).append("\" lon=\"").append(fmt(point.lng)).append("\">\n")
        append("    <name>").append(escape(name)).append("</name>\n  </wpt>\n</gpx>\n")
    }

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.ROOT, "%.7f", v)

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
