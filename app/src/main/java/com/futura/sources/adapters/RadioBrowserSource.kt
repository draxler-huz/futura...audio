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

/**
 * Radio Browser. No key. Returns live stations only: they are playable but
 * never downloadable, so [SourceMediaItem.isLive] and
 * [SourceMediaItem.downloadable] are set accordingly and the UI hides the
 * Download action.
 */
@Singleton
class RadioBrowserSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("radiobrowser") ?: error("radiobrowser not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val url = "https://de1.api.radio-browser.info/json/stations/search" +
            "?name=${enc(query)}&limit=${filter.limit}&hidebroken=true"
        val stations = JSONArray(get(url, HOST))
        return (0 until stations.length()).mapNotNull { i ->
            parse(stations.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun parse(station: org.json.JSONObject): SourceMediaItem? {
        val id = station.optString("stationuuid").takeIf { it.isNotBlank() } ?: return null
        val name = station.optString("name").takeIf { it.isNotBlank() } ?: return null
        val url = station.optString("url_resolved").takeIf { it.isNotBlank() } ?: return null
        return SourceMediaItem(
            id = "radiobrowser:$id",
            title = name,
            creator = station.optString("country").takeIf { it.isNotBlank() },
            durationSec = null,
            thumbnailUrl = station.optString("favicon").takeIf { it.isNotBlank() },
            streamUrl = url,
            downloadUrl = null,
            license = SourceLicense.RADIO_STREAM,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = SourceFileType.AUDIO,
            sourcePageUrl = station.optString("homepage").takeIf { it.isNotBlank() }
                ?: "https://www.radio-browser.info/",
            downloadable = false,
            isLive = true
        )
    }

    private companion object {
        const val HOST = "api.radio-browser.info"
    }
}
