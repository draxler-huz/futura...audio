package com.futura.data.extraction

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Downloads direct HTTP(S) audio-file URLs into the app cache for scanning.
 *
 * The injected application context lets Hilt construct this service without
 * a separate binding module. Downloads are streamed off the main thread and
 * capped at the scanner's default per-file size limit.
 */
@Singleton
class UrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DownloadedMedia(val file: File)

    /** Returns null for unsupported URLs, non-audio responses, or failed downloads. */
    suspend fun download(targetUrl: String): DownloadedMedia? = withContext(Dispatchers.IO) {
        val requestUrl = runCatching { URL(targetUrl.trim()) }.getOrNull()
            ?: return@withContext null
        if (!requestUrl.isHttpUrl()) return@withContext null

        var connection: HttpURLConnection? = null
        var destination: File? = null
        var completed = false

        try {
            connection = requestUrl.openConnection() as? HttpURLConnection
                ?: return@withContext null
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", ACCEPT_HEADER)
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val responseCode = connection.responseCode
            val finalUrl = connection.url
            if (responseCode !in 200..299 || !finalUrl.isHttpUrl()) {
                return@withContext null
            }

            val contentType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
            if (contentType != null && !isAudioContentType(contentType)) {
                return@withContext null
            }

            val extension = supportedExtension(
                contentDisposition = connection.getHeaderField("Content-Disposition"),
                urlPath = finalUrl.path,
                contentType = contentType
            ) ?: return@withContext null

            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                return@withContext null
            }

            val downloadDirectory = File(context.cacheDir, CACHE_DIRECTORY)
            if (!downloadDirectory.isDirectory && !downloadDirectory.mkdirs()) {
                return@withContext null
            }

            val outputFile = File.createTempFile(FILE_PREFIX, ".$extension", downloadDirectory)
            destination = outputFile
            connection.inputStream.use { input ->
                outputFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    var totalBytes = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        if (bytesRead == 0) continue
                        totalBytes += bytesRead
                        if (totalBytes > MAX_DOWNLOAD_BYTES) {
                            throw IOException("Media exceeds the maximum download size")
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            if (outputFile.length() == 0L) return@withContext null
            completed = true
            DownloadedMedia(outputFile)
        } catch (_: IOException) {
            null
        } finally {
            if (!completed) destination?.delete()
            connection?.disconnect()
        }
    }

    private fun supportedExtension(
        contentDisposition: String?,
        urlPath: String,
        contentType: String?
    ): String? {
        val dispositionFilename = contentDisposition
            ?.let { CONTENT_DISPOSITION_FILENAME.find(it)?.groupValues?.getOrNull(1) }
        listOf(dispositionFilename, urlPath)
            .firstNotNullOfOrNull(::audioExtensionFromName)
            ?.let { return it }

        val mimeType = contentType ?: return null
        val knownExtension = when (mimeType) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/ogg", "application/ogg", "application/x-ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/x-ms-wma" -> "wma"
            "audio/aiff", "audio/x-aiff" -> "aiff"
            else -> null
        }
        val extension = knownExtension
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.lowercase(Locale.ROOT)
        return extension?.takeIf { it in ExtractionConfig.DEFAULT_AUDIO_EXTENSIONS }
    }

    private fun audioExtensionFromName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val filename = Uri.decode(value)
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        return extension.takeIf { it in ExtractionConfig.DEFAULT_AUDIO_EXTENSIONS }
    }

    private fun isAudioContentType(contentType: String): Boolean =
        contentType.startsWith("audio/") ||
            contentType == "application/octet-stream" ||
            contentType == "application/ogg" ||
            contentType == "application/x-ogg"

    private fun URL.isHttpUrl(): Boolean {
        val scheme = protocol.lowercase(Locale.ROOT)
        return (scheme == "http" || scheme == "https") &&
            host.isNotBlank() &&
            userInfo == null
    }

    private companion object {
        const val ACCEPT_HEADER = "audio/*,application/octet-stream;q=0.9,*/*;q=0.8"
        const val BUFFER_SIZE_BYTES = 16 * 1024
        const val CACHE_DIRECTORY = "url-extractions"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val FILE_PREFIX = "futura-audio-"
        const val MAX_DOWNLOAD_BYTES = 500L * 1024L * 1024L
        const val READ_TIMEOUT_MS = 60_000
        const val USER_AGENT = "FuturaAudio/1.0"
        val CONTENT_DISPOSITION_FILENAME = Regex(
            """filename\*?=(?:UTF-8''|\")?([^;\"]+)""",
            RegexOption.IGNORE_CASE
        )
    }
}
