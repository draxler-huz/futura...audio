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
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Pixabay videos. Needs an API key. The API returns several renditions per
 * hit; the largest is used for download and a mid-size one for streaming.
 */
@Singleton
class PixabaySource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter,
    private val keys: SourceKeys
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("pixabay") ?: error("pixabay not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val key = keys.value("PIXABAY_API_KEY") ?: return emptyList()
        val url = "https://pixabay.com/api/videos/?key=$key&q=${enc(query)}&per_page=${filter.limit}"
        val hits = JSONObject(get(url, HOST)).optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            parse(hits.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun parse(video: JSONObject): SourceMediaItem? {
        val id = video.optInt("id").takeIf { it > 0 } ?: return null
        val videos = video.optJSONObject("videos") ?: return null
        val large = videos.optJSONObject("large")?.optString("url")?.takeIf { it.isNotBlank() }
        val medium = videos.optJSONObject("medium")?.optString("url")?.takeIf { it.isNotBlank() }
        val stream = large ?: medium ?: return null
        return SourceMediaItem(
            id = "pixabay:$id",
            title = video.optString("tags").takeIf { it.isNotBlank() } ?: "Pixabay video $id",
            creator = video.optString("user").takeIf { it.isNotBlank() },
            durationSec = video.optInt("duration").takeIf { it > 0 },
            thumbnailUrl = video.optString("picture_id").takeIf { it.isNotBlank() }
                ?.let { "https://i.vimeocdn.com/video/${it}_295x166.jpg" },
            streamUrl = medium ?: stream,
            downloadUrl = stream,
            license = SourceLicense.SOURCE_LICENSE,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = SourceFileType.VIDEO,
            sourcePageUrl = video.optString("pageURL").ifBlank { "https://pixabay.com/videos/$id/" }
        )
    }

    private companion object {
        const val HOST = "pixabay.com"
    }
}
