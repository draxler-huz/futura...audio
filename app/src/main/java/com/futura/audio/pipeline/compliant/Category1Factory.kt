package com.futura.audio.pipeline.compliant

import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PlaybackRoute
import java.util.UUID
import javax.inject.Inject

class Category1Factory @Inject constructor(
    private val rssParser: RssFeedParser,
    private val ogScraper: OEmbedOpenGraphScraper
) {

    suspend fun resolve(url: String): List<MediaItem> {
        return when {
            url.endsWith(".rss") || url.contains("feed") || url.endsWith(".xml") -> {
                // Enclosures are mapped to EXOPLAYER_DIRECT by the parser.
                rssParser.parseFeed(url)
            }
            url.endsWith(".mp3") || url.endsWith(".m4a") || url.endsWith(".m3u8") -> {
                listOf(
                    MediaItem(
                        id = UUID.randomUUID().toString(),
                        title = url.substringAfterLast('/'),
                        artist = "Direct Published Audio",
                        streamUrl = url,
                        artworkUrl = null,
                        sourceName = "Direct Stream",
                        subcategory = "direct.audio_file",
                        route = PlaybackRoute.EXOPLAYER_DIRECT
                    )
                )
            }
            else -> {
                // Web links and embeds go to the WebView container.
                listOf(ogScraper.scrapeMetadata(url))
            }
        }
    }
}
