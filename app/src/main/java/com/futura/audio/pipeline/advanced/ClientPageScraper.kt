package com.futura.audio.pipeline.advanced

import com.futura.audio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

class ClientPageScraper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val proxyServer: LocalhostProxyServer
) {
    private val streamRegex = Regex("https?://[^\"'\\s]+\\.(?:mp3|m4a|aac|m3u8|mpd|mp4|m4v|webm|mov)(?:\\?[^\"'\\s]*)?", RegexOption.IGNORE_CASE)
    private val ogTitleRegex = Regex("""<meta[^>]*property=["']og:title["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val ogImageRegex = Regex("""<meta[^>]*property=["']og:image["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    suspend fun extractStream(pageUrl: String): MediaItem = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(pageUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Extraction failed: HTTP ${response.code}")
            val html = response.body?.string() ?: throw IOException("Empty body")

            val title = ogTitleRegex.find(html)?.groupValues?.get(1) ?: pageUrl.substringAfterLast('/')
            val artwork = ogImageRegex.find(html)?.groupValues?.get(1)
            val rawStream = streamRegex.find(html)?.value ?: throw IOException("No stream URL found in page source.")

            val proxiedUrl = proxyServer.getProxyUrl(rawStream)

            MediaItem(
                id = MediaId.random(),
                title = title,
                artist = "Extracted Creator Hub",
                streamUrl = proxiedUrl,
                artworkUrl = artwork,
                sourceName = "Advanced Extraction",
                subcategory = Subcategory("extraction.client_scraper"),
                category = PipelineCategory.ADVANCED_EXTRACTION,
                route = PlaybackRoute.EXOPLAYER_DIRECT,
                isVideo = StreamKind.isVideo(rawStream)
            )
        }
    }
}

class Category2Factory @Inject constructor(
    private val clientPageScraper: ClientPageScraper,
    private val proxyServer: LocalhostProxyServer
) {
    suspend fun resolve(url: String): List<MediaItem> {
        proxyServer.start()
        return if (url.contains("bandcamp") || url.contains("soundcloud") || url.contains("mixcloud")) {
            listOf(clientPageScraper.extractStream(url))
        } else {
            val proxied = proxyServer.getProxyUrl(url)
            listOf(
                MediaItem(
                    id = MediaId.random(),
                    title = url.substringAfterLast('/'),
                    artist = "Proxied Stream",
                    streamUrl = proxied,
                    artworkUrl = null,
                    sourceName = "Local Proxy",
                    subcategory = Subcategory("proxy.localhost_ktor"),
                    category = PipelineCategory.ADVANCED_EXTRACTION,
                    route = PlaybackRoute.EXOPLAYER_DIRECT,
                    isVideo = StreamKind.isVideo(url)
                )
            )
        }
    }
}

/** Classifies a stream URL as video or audio from its path extension. */
internal object StreamKind {
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "ts", "m3u8", "mpd")

    fun isVideo(url: String): Boolean {
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }
}
