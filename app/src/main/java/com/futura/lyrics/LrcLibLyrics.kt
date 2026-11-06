package com.futura.lyrics

import com.futura.data.nowplaying.LyricParser
import com.futura.data.nowplaying.OfflineLyricSynchronizer
import com.futura.ui.components.nowplaying.LyricLine
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Lyrics for one track. [synced] is true only when the lines carry timestamps from the source. */
data class TrackLyrics(val lines: List<LyricLine>, val synced: Boolean) {
    companion object {
        val NONE = TrackLyrics(emptyList(), synced = false)
    }
}

/**
 * Looks up lyrics on LRCLIB (https://lrclib.net) by track name, artist and
 * duration. Synced LRC lyrics are used as published. Plain lyrics have no
 * timestamps, so they are spread across the track and [TrackLyrics.synced]
 * is false.
 *
 * Requests identify the app with a generic User-Agent and send nothing else
 * about the listener.
 */
@Singleton
class LrcLibLyrics @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun find(title: String, artist: String?, durationMs: Long): TrackLyrics =
        withContext(Dispatchers.IO) {
            try {
                val record = lookup(title, artist, durationMs) ?: return@withContext TrackLyrics.NONE

                val synced = record.textOrNull("syncedLyrics")
                if (synced != null) {
                    val lines = LyricParser.parse(synced)
                    if (lines.isNotEmpty()) return@withContext TrackLyrics(lines, synced = true)
                }

                val plain = record.textOrNull("plainLyrics")
                if (plain != null && durationMs > 0L) {
                    TrackLyrics(OfflineLyricSynchronizer.synchronize(plain, durationMs), synced = false)
                } else {
                    TrackLyrics.NONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                TrackLyrics.NONE
            } catch (e: JSONException) {
                TrackLyrics.NONE
            }
        }

    /** The exact match when the duration is known, otherwise the best search result. */
    private fun lookup(title: String, artist: String?, durationMs: Long): JSONObject? {
        if (durationMs > 0L) {
            val exact = getJson(url("get", title, artist, durationMs))
            if (exact is JSONObject) return exact
        }
        val results = getJson(url("search", title, artist, 0L)) as? JSONArray ?: return null
        val records = (0 until results.length()).map { results.getJSONObject(it) }
        return records.firstOrNull { it.textOrNull("syncedLyrics") != null }
            ?: records.firstOrNull { it.textOrNull("plainLyrics") != null }
    }

    private fun url(path: String, title: String, artist: String?, durationMs: Long): HttpUrl {
        val builder = "$BASE/$path".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
        if (!artist.isNullOrBlank()) builder.addQueryParameter("artist_name", artist)
        if (durationMs > 0L) builder.addQueryParameter("duration", (durationMs / 1000L).toString())
        return builder.build()
    }

    /** Parsed JSON, or null for a 404 (no match). Other failures throw IOException. */
    private fun getJson(url: HttpUrl): Any? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("LRCLIB returned HTTP ${response.code}")
            return JSONTokener(response.body?.string().orEmpty()).nextValue()
        }
    }

    private fun JSONObject.textOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        const val BASE = "https://lrclib.net/api"
        const val USER_AGENT = "FuturaAudio/1.0"
    }
}
