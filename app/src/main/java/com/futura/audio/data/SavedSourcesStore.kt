package com.futura.audio.data

import android.content.Context
import com.futura.audio.domain.MediaId
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.domain.Subcategory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.time.Duration.Companion.milliseconds
import org.json.JSONObject

/**
 * Saved media sources, persisted as JSON in app-private storage. Reads and
 * writes run on IO and are serialized by a mutex, so a save from the hub
 * and a load from the library cannot interleave.
 */
@Singleton
class SavedSourcesStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    suspend fun load(): List<MediaItem> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            try {
                val array = JSONArray(file.readText())
                List(array.length()) { i -> array.getJSONObject(i).toMediaItem() }
            } catch (e: Exception) {
                // A corrupt file must not crash the hub; start empty.
                emptyList()
            }
        }
    }

    suspend fun save(items: List<MediaItem>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            // Write to a temp file, then swap it in, so a crash mid-write
            // leaves the previous list intact.
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun MediaItem.toJson(): JSONObject = JSONObject()
        .put("id", id.value)
        .put("title", title)
        .put("artist", artist ?: JSONObject.NULL)
        .put("streamUrl", streamUrl)
        .put("artworkUrl", artworkUrl ?: JSONObject.NULL)
        .put("sourceName", sourceName)
        .put("subcategory", subcategory.value)
        .put("durationMs", durationMs)
        .put("route", route.name)
        .put("category", category.name)
        .put("isVideo", isVideo)

    private fun JSONObject.toMediaItem(): MediaItem = MediaItem(
        id = MediaId(getString("id")),
        title = getString("title"),
        artist = if (isNull("artist")) null else getString("artist"),
        streamUrl = getString("streamUrl"),
        artworkUrl = if (isNull("artworkUrl")) null else getString("artworkUrl"),
        sourceName = getString("sourceName"),
        subcategory = Subcategory(getString("subcategory")),
        duration = optLong("durationMs", 0L).takeIf { it > 0L }?.milliseconds,
        route = PlaybackRoute.valueOf(getString("route")),
        category = PipelineCategory.valueOf(getString("category")),
        isVideo = optBoolean("isVideo", false)
    )

    private companion object {
        const val FILE_NAME = "saved_sources.json"
    }
}
