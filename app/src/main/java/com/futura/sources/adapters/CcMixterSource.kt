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
 * ccMixter. No key. The JSON query API returns uploads with mp3/ogg files
 * and a license name.
 */
@Singleton
class CcMixterSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("ccmixter") ?: error("ccmixter not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val url = "https://ccmixter.org/api/query?f=json&limit=${filter.limit}&tags=${enc(query)}"
        val body = get(url, HOST)
        // The API returns a JSON array, but occasionally wraps it in an object.
        val arr = if (body.trimStart().startsWith("[")) {
            org.json.JSONArray(body)
        } else {
            JSONObject(body).optJSONArray("results") ?: return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            parse(arr.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun parse(upload: JSONObject): SourceMediaItem? {
        val id = upload.optString("upload_id").takeIf { it.isNotBlank() } ?: return null
        val title = upload.optString("upload_name").takeIf { it.isNotBlank() } ?: return null
        val license = SourceLicense.fromLabel(upload.optString("license_name")) ?: SourceLicense.SOURCE_LICENSE
        val mp3 = upload.optString("files").takeIf { it.isNotBlank() }
            ?: upload.optString("download_url").takeIf { it.isNotBlank() }
        return SourceMediaItem(
            id = "ccmixter:$id",
            title = title,
            creator = upload.optString("user_name").takeIf { it.isNotBlank() },
            durationSec = null,
            thumbnailUrl = upload.optString("upload_image").takeIf { it.isNotBlank() },
            streamUrl = mp3,
            downloadUrl = mp3,
            license = license,
            sourceId = config.id,
            sourceName = config.displayName,
            fileType = SourceFileType.AUDIO,
            sourcePageUrl = upload.optString("file_page_url").ifBlank { "https://ccmixter.org/files/$id" }
        )
    }

    private companion object {
        const val HOST = "ccmixter.org"
    }
}
