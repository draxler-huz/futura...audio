// app/src/main/java/com/futura/data/extraction/workflow/F17BatchActionManager.kt
package com.futura.data.extraction.workflow

import android.util.Log
import com.futura.data.extraction.AudioTranscoder
import com.futura.data.extraction.CorruptFileQuarantine
import com.futura.data.extraction.DuplicateFinder
import com.futura.data.extraction.ReplayGainHook
import com.futura.data.extraction.model.ExtractedTrack
import java.io.File
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
 * F17 — Batch Action Pipeline & Maintenance Manager.
 *
 * Orchestrator for the batch operations in the accountability matrix:
 *   • F2  — duplicate detection & merge
 *   • F3  — batch quarantine
 *   • F12 — batch LUFS / ReplayGain normalization
 *   • F14 — batch transcode
 *   • F17 — batch delete / tag
 *
 * Delegates the heavy lifting to the group 1–3 primitives rather than
 * re-implementing them. The blueprint's 8KB hash was structurally
 * unsafe for duplicate detection — `DuplicateFinder` (group 2) does
 * head+middle+tail chunked MD5 which is the correct approach. The
 * blueprint's `renameTo` quarantine also fails across filesystems;
 * `CorruptFileQuarantine` (group 2) handles the copy+delete fallback.
 *
 * Rule 6: every batch method wraps per-item failures so one bad file
 * does not abort the batch. Cancellation is always rethrown.
 * Rule 7: [mutex] serializes concurrent batch runs — two UI batches
 * cannot interleave and corrupt progress state.
 */
@Singleton
class F17BatchActionManager @Inject constructor(
    private val duplicateFinder: DuplicateFinder,
    private val quarantine: CorruptFileQuarantine,
    private val transcoder: AudioTranscoder,
    private val replayGain: ReplayGainHook
) {
    private val tag = "F17BatchActionManager"
    private val mutex = Mutex()

    /** Live progress of the currently running batch, if any. */
    data class BatchProgress(
        val operation: String,
        val processed: Int,
        val total: Int,
        val currentFile: String?
    ) {
        val percent: Float get() = if (total <= 0) 0f
                else (processed.toFloat() / total * 100f).coerceIn(0f, 100f)
    }

    private val _progress = MutableStateFlow<BatchProgress?>(null)
    val progress: StateFlow<BatchProgress?> = _progress.asStateFlow()

    // ── F2 — Duplicate detection ─────────────────────────────────

    suspend fun batchDedupe(
        tracks: List<ExtractedTrack>
    ): List<DuplicateFinder.DuplicateGroup> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                _progress.value = BatchProgress("dedupe", 0, tracks.size, null)
                val groups = duplicateFinder.findDuplicates(tracks) { processed, total ->
                    _progress.value = BatchProgress("dedupe", processed, total, null)
                }
                Log.i(tag, "batchDedupe: ${groups.size} duplicate groups in ${tracks.size} tracks")
                groups
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "batchDedupe failed", e)
                emptyList()
            } finally {
                _progress.value = null
            }
        }
    }

    // ── F3 — Quarantine ─────────────────────────────────────────

    /**
     * Quarantine [files]. The caller must have obtained user confirmation
     * before invoking this — the manager does not prompt.
     */
    suspend fun batchQuarantine(
        files: List<File>,
        reason: String = "batch_quarantine"
    ): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                _progress.value = BatchProgress("quarantine", 0, files.size, null)
                var count = 0
                files.forEachIndexed { index, file ->
                    val ok = try { quarantine.quarantine(file, reason) }
                             catch (e: CancellationException) { throw e }
                             catch (e: Exception) {
                                 Log.w(tag, "quarantine threw for ${file.name}", e)
                                 false
                             }
                    if (ok) count++
                    _progress.value = BatchProgress(
                        "quarantine", index + 1, files.size, file.name
                    )
                }
                Log.i(tag, "batchQuarantine: $count/${files.size} quarantined")
                count
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "batchQuarantine failed", e)
                0
            } finally {
                _progress.value = null
            }
        }
    }

    // ── F12 — ReplayGain ────────────────────────────────────────

    suspend fun batchNormalize(
        tracks: List<ExtractedTrack>
    ): Map<String, ReplayGainHook.ReplayGainResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val results = mutableMapOf<String, ReplayGainHook.ReplayGainResult>()
            try {
                _progress.value = BatchProgress("normalize", 0, tracks.size, null)
                tracks.forEachIndexed { index, track ->
                    try {
                        val file = File(track.filePath)
                        if (!file.exists() || !file.canRead()) {
                            Log.w(tag, "batchNormalize: skip missing ${track.fileName}")
                        } else {
                            replayGain.analyze(file)?.let { results[track.id] = it }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(tag, "batchNormalize threw for ${track.fileName}", e)
                    }
                    _progress.value = BatchProgress(
                        "normalize", index + 1, tracks.size, track.fileName
                    )
                }
                Log.i(tag, "batchNormalize: ${results.size}/${tracks.size} analysed")
                results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "batchNormalize failed", e)
                results
            } finally {
                _progress.value = null
            }
        }
    }

    // ── F14 — Transcode ────────────────────────────────────────

    suspend fun batchTranscode(
        tracks: List<ExtractedTrack>,
        target: AudioTranscoder.TargetFormat = AudioTranscoder.TargetFormat.AAC_192,
        outputDir: File
    ): List<AudioTranscoder.Result> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val results = mutableListOf<AudioTranscoder.Result>()
            try {
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    Log.e(tag, "batchTranscode: cannot create output dir ${outputDir.absolutePath}")
                    return@withContext results
                }
                _progress.value = BatchProgress("transcode", 0, tracks.size, null)
                tracks.forEachIndexed { index, track ->
                    try {
                        val source = File(track.filePath)
                        if (!source.exists() || !source.canRead()) {
                            Log.w(tag, "batchTranscode: skip missing ${track.fileName}")
                        } else {
                            transcoder.transcode(source, outputDir, target)
                                ?.let { results.add(it) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(tag, "batchTranscode threw for ${track.fileName}", e)
                    }
                    _progress.value = BatchProgress(
                        "transcode", index + 1, tracks.size, track.fileName
                    )
                }
                Log.i(tag, "batchTranscode: ${results.size}/${tracks.size} transcoded")
                results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "batchTranscode failed", e)
                results
            } finally {
                _progress.value = null
            }
        }
    }

    // ── F17 — Batch delete ─────────────────────────────────────

    /**
     * Permanently delete the files behind [tracks]. Destructive: the
     * caller must have obtained explicit user confirmation first.
     */
    suspend fun batchDelete(tracks: List<ExtractedTrack>): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                _progress.value = BatchProgress("delete", 0, tracks.size, null)
                var deleted = 0
                tracks.forEachIndexed { index, track ->
                    try {
                        val file = File(track.filePath)
                        if (file.exists() && file.delete()) deleted++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(tag, "batchDelete threw for ${track.fileName}", e)
                    }
                    _progress.value = BatchProgress(
                        "delete", index + 1, tracks.size, track.fileName
                    )
                }
                Log.i(tag, "batchDelete: $deleted/${tracks.size} deleted")
                deleted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "batchDelete failed", e)
                0
            } finally {
                _progress.value = null
            }
        }
    }
}
