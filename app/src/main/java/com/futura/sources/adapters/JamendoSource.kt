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
 * Jamendo. Needs a client_id. The license string comes back with the track
 * when `include=licenses` is requested.
 */
@Singleton
class JamendoSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter,
    private val keys: SourceKeys
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("jamendo") ?: error("jamendo not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val clientId = keys.value("JAMENDO_CLIENT_ID") ?: return emptyList()
        val url = "https://api.jamendo.com/v3.0/tracks/?client_id=$clientId" +
            "&format=json&limit=${filter.limit}&search=${enc(query)}&include=licenses"
        val results = JSONObject(get(url, HOST)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            parse(results.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun parse(track: JSONObject): SourceMediaItem? {
        val id = track.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = track.optString("name").takeIf { it.isNotBlank() } ?: return null
        val licenseUrl = track.optString("license_ccurl").takeIf { it.isNotBlank() }
        val license = SourceLicense.fromCreativeCommonsUrl(licenseUrl)
            ?: SourceLicense.SOURCE_LICENSE
        return SourceMediaItem(
            id = "jamendo:$id",
            title = title,
            creator = track.optString("artist_name").takeIf { it.isNotBlank() },
            durationSec = track.optInt("duration").takeIf { it > 0 },
            thumbnailUrl = track.optString("album_image").takeIf { it.isNotBlank() },
            streamUrl = track.optString("audio").takeIf { it.isNotBlank() },
            downloadUrl = track.optString("audiodownload").takeIf { it.isNotBlank() },
            license = license,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = SourceFileType.AUDIO,
            sourcePageUrl = track.optString("shareurl").ifBlank { "https://www.jamendo.com/track/$id" }
        )
    }

    private companion object {
        const val HOST = "api.jamendo.com"
    }
}
