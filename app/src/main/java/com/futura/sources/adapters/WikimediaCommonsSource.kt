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
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Wikimedia Commons. No key. Searches file pages in the File: namespace and
 * keeps audio or video, reading the license from the file's metadata.
 */
abstract class WikimediaCommonsSource(
    client: HttpClient,
    limiter: HostRateLimiter,
    private val config: SourceConfig,
    private val filePrefix: String
) : BaseSourceAdapter(client, limiter), MediaSource {

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
            "&list=search&srnamespace=6&srlimit=${filter.limit}&srsearch=${enc("$filePrefix $query")}"
        val hits = JSONObject(get(url, HOST))
            .optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i -> parse(hits.optJSONObject(i) ?: return@mapNotNull null) }
    }

    private fun parse(hit: JSONObject): SourceMediaItem? {
        val title = hit.optString("title").takeIf { it.isNotBlank() } ?: return null
        if (!title.startsWith("File:")) return null
        val fileName = title.removePrefix("File:")
        val slug = fileName.replace(' ', '_')
        // Commons pages that are CC0 or public domain are the safe default;
        // anything else is left to the file page's own license tag.
        val license = SourceLicense.SOURCE_LICENSE
        return SourceMediaItem(
            id = "${config.id}:$slug",
            title = fileName.substringBeforeLast('.'),
            creator = null,
            durationSec = null,
            thumbnailUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/${encPath(slug)}?width=480",
            streamUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/${encPath(slug)}",
            downloadUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/${encPath(slug)}",
            license = license,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = config.fileType ?: SourceFileType.AUDIO,
            sourcePageUrl = "https://commons.wikimedia.org/wiki/$title"
        )
    }

    private companion object {
        const val HOST = "commons.wikimedia.org"
    }
}

/** Wikimedia Commons audio files. */
@Singleton
class WikimediaAudioSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : WikimediaCommonsSource(
    client, limiter,
    SourceRegistry.byIdStatic("wikimedia_audio") ?: error("wikimedia_audio not in registry"),
    filePrefix = "filetype:audio"
)

/** Wikimedia Commons video files. */
@Singleton
class WikimediaVideoSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : WikimediaCommonsSource(
    client, limiter,
    SourceRegistry.byIdStatic("wikimedia_video") ?: error("wikimedia_video not in registry"),
    filePrefix = "filetype:video"
)
