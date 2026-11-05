// app/src/main/java/com/futura/audio/pipeline/advanced/PlaylistResolver.kt
package com.futura.audio.pipeline.advanced

import com.futura.audio.domain.MediaId
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.domain.Subcategory
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads a publicly offered `.m3u` or `.pls` playlist and returns its entries
 * as queueable items.
 *
 * The playlist and each entry are fetched with a normal request. No headers
 * are spoofed and no proxy is used. Relative entries are resolved against the
 * playlist's own URL. Only http and https entries are kept.
 */
class PlaylistResolver @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun resolvePlaylist(playlistUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(playlistUrl).get().build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to load playlist: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty playlist")

            val entries = if (body.trimStart().startsWith("[playlist]", ignoreCase = true)) {
                parsePls(body)
            } else {
                parseM3u(body)
            }

            entries
                .take(MAX_ENTRIES)
                .mapNotNull { (title, rawUrl) -> toItem(playlistUrl, title, rawUrl) }
        }
    }

    /** Returns (title, url) pairs from an M3U body. */
    private fun parseM3u(body: String): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        var pendingTitle: String? = null
        for (line in body.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Unit
                trimmed.startsWith("#EXTINF:", ignoreCase = true) ->
                    pendingTitle = trimmed.substringAfter(',', "").ifBlank { null }
                trimmed.startsWith("#") -> Unit
                else -> {
                    out.add(pendingTitle to trimmed)
                    pendingTitle = null
                }
            }
        }
        return out
    }

    /** Returns (title, url) pairs from a PLS body, ordered by entry number. */
    private fun parsePls(body: String): List<Pair<String?, String>> {
        val files = sortedMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        val fileLine = Regex("""File(\d+)=(.+)""", RegexOption.IGNORE_CASE)
        val titleLine = Regex("""Title(\d+)=(.+)""", RegexOption.IGNORE_CASE)

        for (line in body.lines()) {
            val trimmed = line.trim()
            fileLine.matchEntire(trimmed)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { n -> files[n] = m.groupValues[2].trim() }
            }
            titleLine.matchEntire(trimmed)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { n -> titles[n] = m.groupValues[2].trim() }
            }
        }
        return files.map { (n, url) -> titles[n] to url }
    }

    private fun toItem(base: String, title: String?, rawUrl: String): MediaItem? {
        val resolved = resolveEntry(base, rawUrl) ?: return null
        return MediaItem(
            id = MediaId.random(),
            title = title?.takeIf { it.isNotBlank() }
                ?: resolved.substringAfterLast('/').substringBefore('?'),
            artist = "Playlist",
            streamUrl = resolved,
            artworkUrl = null,
            sourceName = "Playlist",
            subcategory = Subcategory("playlist.entry"),
            route = PlaybackRoute.EXOPLAYER_DIRECT,
            category = PipelineCategory.ADVANCED_EXTRACTION
        )
    }

    /** Resolves an entry against the playlist URL. Returns null for anything that is not http(s). */
    private fun resolveEntry(base: String, raw: String): String? {
        val resolved = try {
            URI(base).resolve(raw.trim().replace(" ", "%20")).toString()
        } catch (_: Exception) {
            return null
        }
        return resolved.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private companion object {
        /** Upper bound so a huge playlist cannot flood the queue. */
        const val MAX_ENTRIES = 500
    }
}
