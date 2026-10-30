package com.mockgeo.app.ui.map

import android.content.Context
import android.graphics.drawable.Drawable
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Share the semaphore across renderer recreation: retired in-flight downloads
// and a newly retried provider must not each acquire an independent allowance.
private val sharedOsmSource by lazy { XYTileSource("OSMFranceHOT", 0, 19, 256, ".png",
    arrayOf("https://a.tile.openstreetmap.fr/hot/"),
    "© OpenStreetMap contributors · HOT · OSM France",
    TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE or
        TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)) }

internal fun osmTileSource(): XYTileSource = sharedOsmSource

/** Keep osmdroid's SQL cache, source semaphore and retry/backoff; bound each HTTP request. */
internal class OsmTileProvider(context: Context, source: ITileSource) : MapTileProviderBasic(context, source) {
    override fun createDownloaderProvider(check: INetworkAvailablityCheck?, source: ITileSource?): MapTileDownloader =
        super.createDownloaderProvider(check, source).apply { setTileDownloader(BoundedTileDownloader()) }
}

private class BoundedTileDownloader : TileDownloader() {
    override fun downloadTile(index: Long, cache: IFilesystemCache?, source: OnlineTileSourceBase): Drawable? {
        val url = URL(source.getTileURLString(index))
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.useCaches = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", Configuration.getInstance().userAgentValue)
            connection.setRequestProperty("Accept", "image/png")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (connection.contentType?.startsWith("image/") != true) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val drawable = source.getDrawable(ByteArrayInputStream(bytes)) ?: return null
            val expires = computeExpirationTime(connection.getHeaderField("Expires"),
                connection.getHeaderField("Cache-Control"), System.currentTimeMillis())
            runCatching { cache?.saveFile(source, index, ByteArrayInputStream(bytes), expires) }
            drawable
        } catch (_: Exception) {
            null // The provider records a failed tile and retains its normal URL backoff.
        } finally {
            connection.disconnect()
        }
    }
}
