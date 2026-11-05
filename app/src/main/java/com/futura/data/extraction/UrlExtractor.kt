// app/src/main/java/com/futura/data/extraction/UrlExtractor.kt
package com.futura.data.extraction

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a direct audio or video file into the app cache.
 *
 * Only responses whose Content-Type is audio/* or video/* are kept. Web pages
 * are refused, so this never saves a page or pulls media out of one. Requests
 * use the normal client identity: no spoofed headers and no proxy.
 *
 * Failures return null and leave no partial file. Cancellation is rethrown.
 */
@Singleton
class UrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    data class Downloaded(val file: File, val contentType: String)

    suspend fun download(url: String): Downloaded? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.${extensionOf(url)}")
        try {
            val type = fetchTo(url, target)
            if (type == null) {
                target.delete()
                null
            } else {
                Downloaded(target, type)
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "download failed for $url", e)
            target.delete()
            null
        }
    }

    /** Writes the body to [target] and returns its media type, or null if the response is not audio or video. */
    private fun fetchTo(url: String, target: File): String? {
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?: return null
            if (!type.startsWith("audio/") && !type.startsWith("video/")) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            body.byteStream().use { input ->
                target.outputStream().use { out -> copyLimited(input, out) }
            }
            return type
        }
    }

    private fun copyLimited(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw IOException("File exceeds the $MAX_BYTES byte limit")
            out.write(buffer, 0, read)
        }
    }

    /** File extension from the URL path, or "bin" if it is missing or not a plain word. */
    private fun extensionOf(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext.lowercase() else "bin"
    }

    private companion object {
        const val TAG = "UrlExtractor"
        const val MAX_BYTES = 500L * 1024 * 1024
        const val BUFFER_BYTES = 8 * 1024
    }
}
