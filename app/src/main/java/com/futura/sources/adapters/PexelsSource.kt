package com.futura.sources.adapters

import com.futura.sources.MediaSource
import com.futura.sources.SearchFilter
import com.futura.sources.SourceConfig
import com.futura.sources.SourceFileType
import com.futura.sources.SourceLicense
import com.futura.sources.SourceMediaItem
import com.futura.sources.SourceRegistry
import com.futura.sources.net.HostRateLimiter
import com.futura.sources.net.SourceHttpClient
import com.futura.sources.net.SourceKeys
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Pexels videos. Needs an API key. Picks the largest file up to 1080p for
 * streaming and keeps the original as the download URL.
 */
@Singleton
class PexelsSource @Inject constructor(
    @SourceHttpClient private val client: HttpClient,
    private val limiter: HostRateLimiter,
    private val keys: SourceKeys
) : MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("pexels") ?: error("pexels not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val key = keys.value("PEXELS_API_KEY") ?: return emptyList()
        val url = "https://api.pexels.com/videos/search?query=${enc(query)}&per_page=${filter.limit}"
        limiter.acquire(HOST)
        val body = client.get(url) { header(HttpHeaders.Authorization, key) }.bodyAsText()
        val videos = JSONObject(body).optJSONArray("videos") ?: return emptyList()
        return (0 until videos.length()).mapNotNull { i ->
            parse(videos.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun parse(video: JSONObject): SourceMediaItem? {
        val id = video.optInt("id").takeIf { it > 0 } ?: return null
        val files = video.optJSONArray("video_files") ?: return null
        var best: String? = null
        var bestHeight = 0
        var original: String? = null
        var originalHeight = 0
        var width: Int? = null
        var height: Int? = null
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val link = file.optString("link").takeIf { it.isNotBlank() } ?: continue
            val h = file.optInt("height")
            val w = file.optInt("width")
            if (file.optString("quality") == "hd" && h > 0 && h <= 1080 && h >= bestHeight) {
                best = link
                bestHeight = h
                width = w
                height = h
            }
            if (original == null || h > originalHeight) {
                original = link
                originalHeight = h
            }
        }
        val stream = best ?: original ?: return null
        val pageUrl = video.optString("url").takeIf { it.isNotBlank() } ?: "https://www.pexels.com/video/$id"
        return SourceMediaItem(
            id = "pexels:$id",
            title = pageUrl.substringAfterLast('/', "Pexels video $id").ifBlank { "Pexels video $id" },
            creator = video.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() },
            durationSec = video.optInt("duration").takeIf { it > 0 },
            thumbnailUrl = video.optString("image").takeIf { it.isNotBlank() },
            streamUrl = stream,
            downloadUrl = original ?: stream,
            license = SourceLicense.SOURCE_LICENSE,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = SourceFileType.VIDEO,
            sourcePageUrl = pageUrl,
            width = width,
            height = height
        )
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val HOST = "api.pexels.com"
    }
}
