package com.mockgeo.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class CoordinateTransformTest {
    private val mainlandCities = mapOf(
        "Beijing" to LatLng(39.9087, 116.3975),
        "Shanghai" to LatLng(31.2304, 121.4737),
        "Shenzhen" to LatLng(22.5431, 114.0579),
        "Chengdu" to LatLng(30.5728, 104.0668),
        "Urumqi" to LatLng(43.8256, 87.6168),
        "Lhasa" to LatLng(29.6520, 91.1721),
        "Haikou" to LatLng(20.0442, 110.1999),
        "Harbin" to LatLng(45.8038, 126.5349),
        "Kunming" to LatLng(25.0389, 102.7183),
        "Kashgar" to LatLng(39.4677, 75.9938),
    )

    @Test
    fun `covered mainland cities have an offset and sub half metre inverse`() {
        mainlandCities.forEach { (name, wgs) ->
            val gcj = CoordinateTransform.wgsToGcj(wgs)
            assertWithMessage("$name offset").that(wgs.distanceTo(gcj)).isGreaterThan(20.0)
            val restored = CoordinateTransform.gcjToWgs(gcj)
            assertWithMessage("$name round trip").that(wgs.distanceTo(restored)).isLessThan(0.5)
        }
    }

    @Test
    fun `Beijing forward matches known GCJ reference`() {
        val converted = CoordinateTransform.wgsToGcj(LatLng(39.908823, 116.397470))
        assertThat(converted.lat).isWithin(1e-8).of(39.91022649807321)
        assertThat(converted.lng).isWithin(1e-8).of(116.4037135824225)
    }

    @Test
    fun `foreign cities inside the old coarse rectangle remain exactly unchanged`() {
        val cities = mapOf(
            "Tokyo" to LatLng(35.6762, 139.6503),
            "Osaka" to LatLng(34.6937, 135.5023),
            "Fukuoka" to LatLng(33.5904, 130.4017),
            "Seoul" to LatLng(37.5665, 126.9780),
            "Busan" to LatLng(35.1796, 129.0756),
            "Jeju" to LatLng(33.4996, 126.5312),
            "Pyongyang" to LatLng(39.0392, 125.7625),
            "Hanoi" to LatLng(21.0285, 105.8542),
            "Lao Cai" to LatLng(22.4856, 103.9707),
            "Mong Cai" to LatLng(21.5240, 107.9660),
            "Bangkok" to LatLng(13.7563, 100.5018),
            "Kathmandu" to LatLng(27.7172, 85.3240),
            "Thimphu" to LatLng(27.4728, 89.6393),
            "Ulaanbaatar" to LatLng(47.9184, 106.9177),
            "Vladivostok" to LatLng(43.1155, 131.8855),
            "Khabarovsk" to LatLng(48.4802, 135.0719),
            "Blagoveshchensk" to LatLng(50.2907, 127.5272),
            "Almaty" to LatLng(43.2389, 76.8897),
            "Bishkek" to LatLng(42.8746, 74.5698),
            "Myitkyina" to LatLng(25.3833, 97.3964),
            "Vientiane" to LatLng(17.9757, 102.6331),
            "Dhaka" to LatLng(23.8103, 90.4125),
            "Leh" to LatLng(34.1526, 77.5771),
        )
        cities.forEach { (name, point) ->
            assertWithMessage("$name forward").that(CoordinateTransform.wgsToGcj(point)).isSameInstanceAs(point)
            assertWithMessage("$name inverse").that(CoordinateTransform.gcjToWgs(point)).isSameInstanceAs(point)
        }
    }

    @Test
    fun `global positions remain unchanged in both directions`() {
        listOf(
            LatLng(40.7128, -74.0060), // New York
            LatLng(51.5074, -0.1278), // London
            LatLng(-33.8688, 151.2093), // Sydney
            LatLng(0.0, 0.0), LatLng(90.0, 180.0), LatLng(-90.0, -180.0),
        ).forEach { point ->
            assertThat(CoordinateTransform.wgsToGcj(point)).isSameInstanceAs(point)
            assertThat(CoordinateTransform.gcjToWgs(point)).isSameInstanceAs(point)
        }
    }

    @Test
    fun `coverage edges and nearby excluded points are stable`() {
        // These are coverage vertices, not claims about a political boundary.
        listOf(
            LatLng(39.3, 75.2), LatLng(28.5, 91.0), LatLng(22.1, 108.0),
            LatLng(42.0, 128.1), LatLng(20.10, 110.25), LatLng(18.20, 109.6),
            LatLng(27.99, 86.93), // Nepal/Himalayan vicinity: conservatively excluded.
            LatLng(43.72, 111.90), // Mongolia side near the Erenhot crossing.
            LatLng(40.10, 124.40), // Korean side near Dandong.
        ).forEach { point ->
            repeat(4) {
                assertThat(CoordinateTransform.wgsToGcj(point)).isSameInstanceAs(point)
                assertThat(CoordinateTransform.gcjToWgs(point)).isSameInstanceAs(point)
            }
        }
    }

    @Test
    fun `inverse accuracy stays stable across local route samples`() {
        mainlandCities.values.forEach { city ->
            listOf(0.0, 90.0, 180.0, 270.0).forEach { bearing ->
                val point = city.destination(bearing, 500.0)
                val restored = CoordinateTransform.gcjToWgs(CoordinateTransform.wgsToGcj(point))
                assertThat(point.distanceTo(restored)).isLessThan(0.5)
            }
        }
    }
}
