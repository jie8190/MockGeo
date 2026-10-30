package com.mockgeo.core.data.repo

import androidx.room.withTransaction
import com.mockgeo.core.data.db.AppDatabase
import com.mockgeo.core.data.db.FavoriteEntity
import com.mockgeo.core.data.db.HistoryEntity
import com.mockgeo.core.data.db.RouteEntity
import com.mockgeo.core.data.db.RoutePointEntity
import com.mockgeo.core.model.Favorite
import com.mockgeo.core.model.HistoryEntry
import com.mockgeo.core.model.HistorySource
import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.Route
import com.mockgeo.core.model.RouteMode
import com.mockgeo.core.model.RoutePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/** 收藏/历史/路线仓库。历史裁剪在这里做（不用触发器）。 */
class LocationRepository(private val db: AppDatabase) {

    // ---- 收藏 ----

    fun observeFavorites(): Flow<List<Favorite>> =
        db.favoriteDao().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun addFavorite(name: String, pos: LatLng, altitude: Double? = null): Long =
        db.favoriteDao().insert(
            FavoriteEntity(
                name = checkedName(name),
                lat = pos.lat,
                lng = pos.lng,
                altitude = checkedFavoriteAltitude(altitude),
                accuracy = null,
                createdAt = System.currentTimeMillis(),
                groupName = null,
            ),
        )

    suspend fun removeFavorite(id: Long) {
        db.favoriteDao().byId(id)?.let { db.favoriteDao().delete(it) }
    }

    /** Update only editable columns; creation time, grouping and accuracy remain unchanged. */
    suspend fun updateFavorite(id: Long, name: String, pos: LatLng, altitude: Double?) {
        val cleanName = checkedName(name)
        val cleanAltitude = checkedFavoriteAltitude(altitude)
        require(pos.lat.isFinite() && pos.lat in -90.0..90.0 &&
            pos.lng.isFinite() && pos.lng in -180.0..180.0) { "坐标超出范围" }
        check(db.favoriteDao().updateDetails(id, cleanName, pos.lat, pos.lng, cleanAltitude) > 0) { "收藏位置不存在" }
    }

    private fun checkedFavoriteAltitude(altitude: Double?): Double? = altitude.also {
        require(it == null || (it.isFinite() && it in -500.0..9000.0)) { "海拔应为 -500–9000 米" }
    }

    // ---- 历史 ----

    fun observeHistory(limit: Int): Flow<List<HistoryEntry>> =
        db.historyDao().observeRecent(limit).map { list -> list.map { it.toModel() } }

    suspend fun addHistory(pos: LatLng, source: HistorySource, extra: String? = null) {
        db.withTransaction {
            db.historyDao().insert(
                HistoryEntity(
                    lat = pos.lat,
                    lng = pos.lng,
                    source = source.name,
                    createdAt = System.currentTimeMillis(),
                    extra = extra,
                ),
            )
            db.historyDao().trimTo(historyLimit)
        }
    }

    @Volatile
    private var historyLimit: Int = 100

    suspend fun setHistoryLimit(limit: Int) {
        require(limit in 10..1000) { "历史条数应为 10–1000" }
        db.withTransaction {
            historyLimit = limit
            db.historyDao().trimTo(limit)
        }
    }

    suspend fun clearHistory() = db.historyDao().clear()

    suspend fun deleteHistory(id: Long) = db.historyDao().delete(id)

    // ---- 路线 ----

    fun observeRoutes(): Flow<List<Route>> =
        db.routeDao().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun saveRoute(name: String, points: List<LatLng>, mode: RouteMode): Long =
        saveDetailedRoute(name, points.mapIndexed { i, p -> RoutePoint(seq = i, lat = p.lat, lng = p.lng) }, mode)

