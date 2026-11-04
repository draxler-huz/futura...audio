package com.futura.audio.pipeline.compliant

import android.util.Xml
import com.futura.audio.domain.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.util.UUID
import javax.inject.Inject

class RssFeedParser @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun parseFeed(feedUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl).get().build()

        // `.use {}` closes the response body on every path, including errors.
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch RSS feed: ${response.code} ${response.message}")
            }

            val xmlBody = response.body?.string() ?: throw IllegalStateException("Empty feed response")

            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xmlBody))
            }

            val items = mutableListOf<MediaItem>()
            var currentTitle: String? = null
            var currentAudioUrl: String? = null
            var currentArtwork: String? = null
            var feedTitle = "Podcast Feed"

            var eventType = parser.eventType
            var insideItem = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name?.lowercase()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "item" || name == "entry") {
                            insideItem = true
                            currentTitle = null
                            currentAudioUrl = null
                            currentArtwork = null
                        } else if (insideItem) {
                            if (name == "title") {
                                currentTitle = parser.nextText()
                            } else if (name == "enclosure") {
                                val type = parser.getAttributeValue(null, "type") ?: ""
                                val url = parser.getAttributeValue(null, "url")
                                if (url != null && (type.contains("audio") || url.endsWith(".mp3") || url.endsWith(".m4a"))) {
                                    currentAudioUrl = url
                                }
                            } else if (name == "image" || name == "itunes:image") {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank()) currentArtwork = href
                            }
                        } else {
                            if (name == "title" && feedTitle == "Podcast Feed") {
                                feedTitle = parser.nextText()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if ((name == "item" || name == "entry") && insideItem) {
                            val audioUrl = currentAudioUrl
                            val title = currentTitle
                            if (!audioUrl.isNullOrBlank() && !title.isNullOrBlank()) {
                                items.add(
                                    MediaItem(
                                        id = UUID.randomUUID().toString(),
                                        title = title,
                                        artist = feedTitle,
                                        streamUrl = audioUrl,
                                        artworkUrl = currentArtwork,
                                        sourceName = feedTitle,
                                        subcategory = "syndication.rss"
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
