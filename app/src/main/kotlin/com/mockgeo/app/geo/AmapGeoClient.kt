package com.mockgeo.app.geo

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.poisearch.PoiResultV2
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.poisearch.VisualSearchResult
import com.amap.api.services.route.BusRouteResultV2
import com.amap.api.services.route.DriveRouteResultV2
import com.amap.api.services.route.RideRouteResultV2
import com.amap.api.services.route.RouteResult
import com.amap.api.services.route.RouteSearchV2
import com.amap.api.services.route.WalkRouteResultV2
import com.mockgeo.app.BuildConfig
import com.mockgeo.core.data.prefs.SettingsRepository
import com.mockgeo.core.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/** AMap search API boundary: consent before SDK use; WGS84 outside, GCJ-02 inside. */
class AmapGeoClient(context: Context) {
    private val appContext = context.applicationContext
    private val coordinates = AmapCoordinates(appContext)
    private val settings = SettingsRepository(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val searches = GeoMemoryCache<String, List<NominatimClient.Place>>(50, 15 * 60_000L)
    private val addresses = GeoMemoryCache<String, String>(20, 15 * 60_000L)

    suspend fun search(query: String, near: LatLng? = null): List<NominatimClient.Place> = safeCall(15_000) {
        authorize()
        val text = query.trim()
        if (text.isEmpty()) return@safeCall emptyList()
        if (text.length > 100) throw GeoServiceException("高德搜索关键词请控制在 100 字以内")
        val key = "${text.lowercase(Locale.ROOT)}|${near?.let { quantize(it, 100.0) }.orEmpty()}"
        searches.get(key)?.let { return@safeCall it }
        val result = awaitSdk<PoiResultV2> { complete ->
            val request = PoiSearchV2.Query(text, "").apply {
                setPageNum(1)
                setPageSize(20)
                setCityLimit(false)
                setQueryLanguage(ServiceSettings.CHINESE)
                if (near != null) {
                    setLocation(toSdk(near))
                    setDistanceSort(true)
                }
            }
            val client = PoiSearchV2(appContext, request)
            val detach = { client.setOnPoiSearchListener(null) }
            client.setOnPoiSearchListener(object : PoiSearchV2.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResultV2?, code: Int) = complete(result, code)
                override fun onPoiItemSearched(item: PoiItemV2?, code: Int) = Unit
                override fun onVisualSearched(result: VisualSearchResult?, code: Int) = Unit
            })
            try { client.searchPOIAsyn() } catch (error: Exception) { detach(); throw error }
            detach
        }
        val places = withContext(Dispatchers.Default) {
            result.pois.orEmpty().mapNotNull { item ->
                val coordinate = item.latLonPoint ?: return@mapNotNull null
                val name = listOf(item.title, item.provinceName, item.cityName, item.adName, item.snippet)
                    .filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" · ")
                if (name.isEmpty()) null else NominatimClient.Place(name, fromSdk(coordinate))
            }.distinctBy { Triple(it.name, it.position.lat, it.position.lng) }
        }
        requireConsent() // Consent may have been withdrawn while an asynchronous request was in flight.
        places.also { if (it.isNotEmpty()) searches.put(key, it) }
    }

    suspend fun reverse(point: LatLng): String? = safeCall(15_000) {
        authorize()
        val key = quantize(point, 10_000.0)
        addresses.get(key)?.let { return@safeCall it }
        val result = awaitSdk<RegeocodeResult> { complete ->
            val client = GeocodeSearch(appContext)
            val detach = { client.setOnGeocodeSearchListener(null) }
            client.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) = complete(result, code)
                override fun onGeocodeSearched(result: GeocodeResult?, code: Int) = Unit
            })
            val request = RegeocodeQuery(toSdk(point), 200f, GeocodeSearch.AMAP).apply {
                setExtensions(GeocodeSearch.EXTENSIONS_BASE)
            }
            try { client.getFromLocationAsyn(request) } catch (error: Exception) { detach(); throw error }
            detach
        }
        requireConsent()
        result.regeocodeAddress?.formatAddress?.takeIf { it.isNotBlank() }
            ?.also { addresses.put(key, it) }
    }

    suspend fun route(points: List<LatLng>, profile: String): OsrmClient.RouteResult =
        safeCall(if (profile == "drive") 15_000 else 30_000) {
            authorize()
            if (profile !in setOf("drive", "walk", "ride")) throw GeoServiceException("请选择驾车、步行或骑行")
            val maximum = if (profile == "drive") 16 else 10
            if (points.size !in 2..maximum) throw GeoServiceException("此方式的路线需要 2–$maximum 个途经点")
            if (points.zipWithNext().all { (a, b) -> a == b }) throw GeoServiceException("请选择至少两个不同的位置")
            val sections = if (profile == "drive") listOf(points) else points.zipWithNext().map { (a, b) -> listOf(a, b) }
            val merged = ArrayList<LatLng>()
            var distance = 0.0
            var duration = 0.0
            sections.forEachIndexed { index, section ->
                coroutineContext.ensureActive()
                if (index > 0) authorize()
                val raw = geoDeadline(15_000, "高德路线规划超时，请重试") { requestRoute(section, profile) }
                val segment = withContext(Dispatchers.Default) { convertRoute(raw) }
                for (position in segment.points) if (merged.lastOrNull() != position) merged.add(position)
                if (merged.size > 100_000) throw GeoServiceException("路线点数过多，请分段规划")
                distance += segment.distanceM
                duration += segment.durationS
            }
            requireConsent()
            OsrmClient.RouteResult(merged.toList(), distance, duration)
        }

    private suspend fun requestRoute(points: List<LatLng>, profile: String): RouteResult = awaitSdk { complete ->
        val client = RouteSearchV2(appContext)
        val detach = { client.setRouteSearchListener(null) }
        client.setRouteSearchListener(object : RouteSearchV2.OnRouteSearchListener {
            override fun onDriveRouteSearched(result: DriveRouteResultV2?, code: Int) {
                if (profile == "drive") complete(result, code)
            }
            override fun onWalkRouteSearched(result: WalkRouteResultV2?, code: Int) {
                if (profile == "walk") complete(result, code)
            }
            override fun onRideRouteSearched(result: RideRouteResultV2?, code: Int) {
                if (profile == "ride") complete(result, code)
            }
            override fun onBusRouteSearched(result: BusRouteResultV2?, code: Int) = Unit
        })
        val ends = RouteSearchV2.FromAndTo(toSdk(points.first()), toSdk(points.last()))
        val fields = RouteSearchV2.ShowFields.POLINE or RouteSearchV2.ShowFields.COST
        try {
            when (profile) {
                "drive" -> client.calculateDriveRouteAsyn(RouteSearchV2.DriveRouteQuery(
                    ends, RouteSearchV2.DrivingStrategy.DEFAULT, points.drop(1).dropLast(1).map(::toSdk), null, null,
                ).apply { setShowFields(fields) })
                "walk" -> client.calculateWalkRouteAsyn(RouteSearchV2.WalkRouteQuery(ends).apply { setShowFields(fields) })
                "ride" -> client.calculateRideRouteAsyn(RouteSearchV2.RideRouteQuery(ends).apply { setShowFields(fields) })
            }
        } catch (error: Exception) { detach(); throw error }
        detach
    }

    private suspend fun convertRoute(result: RouteResult): OsrmClient.RouteResult {
        val line: List<LatLonPoint>
        val distance: Double
        val duration: Double
        when (result) {
            is DriveRouteResultV2 -> {
                val path = result.paths?.firstOrNull() ?: throw noRoute()
                line = path.steps.orEmpty().flatMap { it.polyline.orEmpty() }.ifEmpty { path.polyline.orEmpty() }
                distance = path.distance.toDouble()
                duration = path.cost?.duration?.toDouble()?.takeIf { it.isFinite() && it > 0 }
                    ?: path.duration.toDouble()
            }
            is WalkRouteResultV2 -> {
                val path = result.paths?.firstOrNull() ?: throw noRoute()
                line = path.steps.orEmpty().flatMap { it.polyline.orEmpty() }.ifEmpty { path.polyline.orEmpty() }
                distance = path.distance.toDouble()
                duration = path.duration.toDouble()
            }
            is RideRouteResultV2 -> {
                val path = result.paths?.firstOrNull() ?: throw noRoute()
                line = path.steps.orEmpty().flatMap { it.polyline.orEmpty() }.ifEmpty { path.polyline.orEmpty() }
                distance = path.distance.toDouble()
                duration = path.duration.toDouble()
            }
            else -> throw noRoute()
        }
        if (line.size !in 2..100_000) throw GeoServiceException("高德未返回有效的道路轨迹，请重试")
        val converted = ArrayList<LatLng>(line.size)
        line.forEachIndexed { index, point ->
            if (index % 512 == 0) coroutineContext.ensureActive()
            val position = fromSdk(point)
            if (converted.lastOrNull() != position) converted += position
        }
        if (converted.size < 2) throw noRoute()
        return OsrmClient.RouteResult(
            converted,
            distance.takeIf { it.isFinite() && it >= 0 }
                ?: converted.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) },
            duration.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
        )
    }

    private suspend fun requireConsent() {
        if (!settings.settings.first().amapConsent) throw GeoServiceException("请先在地图设置中同意高德隐私政策")
        if (AmapKeyStore.effectiveApiKey(appContext).isBlank()) throw GeoServiceException("尚未配置高德地图服务，请切换开放地图")
    }

    private suspend fun authorize() {
        requireConsent()
        val apiKey = AmapKeyStore.effectiveApiKey(appContext)
        withContext(Dispatchers.Main.immediate) {
            ServiceSettings.updatePrivacyShow(appContext, true, true)
            ServiceSettings.updatePrivacyAgree(appContext, true)
            ServiceSettings.getInstance().apply {
                setApiKey(apiKey)
                setProtocol(ServiceSettings.HTTPS)
                setConnectionTimeOut(8_000)
                setSoTimeOut(8_000)
                setLanguage(ServiceSettings.CHINESE)
            }
        }
    }

    /** Registers and detaches SDK listeners on Main; late/duplicate callbacks cannot resume twice. */
    private suspend fun <T : Any> awaitSdk(register: ((T?, Int) -> Unit) -> (() -> Unit)): T =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                var detach: (() -> Unit)? = null
                fun clean() { runCatching { detach?.invoke() } }
                continuation.invokeOnCancellation {
                    finished.set(true)
                    main.post { clean() }
                }
                try {
                    detach = register { value, code ->
                        main.post {
                            if (finished.compareAndSet(false, true)) {
                                clean()
                                if (code == AMapException.CODE_AMAP_SUCCESS && value != null) {
                                    continuation.resumeWith(Result.success(value))
                                } else continuation.resumeWith(Result.failure(serviceError(code)))
                            }
                        }
                    }
                    if (finished.get()) clean()
                } catch (error: Exception) {
                    clean()
                    if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private suspend fun <T> safeCall(timeoutMs: Long, operation: suspend () -> T): T = try {
        geoDeadline(timeoutMs, "高德服务响应超时，请重试", operation)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (safe: GeoServiceException) { throw safe }
    catch (error: AMapException) { throw serviceError(error.errorCode) }
    catch (_: Exception) { throw GeoServiceException("高德服务暂时不可用，请稍后重试") }

    private fun serviceError(code: Int): GeoServiceException {
        val reason = when (code) {
            AMapException.CODE_AMAP_INVALID_USER_KEY, AMapException.CODE_AMAP_INVALID_USER_SCODE,
            AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH, AMapException.CODE_AMAP_SIGNATURE_ERROR -> "服务授权或应用签名配置不匹配"
            AMapException.CODE_AMAP_DAILY_QUERY_OVER_LIMIT, AMapException.CODE_AMAP_ACCESS_TOO_FREQUENT -> "请求次数受限，请稍后重试"
            AMapException.CODE_AMAP_CLIENT_SOCKET_TIMEOUT_EXCEPTION, AMapException.CODE_AMAP_ENGINE_CONNECT_TIMEOUT,
            AMapException.CODE_AMAP_ENGINE_RETURN_TIMEOUT -> "服务连接超时"
            AMapException.CODE_AMAP_CLIENT_NETWORK_EXCEPTION, AMapException.CODE_AMAP_CLIENT_UNKNOWHOST_EXCEPTION -> "网络暂不可用"
            AMapException.CODE_AMAP_ROUTE_OUT_OF_SERVICE, AMapException.CODE_AMAP_ROUTE_NO_ROADS_NEARBY,
            AMapException.CODE_AMAP_ROUTE_FAIL, AMapException.CODE_AMAP_OVER_DIRECTION_RANGE -> "所选范围没有可用的该方式路线"
            AMapException.CODE_AMAP_SUCCESS -> "服务未返回结果"
            else -> "服务暂不可用，请稍后重试"
        }
        return GeoServiceException("高德：$reason（错误码 $code）")
    }

    private fun noRoute() = GeoServiceException("高德未找到可用路线，请调整途经点或出行方式")
    private fun quantize(point: LatLng, scale: Double) = "${(point.lat * scale).roundToLong()}|${(point.lng * scale).roundToLong()}"
    private fun toSdk(point: LatLng): LatLonPoint = coordinates.fromWgs(point).let { LatLonPoint(it.lat, it.lng) }
    private fun fromSdk(point: LatLonPoint): LatLng = coordinates.toWgs(LatLng(point.latitude, point.longitude))
}
