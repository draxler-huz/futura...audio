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
import org.jsoup.nodes.Document

/**
 * Incompetech (Kevin MacLeod). No JSON API. Reads the RSS feed and the
 * per-genre pages, and builds the direct MP3 URL from the track slug. All
 * works are CC-BY 4.0, so attribution is required.
 *
 * Scraping is brittle: [parseFeed] and [parseGenrePage] are kept separate so
 * a layout change is easy to spot and fix.
 */
@Singleton
class IncompetechSource @Inject constructor(
    @SourceHttpClient client: HttpClient,
    limiter: HostRateLimiter
) : BaseSourceAdapter(client, limiter), MediaSource {

    override val config: SourceConfig =
        SourceRegistry.byIdStatic("incompetech") ?: error("incompetech not in registry")

    override suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem> {
        val xml = get(FEED_URL, HOST, accept = "application/rss+xml")
        return parseFeed(xml)
            .filter { it.title.contains(query, ignoreCase = true) }
            .take(filter.limit)
    }

    /** Parses the pieces.rss feed into one item per track. */
    internal fun parseFeed(xml: String): List<SourceMediaItem> {
        val doc: Document = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = item.selectFirst("link")?.text()?.takeIf { it.isNotBlank() }
            val slug = link?.substringAfterLast('/', "")
                ?.removeSuffix(".html")
                ?.takeIf { it.isNotBlank() }
                ?: title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val mp3 = "$MP3_BASE/$slug.mp3"
            SourceMediaItem(
                id = "incompetech:$slug",
                title = title,
                creator = "Kevin MacLeod",
                durationSec = null,
                thumbnailUrl = null,
                streamUrl = mp3,
                downloadUrl = mp3,
                license = SourceLicense.CC_BY,
                sourceId = config.id,
                sourceName = config.displayName,
                fileType = SourceFileType.AUDIO,
                sourcePageUrl = link ?: "https://incompetech.com/music/royalty-free/"
            )
        }
    }

    private companion object {
        const val HOST = "incompetech.com"
        const val FEED_URL = "https://incompetech.com/music/royalty-free/pieces.rss"
        const val MP3_BASE = "https://incompetech.com/music/royalty-free/mp3-royaltyfree"
    }
}