    /** Route and its ordered points are committed together; GPX elevation/speed are retained. */
    suspend fun saveDetailedRoute(
        name: String,
        points: List<RoutePoint>,
        mode: RouteMode,
        controlPoints: List<LatLng> = emptyList(),
        profile: String = "drive",
    ): Long {
        val cleanName = checkedName(name)
        require(profile in listOf("drive", "walk", "ride")) { "请选择驾车、步行或骑行" }
        require(points.size in 2..100_000) { "路线应包含 2–100000 个点" }
        require(points.map { it.seq }.distinct().size == points.size) { "路线点序号重复" }
        val ordered = points.sortedBy { it.seq }
        ordered.forEach {
            LatLng(it.lat, it.lng)
            require(it.altitude?.isFinite() != false) { "海拔数值无效" }
            require(it.speedMps?.let { speed -> speed.isFinite() && speed >= 0.0 } != false) { "速度数值无效" }
        }
        val controlsJson = encodeControlPoints(controlPoints)
        val now = System.currentTimeMillis()
        val segments = ordered.zipWithNext().map { (a, b) ->
            LatLng(a.lat, a.lng).distanceTo(LatLng(b.lat, b.lng))
        }
        val distance = segments.sum()
        val duration = if (ordered.drop(1).all { (it.speedMps ?: 0.0) > 0.0 }) {
            segments.mapIndexed { i, meters -> meters / ordered[i + 1].speedMps!! }.sum().takeIf { it.isFinite() }
        } else null
        return db.withTransaction {
            val routeId = db.routeDao().insertRoute(
                RouteEntity(
                    name = cleanName,
                    distanceM = distance,
                    durationS = duration,
                    mode = mode.name,
                    createdAt = now,
                    updatedAt = now,
                    controlPointsJson = controlsJson,
                    profile = profile,
                ),
            )
            db.routeDao().insertPoints(
                ordered.mapIndexed { i, p ->
                    RoutePointEntity(routeId = routeId, seq = i, lat = p.lat, lng = p.lng,
                        altitude = p.altitude, speedMps = p.speedMps)
                },
            )
            routeId
        }
    }

    suspend fun renameRoute(id: Long, name: String) {
        check(db.routeDao().rename(id, checkedName(name), System.currentTimeMillis()) > 0) { "路线不存在" }
    }

    private fun checkedName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 120) { "名称应为 1–120 个字符" }
    }

    private fun encodeControlPoints(points: List<LatLng>): String? {
        if (points.isEmpty()) return null
        require(points.size in 2..100_000) { "途经点应包含 2–100000 个坐标" }
        val coordinates = JSONArray()
        points.forEach { point ->
            require(point.lat.isFinite() && point.lat in -90.0..90.0 &&
                point.lng.isFinite() && point.lng in -180.0..180.0) { "途经点坐标超出范围" }
            coordinates.put(JSONArray().put(point.lat).put(point.lng))
        }
        return coordinates.toString()
    }

    /** Old rows have null metadata. Invalid metadata falls back to the intact track geometry. */
    private fun decodeControlPoints(json: String?): List<LatLng> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val coordinates = JSONArray(json)
            require(coordinates.length() in 2..100_000)
            List(coordinates.length()) { index ->
                val point = coordinates.getJSONArray(index)
                require(point.length() == 2)
                LatLng(point.getDouble(0), point.getDouble(1))
            }
        }.getOrDefault(emptyList())
    }

    suspend fun routeById(id: Long): Route? = db.routeDao().byId(id)?.toModel()

    suspend fun deleteRoute(id: Long) {
        db.withTransaction {
            db.routeDao().deletePoints(id)
            db.routeDao().deleteRoute(id)
        }
    }

    // ---- 映射 ----

    private fun FavoriteEntity.toModel() = Favorite(
        id = id, name = name, lat = lat, lng = lng, altitude = altitude,
        accuracy = accuracy, createdAt = createdAt, groupName = groupName,
    )

    private fun HistoryEntity.toModel() = HistoryEntry(
        id = id, lat = lat, lng = lng,
        source = runCatching { HistorySource.valueOf(source) }.getOrDefault(HistorySource.MANUAL),
        createdAt = createdAt, extra = extra,
    )

    private fun com.mockgeo.core.data.db.RouteWithPoints.toModel() = Route(
        id = route.id, name = route.name, distanceM = route.distanceM,
        durationS = route.durationS,
        mode = runCatching { RouteMode.valueOf(route.mode) }.getOrDefault(RouteMode.ONCE),
        createdAt = route.createdAt, updatedAt = route.updatedAt,
        points = points.sortedBy { it.seq }.map { RoutePoint(it.routeId, it.seq, it.lat, it.lng, it.altitude, it.speedMps) },
        controlPoints = decodeControlPoints(route.controlPointsJson),
        profile = route.profile,
    )
}
