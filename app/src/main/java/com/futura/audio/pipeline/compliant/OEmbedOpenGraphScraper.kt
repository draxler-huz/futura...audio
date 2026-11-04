package com.futura.audio.pipeline.compliant

import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PlaybackRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

class OEmbedOpenGraphScraper @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    private val ogTitleRegex = Regex(
        """<meta[^>]*property=["']og:title["'][^>]*content=["']([^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val ogImageRegex = Regex(
        """<meta[^>]*property=["']og:image["'][^>]*content=["']([^"']*)["']""",
        RegexOption.IGNORE_CASE
    )

    suspend fun scrapeMetadata(pageUrl: String): MediaItem = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) FuturaAudio/1.0")
            .build()

        // `.use {}` closes the response body on every path, including errors.
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch web metadata: ${response.code}")
            }

            val html = response.body?.string() ?: ""

            val title = ogTitleRegex.find(html)?.groupValues?.get(1) ?: pageUrl
            val image = ogImageRegex.find(html)?.groupValues?.get(1)

            MediaItem(
                id = UUID.randomUUID().toString(),
                title = title,
                artist = "Web Embed Source",
                streamUrl = pageUrl,   // Loaded by the WebView container, not ExoPlayer
                artworkUrl = image,
                sourceName = "Official Web Embed",
                subcategory = "metadata.opengraph",
                route = PlaybackRoute.WEB_EMBED_CONTAINER
            )
        }
    }
}
