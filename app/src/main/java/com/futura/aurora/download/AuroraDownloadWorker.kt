package com.futura.aurora.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Downloads one file into filesDir/aurora. Uses parallel ranged requests when the
 * server allows them, and resumes from saved progress after a failure. Otherwise
 * it streams the file in one request.
 */
@HiltWorker
class AuroraDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val client: HttpClient
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val attribution = inputData.getString(KEY_ATTRIBUTION).orEmpty()
        if (!DownloadRules.isAllowed(url) || !DownloadRules.isSafeFileName(fileName)) return Result.failure()

        return try {
            setForeground(foregroundInfo(fileName, 0))
            var lastShown = 0
            val target = download(url, fileName) { percent ->
                setProgress(workDataOf(KEY_PROGRESS to percent))
                if (percent >= lastShown + 5) {
                    lastShown = percent
                    setForeground(foregroundInfo(fileName, percent))
                }
            }
            Result.success(workDataOf(KEY_PATH to target.absolutePath, KEY_ATTRIBUTION to attribution))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadRejected) {
            Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Network error")))
            }
        }
    }

    private suspend fun download(url: String, fileName: String, progress: suspend (Int) -> Unit): File {
        val dir = File(applicationContext.filesDir, OUTPUT_DIR).apply { mkdirs() }
        val target = File(dir, fileName)
        val part = File(dir, "$fileName.part")
        val stateFile = File(dir, "$fileName.state.json")

        val probe = try {
            client.head(url)
        } catch (e: IOException) {
            null
        }?.takeIf { it.status.isSuccess() }
        val total = probe?.headers?.get(HttpHeaders.ContentLength)?.toLongOrNull()
        if (total != null && total > DownloadRules.MAX_BYTES) throw DownloadRejected("File is larger than 1 GB")
        val ranged = probe != null && total != null && total > 0 &&
            probe.headers[HttpHeaders.AcceptRanges].equals("bytes", ignoreCase = true)

        if (ranged && probe != null && total != null) {
            downloadRanged(url, total, probe, part, stateFile, progress)
        } else {
            streamWhole(url, part, total, progress)
        }
        stateFile.delete()

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Could not save the file")
        progress(100)
        return target
    }

    private suspend fun downloadRanged(
        url: String,
        total: Long,
        probe: HttpResponse,
        part: File,
        stateFile: File,
        progress: suspend (Int) -> Unit
    ) {
        val etag = probe.headers[HttpHeaders.ETag]
        val lastModified = probe.headers[HttpHeaders.LastModified]
        val chunks = DownloadPlan.chunks(total)

        val saved = DownloadState.read(stateFile)
        val state = saved?.takeIf {
            it.matches(url, total, etag, lastModified, chunks.size) && part.length() == total
        } ?: run {
            part.delete()
            stateFile.delete()
            DownloadState(url, etag, lastModified, total, LongArray(chunks.size))
        }
        RandomAccessFile(part, "rw").use { it.setLength(total) }

        val lock = Mutex()
        val sinceSave = LongArray(chunks.size)
        coroutineScope {
            chunks.mapIndexed { index, range ->
                async {
                    val start = range.first + state.done[index]
                    if (start > range.last) return@async
                    fetchChunk(url, part, start, range.last) { bytes ->
                        val percent = lock.withLock {
                            state.done[index] += bytes.toLong()
                            sinceSave[index] += bytes.toLong()
                            if (sinceSave[index] >= CHECKPOINT_BYTES) {
                                stateFile.writeText(state.toJson())
                                sinceSave[index] = 0L
                            }
                            (state.done.sum() * 100 / total).toInt()
                        }
                        progress(percent)
                    }
                    lock.withLock { stateFile.writeText(state.toJson()) }
                }
            }.awaitAll()
        }
        if (state.done.sum() != total) throw IOException("Incomplete download")
    }

    private suspend fun fetchChunk(
        url: String,
        part: File,
        start: Long,
        last: Long,
        onBytes: suspend (Int) -> Unit
    ) {
        client.prepareGet(url) {
            headers.append(HttpHeaders.Range, "bytes=$start-$last")
        }.execute { response ->
            if (response.status != HttpStatusCode.PartialContent) {
                throw IOException("Server ignored the range request (${response.status.value})")
            }
            RandomAccessFile(part, "rw").use { raf ->
                var position = start
                pump(response.bodyAsChannel(), { buffer, count ->
                    raf.seek(position)
                    raf.write(buffer, 0, count)
                    position += count
                }, onBytes)
            }
        }
    }

    private suspend fun streamWhole(url: String, part: File, total: Long?, progress: suspend (Int) -> Unit) {
        part.delete()
        var written = 0L
        client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}")
            part.outputStream().use { out ->
                pump(response.bodyAsChannel(), { buffer, count -> out.write(buffer, 0, count) }) { count ->
                    written += count
                    if (written > DownloadRules.MAX_BYTES) throw DownloadRejected("File is larger than 1 GB")
                    if (total != null && total > 0) progress((written * 100 / total).toInt())
                }
            }
        }
        if (total != null && written != total) throw IOException("Incomplete download")
    }

    private suspend fun pump(
        channel: ByteReadChannel,
        write: (ByteArray, Int) -> Unit,
        onBytes: suspend (Int) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read > 0) {
                write(buffer, read)
                onBytes(read)
            }
        }
    }

    private fun foregroundInfo(name: String, percent: Int): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, percent == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aurora downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_ATTRIBUTION = "attribution"
        const val KEY_PROGRESS = "progress"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
        const val OUTPUT_DIR = "aurora"
        const val CHANNEL_ID = "aurora_downloads"
        const val MAX_ATTEMPTS = 4
        const val BUFFER_BYTES = 64 * 1024
        const val CHECKPOINT_BYTES = 4L * 1024 * 1024
    }
}
