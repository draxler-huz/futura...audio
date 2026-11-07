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
 * OpenGameArt. No API. Scrapes the advanced-search results for music
 * (art_type_tid 12) and reads the license from each submission.
 *
 * The selectors below match the current site. If OpenGameArt changes its
 * markup, [parse] returns fewer items rather than throwing, and the
 * source health check (Phase 6) will flag it.
 */
@Singleton
class OpenGameArtSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("opengameart") ?: error("opengameart not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val url = "https://opengameart.org/art-search-advanced" +
            "?keys=${enc(query)}&field_art_type_tid%5B%5D=12"
        val html = get(url, HOST, accept = "text/html")
        return parse(html, filter.limit)
    }

    internal fun parse(html: String, limit: Int): List<SourceMediaItem> {
        val doc = Jsoup.parse(html)
        return doc.select("table.views-table tbody tr, article.node").mapNotNull { row ->
            val link = row.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val title = link.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val licenseText = row.select("td").joinToString(" ") { it.text() }
            val license = SourceLicense.fromLabel(licenseText) ?: SourceLicense.SOURCE_LICENSE
            SourceMediaItem(
                id = "opengameart:${href.substringAfterLast('/')}",
                title = title,
                creator = null,
                durationSec = null,
                thumbnailUrl = row.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() },
                streamUrl = null,
                downloadUrl = null,
                license = license,
                sourceId = config.id,
                sourceName = config.displayName,
                fileType = SourceFileType.AUDIO,
                sourcePageUrl = href
            )
        }.take(limit)
    }

    private companion object {
        const val HOST = "opengameart.org"
    }
}
