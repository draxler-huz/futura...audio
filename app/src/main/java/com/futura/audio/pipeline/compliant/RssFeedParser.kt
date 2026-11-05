package com.futura.audio.pipeline.compliant

import android.util.Xml
import com.futura.audio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import javax.inject.Inject

class RssFeedParser @Inject constructor(private val okHttpClient: OkHttpClient) {
    suspend fun parseFeed(feedUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("RSS Error: ${response.code}")
            val xmlBody = response.body?.string() ?: throw IOException("Empty body")

            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xmlBody))
            }

            val items = mutableListOf<MediaItem>()
            var title: String? = null
            var audioUrl: String? = null
            var artwork: String? = null
            var sourceName = "Open Audio Feed"
            var insideItem = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name?.lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "item" || name == "entry") {
                            insideItem = true
                            title = null; audioUrl = null; artwork = null
                        } else if (insideItem) {
                            if (name == "title") title = parser.nextText()
                            else if (name == "enclosure") {
                                val type = parser.getAttributeValue(null, "type") ?: ""
                                val url = parser.getAttributeValue(null, "url")
                                if (url != null && (type.contains("audio") || url.endsWith(".mp3") || url.endsWith(".m4a"))) {
                                    audioUrl = url
                                }
                            } else if (name == "image" || name == "itunes:image") {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank()) artwork = href
                            }
                        } else if (name == "title" && sourceName == "Open Audio Feed") {
                            sourceName = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if ((name == "item" || name == "entry") && insideItem) {
                            if (!audioUrl.isNullOrBlank() && !title.isNullOrBlank()) {
                                items.add(
                                    MediaItem(
                                        id = MediaId.random(),
                                        title = title!!,
                                        artist = sourceName,
                                        streamUrl = audioUrl!!,
                                        artworkUrl = artwork,
                                        sourceName = sourceName,
                                        subcategory = Subcategory("syndication.rss"),
                                        category = PipelineCategory.COMPLIANT_STANDARD,
                                        route = PlaybackRoute.EXOPLAYER_DIRECT
                                    )
                                )
                            }
                            insideItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
            items
        }
    }
}

class Category1Factory @Inject constructor(
    private val rssParser: RssFeedParser
) {
    suspend fun resolve(url: String): List<MediaItem> {
        return when {
            url.endsWith(".rss") || url.contains("feed") || url.endsWith(".xml") || url.contains("archive.org") -> {
                rssParser.parseFeed(url)
            }
            url.endsWith(".mp3") || url.endsWith(".m4a") || url.endsWith(".m3u8") -> {
                listOf(
                    MediaItem(
                        id = MediaId.random(),
                        title = url.substringAfterLast('/'),
                        artist = "Direct Audio Source",
                        streamUrl = url,
                        artworkUrl = null,
                        sourceName = "Direct Stream",
                        subcategory = Subcategory("direct.audio_file"),
                        category = PipelineCategory.COMPLIANT_STANDARD,
                        route = PlaybackRoute.EXOPLAYER_DIRECT
                    )
                )
            }
            else -> {
                listOf(
                    MediaItem(
                        id = MediaId.random(),
                        title = url,
                        artist = "Web Embed",
                        streamUrl = url,
                        artworkUrl = null,
                        sourceName = "Official Web Embed",
                        subcategory = Subcategory("metadata.opengraph"),
                        category = PipelineCategory.COMPLIANT_STANDARD,
                        route = PlaybackRoute.WEB_EMBED_CONTAINER
                    )
                )
            }
        }
    }
}
