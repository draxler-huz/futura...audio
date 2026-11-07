package com.futura.aurora

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Wikimedia Commons via the MediaWiki API. No key needed. The license comes
 * from each file's extmetadata. Files with NC or ND licenses are dropped.
 */
@Singleton
class WikimediaCommonsSource @Inject constructor(
    private val client: HttpClient,
    private val limiter: HostRateLimiter
) : AuroraSource {

    override val name = "Wikimedia Commons"

    override suspend fun search(query: String, kind: AuroraKind, limit: Int): List<AuroraMediaItem> {
        val fileType = if (kind == AuroraKind.AUDIO) "audio" else "video"
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2" +
            "&generator=search&gsrnamespace=6&gsrlimit=$limit" +
            "&gsrsearch=${urlEncode("$query filetype:$fileType")}" +
            "&prop=imageinfo&iiprop=url|mime|extmetadata&iiurlwidth=480"

        limiter.await(HOST)
        val pages = JSONObject(client.get(url).bodyAsText())
            .optJSONObject("query")
            ?.optJSONArray("pages")
            ?: return emptyList()
        return (0 until pages.length()).mapNotNull { parse(pages.getJSONObject(it), kind) }
    }

    private fun parse(page: JSONObject, kind: AuroraKind): AuroraMediaItem? {
        val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
        val meta = info.optJSONObject("extmetadata") ?: return null
        val label = meta.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
        val license = AuroraLicense.fromLabel(label) ?: return null
        val file = info.optString("url").takeIf { it.isNotBlank() } ?: return null
        val title = page.optString("title").removePrefix("File:").substringBeforeLast('.')
        val creator = meta.optJSONObject("Artist")?.optString("value")
            ?.let { stripHtml(it) }
            ?.takeIf { it.isNotBlank() }

        return AuroraMediaItem(
            id = "commons:${page.optLong("pageid")}",
            title = title.ifBlank { file.substringAfterLast('/') },
            creator = creator,
            durationSec = null,
            thumbnailUrl = info.optString("thumburl").takeIf { it.isNotBlank() },
            previewUrl = file,
            downloadUrl = file,
            license = license,
            sourceName = name,
            kind = kind,
            sourcePageUrl = info.optString("descriptionurl")
        )
    }

    private fun stripHtml(html: String): String =
        Regex("<[^>]+>").replace(html, "").replace("&amp;", "&").trim()

    private companion object {
        const val HOST = "commons.wikimedia.org"
    }
}
