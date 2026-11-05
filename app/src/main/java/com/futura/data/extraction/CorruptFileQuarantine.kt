// app/src/main/java/com/futura/data/extraction/CorruptFileQuarantine.kt
package com.futura.data.extraction

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Moves corrupt or unreadable audio files into an app-private quarantine
 * directory.
 *
 * Feature 3 of the extraction pipeline. Extracted from
 * `MediaExtractorService.quarantine` (group 1's GROWTH marker) so the
 * scanner and any future analyzer can share one quarantine destination
 * and one quarantined-files log.
 *
 * A quarantined file is preserved (not deleted) so the user can
 * investigate or restore it. It sits under `filesDir`, outside the
 * scanned music folders, so later scans never see it.
 *
 * Rule 6: every file operation wrapped.
 * Rule 7: [mutex] serializes concurrent quarantines so two scan
 * coroutines cannot rename the same file to different destinations.
 */
@Singleton
class CorruptFileQuarantine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "CorruptFileQuarantine"
    private val mutex = Mutex()

    data class QuarantinedFile(
        val originalPath: String,
        val quarantinePath: String,
        val reason: String,
        val quarantinedAtMs: Long
    )

    private val _quarantined = MutableStateFlow<List<QuarantinedFile>>(emptyList())
    val quarantined: StateFlow<List<QuarantinedFile>> = _quarantined.asStateFlow()

    /**
     * Move [file] into quarantine.
     *
     * @return true if the file was successfully moved; false if the file
     *         no longer exists, is unreadable, or the move failed.
     */
    suspend fun quarantine(
        file: File,
        reason: String,
        dirName: String = DEFAULT_DIR_NAME
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!file.exists()) {
                    Log.w(tag, "quarantine: file does not exist: ${file.absolutePath}")
                    return@withLock false
                }
                val dir = quarantineDir(dirName)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(tag, "quarantine: cannot create dir ${dir.absolutePath}")
                    return@withLock false
                }
                val dest = File(
                    dir,
                    "${UUID.randomUUID()}_${file.name}"
                )
                if (!file.renameTo(dest)) {
                    // Fallback: copy + delete if rename failed (cross-filesystem).
                    try {
                        file.copyTo(dest, overwrite = false)
                        if (!file.delete()) {
                            // Original still present: drop the copy so the file is not duplicated.
                            dest.delete()
                            Log.w(tag, "quarantine: could not delete ${file.absolutePath}")
                            return@withLock false
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(tag, "quarantine: copy+delete failed", e)
                        dest.delete()
                        return@withLock false
                    }
                }
                _quarantined.value = _quarantined.value + QuarantinedFile(
                    originalPath = file.absolutePath,
                    quarantinePath = dest.absolutePath,
                    reason = reason,
                    quarantinedAtMs = System.currentTimeMillis()
                )
                Log.i(tag, "Quarantined ${file.name}: $reason")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "quarantine failed for ${file.absolutePath}", e)
                false
            }
        }
    }

    /** Delete everything in the quarantine directory. Returns count removed. */
    suspend fun purgeAll(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val dir = quarantineDir(DEFAULT_DIR_NAME)
                if (!dir.exists()) return@withLock 0
                var removed = 0
                dir.listFiles()?.forEach { f ->
                    try { if (f.delete()) removed++ } catch (_: Exception) {}
                }
                _quarantined.value = emptyList()
                removed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "purgeAll failed", e)
                0
            }
        }
    }

    fun quarantineDir(dirName: String = DEFAULT_DIR_NAME): File =
        File(context.filesDir, dirName)

    companion object {
        const val DEFAULT_DIR_NAME = "quarantine"
    }
}