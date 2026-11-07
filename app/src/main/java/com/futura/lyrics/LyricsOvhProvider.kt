package com.futura.lyrics

import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * Lyrics.ovh (https://lyrics.ovh) fallback provider. Keyless. Returns plain
 * text only — it has no time-synced lyrics — so [LyricsState.Success] is
 * always built with `syncedLyrics = null` and the caller falls back to
 * spreading the text across the track.
 *
 * Api: `GET /v1/{artist}/{title}`.
 */
@Singleton
class LyricsOvhProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : LyricsProvider {

    override suspend fun find(title: String, artist: String?, durationMs: Long): LyricsState =
        withContext(Dispatchers.IO) {
            // The endpoint is path-addressed, so an artist is required. Without
            // one there is nothing to look up.
            if (artist.isNullOrBlank()) return@withContext LyricsState.Empty
            try {
                val body = get(url(artist, title)) ?: return@withContext LyricsState.Empty
                val lyrics = JSONObject(body).optString("lyrics").takeIf { it.isNotBlank() }
                    ?: return@withContext LyricsState.Empty
                LyricsState.Success(plainLyrics = lyrics, syncedLyrics = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                LyricsState.Error("Could not reach Lyrics.ovh. Check your connection.")
            } catch (e: JSONException) {
                LyricsState.Error("Lyrics.ovh returned an unexpected reply.")
            }
        }

    private fun url(artist: String, title: String): HttpUrl =
        "$BASE/${enc(artist)}/${enc(title)}".toHttpUrl()

    /** Parsed body, or null for a 404 (no match). Other failures throw IOException. */
    private fun get(url: HttpUrl): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Lyrics.ovh returned HTTP ${response.code}")
            return response.body?.string()
        }
    }

    /** Encodes one path segment; spaces become %20, not +. */
    private fun enc(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")

    private companion object {
        const val BASE = "https://api.lyrics.ovh/v1"
        const val USER_AGENT = "FuturaAudio/1.0"
    }
}
