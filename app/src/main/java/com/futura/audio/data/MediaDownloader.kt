package com.futura.audio.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.futura.audio.domain.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Saves a resolved media item to the device's Downloads collection.
 *
 * Items whose [MediaItem.streamUrl] points at the loopback proxy are fetched
 * through it, so the spoofed headers and Range handling that made them playable
 * are reused for the download. On API 29+ the file is published through
 * MediaStore (visible in the Files app); on older releases it lands in the
 * app's external Downloads folder, which needs no runtime permission.
 */
@Singleton
class MediaDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Streaming download: no read timeout, so a slow host cannot abort a large file.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /** Returns the saved location, or null when the download failed. */
    suspend fun download(item: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(item.streamUrl).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                    ?: contentTypeFor(item)
                val fileName = fileNameFor(item, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(fileName, mime, body.byteStream(), body.contentLength())
                } else {
                    saveViaFile(fileName, body.byteStream())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for ${item.title}", e)
            null
        }
    }

    private fun saveViaMediaStore(
        fileName: String,
        mime: String,
        input: InputStream,
        length: Long
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
            if (length > 0) put(MediaStore.Downloads.SIZE, length)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create a Downloads entry")
        try {
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                ?: throw IOException("Could not open the download stream")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveViaFile(fileName: String, input: InputStream): Uri {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        dir.mkdirs()
        val target = File(dir, fileName)
        input.use { source -> target.outputStream().use { out -> source.copyTo(out) } }
        return Uri.fromFile(target)
    }

    /** A safe file name built from the item title plus an extension taken from the mime type or link. */
    private fun fileNameFor(item: MediaItem, mime: String): String {
        val ext = extensionForMime(mime) ?: extensionFromUrl(item.streamUrl) ?: "bin"
        val base = item.title.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(80).ifBlank { "futura" }
        return "$base.$ext"
    }

    private fun extensionFromUrl(url: String): String? =
        url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }

    private fun extensionForMime(mime: String): String? = when (mime.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "m3u8"
        "application/dash+xml" -> "mpd"
        else -> null
    }

    private fun contentTypeFor(item: MediaItem): String = when {
        item.isVideo -> "video/mp4"
        else -> "audio/mpeg"
    }

    private companion object {
        const val TAG = "MediaDownloader"
    }
}
