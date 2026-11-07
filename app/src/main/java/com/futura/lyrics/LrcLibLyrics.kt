package com.futura.lyrics

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

/**
 * Looks up lyrics on LRCLIB (https://lrclib.net) by track name, artist and
 * duration. Returns the lyric text as published. Parsing is done by the
 * caller.
 *
 * Requests identify the app with a generic User-Agent and send nothing else
 * about the listener.
 */
@Singleton
class LrcLibLyrics @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    /**
     * @param mediaId the track's media id. A numeric id is a real millisecond
     *        duration from the queue, so the exact-duration lookup is safe. A
     *        non-numeric id (a file path, a URL, a hash) means the duration is
     *        not trustworthy, so only the search endpoint is used.
     */
    suspend fun find(
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String? = null
    ): LyricsState =
        withContext(Dispatchers.IO) {
            // Only trust the duration for a numeric media id (a real track);
            // otherwise a URL/path would be sent as the duration and fail.
            val duration = if (mediaId != null && mediaId.all { it.isDigit() }) durationMs else 0L
            val cleanArtist = artist?.substringBefore(" - Topic")?.trim()?.takeIf { it.isNotBlank() }
            try {
                val record = lookup(title, cleanArtist, duration) ?: return@withContext LyricsState.Empty
                val synced = record.textOrNull("syncedLyrics")
                val plain = record.textOrNull("plainLyrics")
                if (synced == null && plain == null) {
                    LyricsState.Empty
                } else {
                    LyricsState.Success(plainLyrics = plain.orEmpty(), syncedLyrics = synced)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                LyricsState.Error("Could not reach LRCLIB. Check your connection.")
            } catch (e: JSONException) {
                LyricsState.Error("LRCLIB returned an unexpected reply.")
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
