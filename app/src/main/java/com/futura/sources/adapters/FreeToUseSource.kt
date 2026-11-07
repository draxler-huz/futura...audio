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
import org.jsoup.Jsoup

/**
 * FreeToUse. No documented API; the site's own front-end reads an embedded
 * JSON catalogue. The endpoint and the CSS fallback below are best-effort and
 * marked TODO: verify against the live site before enabling this source.
 *
 * Kept disabled in [SourceRegistry] until a smoke test passes.
 */
@Singleton
class FreeToUseSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("freetouse") ?: error("freetouse not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        // TODO: confirm the embedded JSON endpoint. Until then, use the
        // search page and the documented CSS selectors.
        val url = "https://freetouse.com/music/search?q=${enc(query)}"
        val html = get(url, HOST, accept = "text/html")
        return parse(html, filter.limit)
    }

    internal fun parse(html: String, limit: Int): List<SourceMediaItem> {
        val doc = Jsoup.parse(html)
        // TODO: verify these selectors against the live page.
        return doc.select("a.track, div.track-item").mapNotNull { node ->
            val href = node.attr("abs:href").ifBlank { node.attr("href") }
            val title = node.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (href.isBlank()) return@mapNotNull null
            SourceMediaItem(
                id = "freetouse:${href.substringAfterLast('/')}",
                title = title,
                creator = null,
                durationSec = null,
                thumbnailUrl = null,
                streamUrl = null,
                downloadUrl = null,
                license = SourceLicense.SOURCE_LICENSE,
                sourceId = config.id,
                sourceName = config.displayName,
                fileType = SourceFileType.AUDIO,
                sourcePageUrl = href
            )
        }.take(limit)
    }

    private companion object {
        const val HOST = "freetouse.com"
    }
}
