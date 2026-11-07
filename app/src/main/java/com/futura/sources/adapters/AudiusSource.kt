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
 * Audius. No key; a live discovery host is resolved from api.audius.co and
 * cached. Stream URLs are derived from the track id.
 */
@Singleton
class AudiusSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("audius") ?: error("audius not in registry")

    @Volatile
    private var discoveryHost: String? = null

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val host = resolveHost()
        val url = "$host/v1/tracks/search?query=${enc(query)}&app_name=FuturaAudio&limit=${filter.limit}"
        val json = JSONObject(get(url, HOST))
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i -> parse(data.optJSONObject(i) ?: return@mapNotNull null) }
    }

    private suspend fun resolveHost(): String {
        discoveryHost?.let { return it }
        val host = try {
            val body = get("https://api.audius.co", "api.audius.co")
            val data = JSONObject(body).optJSONArray("data")
            data?.optString(0)?.takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: DEFAULT_HOST
        discoveryHost = host
        return host
    }

    private fun parse(track: JSONObject): SourceMediaItem? {
        val id = track.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = track.optString("title").takeIf { it.isNotBlank() } ?: return null
        val user = track.optJSONObject("user")?.optString("name")
        val artwork = track.optJSONObject("artwork")?.optString("480x480")?.takeIf { it.isNotBlank() }
        val durationSec = track.optInt("duration").takeIf { it > 0 }
        // Audius tracks are artist-uploaded. Only treat as commercial when the
        // artist marks it downloadable; otherwise keep the safe default.
        val downloadable = track.optBoolean("is_downloadable", false)
        return SourceMediaItem(
            id = "audius:$id",
            title = title,
            creator = user,
            durationSec = durationSec,
            thumbnailUrl = artwork,
            streamUrl = "$DEFAULT_HOST/v1/tracks/$id/stream?app_name=FuturaAudio",
            downloadUrl = if (downloadable) "$DEFAULT_HOST/v1/tracks/$id/download?app_name=FuturaAudio" else null,
            license = SourceLicense.SOURCE_LICENSE,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = SourceFileType.AUDIO,
            sourcePageUrl = "https://audius.co/track/$id",
            downloadable = downloadable
        )
    }

    private companion object {
        const val HOST = "audius.co"
        const val DEFAULT_HOST = "https://discoveryprovider.audius.co"
    }
}
