package com.mockgeo.app.geo

import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToLong

/** Explicit, user-triggered search. Photon is primary; Nominatim is the fallback. */
class NominatimClient(private val userAgent: String = "MockGeo/0.4 (Android location testing)") {
    data class Place(val name: String, val position: LatLng)
    private val searches = GeoMemoryCache<String, List<Place>>(50, 15 * 60_000L)
    private val addresses = GeoMemoryCache<String, String>(20, 15 * 60_000L)

    suspend fun search(query: String, limit: Int = 8): List<Place> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length > 200) throw GeoServiceException("搜索关键词请控制在 200 字以内")
        val count = limit.coerceIn(1, 10)
        val key = "${trimmed.lowercase(Locale.ROOT)}|$count"
        searches.get(key)?.let { return it }
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        try {
            return geoDeadline(12_000, "地点搜索超时，请重试或输入坐标") {
                val primary = try {
                    val body = GeoHttp.get("https://photon.komoot.io/api/?q=$encoded&limit=$count", userAgent)
                    withContext(Dispatchers.Default) { parsePhoton(body) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                val result = if (primary.isNotEmpty()) primary else {
                    val body = GeoHttp.get("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=$count", userAgent)
                    withContext(Dispatchers.Default) { parse(body) }
                }
                result.toList().also { if (it.isNotEmpty()) searches.put(key, it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: GeoServiceException) { throw safe }
        catch (_: Exception) { throw GeoServiceException("地点搜索返回异常，请稍后重试") }
    }

    /** Optional selected-point details; never runs automatically during map movement. */
    suspend fun reverse(point: LatLng): String? {
        val key = "${(point.lat * 10_000).roundToLong()}|${(point.lng * 10_000).roundToLong()}"
        addresses.get(key)?.let { return it }
        try {
            return geoDeadline(12_000, "地址查询超时，仍可直接使用此坐标") {
                val primary = try {
                    val body = GeoHttp.get("https://photon.komoot.io/reverse?lat=${point.lat}&lon=${point.lng}&limit=1", userAgent)
                    withContext(Dispatchers.Default) { parsePhoton(body).firstOrNull()?.name }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                val address = primary ?: run {
                    val body = GeoHttp.get("https://nominatim.openstreetmap.org/reverse?lat=${point.lat}&lon=${point.lng}&format=json&zoom=18", userAgent)
                    withContext(Dispatchers.Default) { JSONObject(body).optString("display_name").takeIf { it.isNotBlank() } }
                }
                address?.also { addresses.put(key, it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: GeoServiceException) { throw safe }
        catch (_: Exception) { throw GeoServiceException("暂时无法查询地址，仍可直接使用此坐标") }
    }

    internal fun parsePhoton(json: String): List<Place> {
        val features = JSONObject(json).getJSONArray("features")
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.optJSONObject(i) ?: return@mapNotNull null
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            if (coordinates.length() < 2) return@mapNotNull null
            val properties = feature.optJSONObject("properties") ?: return@mapNotNull null
            val name = listOf("name", "street", "city", "county", "state", "country")
                .map { properties.optString(it, "") }.filter { it.isNotBlank() }.distinct().joinToString(" · ")
            val lat = coordinates.optDouble(1, Double.NaN)
            val lng = coordinates.optDouble(0, Double.NaN)
            if (name.isEmpty() || lat !in -90.0..90.0 || lng !in -180.0..180.0) null else Place(name, LatLng(lat, lng))
        }.distinctBy { Triple(it.name, it.position.lat, it.position.lng) }
    }

    internal fun parse(json: String): List<Place> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = obj.optDouble("lat", Double.NaN)
            val lng = obj.optDouble("lon", Double.NaN)
            val name = obj.optString("display_name", "")
            if (lat in -90.0..90.0 && lng in -180.0..180.0 && name.isNotBlank()) Place(name, LatLng(lat, lng)) else null
        }
    }
}
