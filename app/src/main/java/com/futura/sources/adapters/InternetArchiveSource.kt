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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Internet Archive Advanced Search, for audio or video. No key. Only items
 * whose licenseurl is a Creative Commons or public-domain license are kept.
 * The playable file is resolved lazily from the item's metadata.
 */
abstract class InternetArchiveSource(
    client: HttpClient,
    limiter: HostRateLimiter,
    override val config: SourceConfig,
    private val mediaType: String,
    private val collections: String,
    private val audio: Boolean
) : BaseSourceAdapter(client, limiter), MediaSource {

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val q = "($query) AND mediatype:$mediaType AND ($collections)" +
            " AND licenseurl:*creativecommons.org*"
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl" +
            "&rows=${filter.limit}&page=1&output=json"
        val docs = JSONObject(get(url, HOST)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).mapNotNull { i -> parse(docs.optJSONObject(i) ?: return@mapNotNull null) }
    }

    /** Resolves the first playable file for [item] from the item metadata. */
    suspend fun resolveStreamUrl(item: SourceMediaItem): String? {
        val identifier = item.id.substringAfter(':')
        val files = JSONObject(get("https://archive.org/metadata/${encPath(identifier)}", HOST))
            .optJSONArray("files") ?: return null
        val extensions = if (audio) AUDIO_EXT else VIDEO_EXT
        for (i in 0 until files.length()) {
            val name = files.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotEmpty() && !name.contains('/') &&
                extensions.any { name.endsWith(".$it", ignoreCase = true) }
            ) {
                return "https://archive.org/download/${encPath(identifier)}/${encPath(name)}"
            }
        }
        return null
    }

    private fun parse(doc: JSONObject): SourceMediaItem? {
        val identifier = doc.optString("identifier").takeIf { it.isNotBlank() } ?: return null
        val license = SourceLicense.fromCreativeCommonsUrl(doc.optString("licenseurl")) ?: return null
        return SourceMediaItem(
            id = "${config.id}:$identifier",
            title = doc.optString("title").ifBlank { identifier },
            creator = firstString(doc.opt("creator")),
            durationSec = null,
            thumbnailUrl = "https://archive.org/services/img/$identifier",
            streamUrl = null,
            downloadUrl = null,
            license = license,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = if (audio) SourceFileType.AUDIO else SourceFileType.VIDEO,
            sourcePageUrl = "https://archive.org/details/$identifier"
        )
    }

    companion object {
        private const val HOST = "archive.org"
        private val AUDIO_EXT = listOf("mp3", "ogg", "flac", "wav")
        private val VIDEO_EXT = listOf("mp4", "ogv", "webm")
    }
}

/** Internet Archive audio (netlabels + librivox). */
@Singleton
class ArchiveAudioSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : InternetArchiveSource(
    client, limiter,
    SourceRegistry.byIdStatic("archive_audio") ?: error("archive_audio not in registry"),
    mediaType = "audio",
    collections = "collection:netlabels OR collection:librivox",
    audio = true
)

/** Internet Archive video (Prelinger + opensource_movies). */
@Singleton
class ArchiveVideoSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : InternetArchiveSource(
    client, limiter,
    SourceRegistry.byIdStatic("archive_video") ?: error("archive_video not in registry"),
    mediaType = "movies",
    collections = "collection:prelinger OR collection:opensource_movies",
    audio = false
)

/** Archive creator fields are sometimes a string and sometimes an array. */
internal fun firstString(value: Any?): String? = when (value) {
    is String -> value.takeIf { it.isNotBlank() }
    is JSONArray -> if (value.length() > 0) value.optString(0).takeIf { it.isNotBlank() } else null
    else -> null
}
