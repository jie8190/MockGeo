package com.mockgeo.app.geo

import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * OSRM 在线驾车路线规划（公共 demo 服务器，无需 Key）。
 * 失败（离线/服务不可用）时 UI 引导用户手动加点或 GPX 导入——不阻塞核心功能。
 */
class OsrmClient {

    data class RouteResult(val points: List<LatLng>, val distanceM: Double, val durationS: Double)
    private val cache = GeoMemoryCache<String, RouteResult>(16, 10 * 60_000L)

    /** This endpoint returns driving roads only. Walking/cycling must use another provider. */
    suspend fun route(vararg stops: LatLng): Result<RouteResult> = try {
        require(stops.size in 2..100) { "道路规划需要 2–100 个途经点" }
        val coords = stops.joinToString(";") { "%.6f,%.6f".format(Locale.ROOT, it.lng, it.lat) }
        Result.success(cache.get(coords) ?: geoDeadline(15_000, "道路规划超时，请重试或使用直线路线") {
            val body = GeoHttp.get(
                "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson",
                "MockGeo/0.4 (Android location testing)", readTimeoutMs = 8_000,
            )
            withContext(Dispatchers.Default) { parse(body) }.also { cache.put(coords, it) }
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (safe: GeoServiceException) { Result.failure(safe) }
    catch (invalid: IllegalArgumentException) { Result.failure(invalid) }
    catch (_: Exception) { Result.failure(GeoServiceException("道路规划返回异常，请稍后重试")) }

    internal fun parse(json: String): RouteResult {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") throw GeoServiceException(when (root.optString("code")) {
            "NoRoute", "NoSegment" -> "所选途经点附近没有可用的驾车道路"
            "TooBig" -> "路线范围或途经点过多，请分段规划"
            else -> "道路规划服务暂时不可用"
        })
        val route = root.getJSONArray("routes").getJSONObject(0)
        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        if (coords.length() !in 2..100_000) throw GeoServiceException("道路规划结果的点数无效")
        val pts = mutableListOf<LatLng>()
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts += LatLng(c.getDouble(1), c.getDouble(0))
        }
        val distance = route.getDouble("distance")
        val duration = route.getDouble("duration")
        if (!distance.isFinite() || distance < 0 || !duration.isFinite() || duration < 0) {
            throw GeoServiceException("道路规划返回的距离或时间无效")
        }
        return RouteResult(
            points = pts,
            distanceM = distance,
            durationS = duration,
        )
    }
}
