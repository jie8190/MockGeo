package com.mockgeo.app.geo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

/** Messages from these exceptions are safe for the UI; never include URLs or service credentials. */
internal class GeoServiceException(message: String) : IllegalStateException(message)

private class Completed<T>(val value: T)

internal suspend fun <T> geoDeadline(timeoutMs: Long, message: String, block: suspend () -> T): T {
    val completed = withTimeoutOrNull(timeoutMs) { Completed(block()) }
        ?: throw GeoServiceException(message)
    return completed.value
}

/** Small per-client memory cache. Age uses a monotonic clock, so wall-clock changes cannot extend it. */
internal class GeoMemoryCache<K, V>(private val capacity: Int, private val ttlMs: Long) {
    private data class Entry<V>(val value: V, val writtenAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(capacity, 0.75f, true)

    @Synchronized fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if ((System.nanoTime() - entry.writtenAt) / 1_000_000L >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized fun put(key: K, value: V) {
        entries[key] = Entry(value, System.nanoTime())
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
}

/**
 * Blocking HTTP stays on IO workers. Cancellation detaches the waiter immediately and
 * disconnects its connection, allowing a real overall deadline across fallback services.
 */
internal object GeoHttp {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun get(
        address: String,
        userAgent: String,
        connectTimeoutMs: Int = 4_000,
        readTimeoutMs: Int = 4_000,
        maxBytes: Int = 8 * 1024 * 1024,
    ): String = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = workers.launch {
            var http: HttpURLConnection? = null
            try {
                ensureActive()
                val activeConnection = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
                }
                http = activeConnection
                connection.set(activeConnection)
                ensureActive()
                val status = activeConnection.responseCode
                if (status !in 200..299) throw GeoServiceException("地图服务请求失败（HTTP $status）")
                val body = activeConnection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBytes) throw GeoServiceException("地图服务返回的数据过大，请缩小范围")
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(body))
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (error: Exception) {
                val safe = when (error) {
                    is GeoServiceException -> error
                    is SocketTimeoutException -> GeoServiceException("地图服务响应超时，请重试")
                    is UnknownHostException -> GeoServiceException("无法连接地图服务，请检查网络")
                    else -> GeoServiceException("地图服务连接失败，请稍后重试")
                }
                if (continuation.isActive) continuation.resumeWith(Result.failure(safe))
            } finally {
                runCatching { http?.disconnect() }
            }
        }
        continuation.invokeOnCancellation {
            worker.cancel()
            // HttpURLConnection may synchronize disconnect with an active read; never block the UI on it.
            workers.launch { runCatching { connection.get()?.disconnect() } }
        }
    }
}
