// app/src/main/java/com/futura/data/extraction/MediaExtractorService.kt
package com.futura.data.extraction

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.futura.data.extraction.model.ExtractedTrack
import com.futura.data.extraction.model.ExtractionStats
import com.futura.data.extraction.model.QuarantineCandidate
import com.futura.data.extraction.model.ScanProgress
import com.futura.data.extraction.model.ScanResult
import com.futura.data.extraction.model.TrackCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Multi-directory audio scanner.
 *
 * Walks every path in [ExtractionConfig.customPaths], analyzes each
 * audio file with [MediaMetadataRetriever], categorizes by extension,
 * and quarantines corrupt files.
 *
 * Threading: uses `Dispatchers.IO` with a bounded concurrency
 * [Semaphore]. Every per-file analysis runs in its own coroutine; a
 * single slow file does not block the rest. [onProgress] is called once
 * per completed file, from worker threads, so callers must be thread-safe.
 *
 * Rule 6: every per-file analysis is wrapped — a corrupt file records an
 * error and moves on; the scan never aborts on a single failure.
 * Rule 7: [mutex] serializes concurrent [scan] calls so two callers do
 * not quarantine the same file to different destinations.
 *
 * **GROWTH (group 2):** the inline [quarantine] helper will extract to
 * `CorruptFileQuarantine.kt` once delivered. The scanner's
 * fingerprinting / telemetry fields are declared on [ExtractedTrack] but
 * not populated here — those land in group 2 via `DuplicateFinder` and
 * `AudioTelemetryHelper`.
 */
@Singleton
class MediaExtractorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val quarantine: CorruptFileQuarantine
) {
    private val tag = "MediaExtractorService"
    private val mutex = Mutex()

    /**
     * Scan every path in [config]. Returns after all files have been
     * analyzed. Emits [onProgress] at most once per completed file.
     */
    suspend fun scan(
        config: ExtractionConfig,
        onProgress: (ScanProgress) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                performScan(config, onProgress)
            } catch (e: CancellationException) {
                // A cancelled scan must stop, not report itself as a failed result.
                throw e
            } catch (e: Exception) {
                Log.e(tag, "scan failed", e)
                ScanResult(
                    tracks = emptyList(),
                    stats = ExtractionStats(
                        totalTracks = 0,
                        losslessCount = 0,
                        compressedCount = 0,
                        totalBytes = 0L,
                        totalDurationMs = 0L,
                        errorCount = 1,
                        scanDurationMs = 0L
                    ),
                    errors = listOf("scan failed: ${e.message}")
                )
            }
        }
    }

    // ── Internals ──────────────────────────────────────────────────

    private suspend fun performScan(
        config: ExtractionConfig,
        onProgress: (ScanProgress) -> Unit
    ): ScanResult {
        val startMs = System.currentTimeMillis()

        // Phase 1 — discover files.
        onProgress(ScanProgress(0, 0, "", ScanProgress.Phase.DISCOVERING))
        val allFiles = collectFiles(config)
        val total = allFiles.size
        Log.i(tag, "Discovered $total audio files across ${config.customPaths.size} paths")

        // Phase 2 — analyze in parallel with bounded concurrency.
        val tracks = Collections.synchronizedList(mutableListOf<ExtractedTrack>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val candidates = Collections.synchronizedList(mutableListOf<QuarantineCandidate>())
        val processed = AtomicInteger(0)
        val semaphore = Semaphore(config.concurrency.coerceIn(1, 16))

        coroutineScope {
            for (file in allFiles) {
                launch {
                    semaphore.withPermit {
                        try {
                            val track = analyzeFile(file, config, candidates)
                            if (track != null) tracks.add(track)
                        } catch (e: Exception) {
                            Log.w(tag, "analyzeFile threw for ${file.name}", e)
                            errors.add("${file.name}: ${e.message}")
                        }
                        val done = processed.incrementAndGet()
                        onProgress(
                            ScanProgress(
                                filesProcessed = done,
                                totalFiles = total,
                                currentFile = file.name,
                                phase = ScanProgress.Phase.ANALYZING
                            )
                        )
                    }
                }
            }
        }

        // Phase 3 — finalize.
        onProgress(ScanProgress(total, total, "", ScanProgress.Phase.FINALIZING))

        val sortedTracks = tracks.sortedWith(
            compareBy<ExtractedTrack> { it.artist.orEmpty().lowercase() }
                .thenBy { it.album.orEmpty().lowercase() }
                .thenBy { it.fileName.lowercase() }
        )

        val stats = ExtractionStats(
            totalTracks = sortedTracks.size,
            losslessCount = sortedTracks.count { it.category == TrackCategory.LOSSLESS },
            compressedCount = sortedTracks.count { it.category == TrackCategory.COMPRESSED },
            totalBytes = sortedTracks.sumOf { it.sizeBytes },
            totalDurationMs = sortedTracks.sumOf { it.durationMs },
            errorCount = errors.size,
            scanDurationMs = System.currentTimeMillis() - startMs
        )

        Log.i(
            tag,
            "Scan complete: ${stats.totalTracks} tracks " +
                "(${stats.losslessCount} lossless, ${stats.compressedCount} compressed), " +
                "${stats.errorCount} errors, ${stats.scanDurationMs}ms"
        )

        return ScanResult(
            tracks = sortedTracks,
            stats = stats,
            errors = errors.toList(),
            quarantineCandidates = candidates.toList()
        )
    }

    /**
     * Walk every configured path. Handles both file and directory roots.
     * Skips dot-directories (`.thumbnails`, `.nomedia` caches). A file
     * reachable from two overlapping roots is listed once.
     */
    private fun collectFiles(config: ExtractionConfig): List<File> {
        val result = mutableListOf<File>()
        for (path in config.customPaths) {
            try {
                val dir = File(path)
                if (!dir.exists()) {
                    Log.w(tag, "Path does not exist: $path")
                    continue
                }
                if (dir.isFile) {
                    if (isAudioFile(dir, config)) result.add(dir)
                } else if (dir.isDirectory) {
                    dir.walkTopDown()
                        .onEnter { !it.name.startsWith(".") }
                        .filter { it.isFile && it.canRead() && isAudioFile(it, config) }
                        .forEach { result.add(it) }
                }
            } catch (e: Exception) {
                Log.w(tag, "collectFiles failed for $path", e)
            }
        }
        return result.distinctBy { it.absolutePath }
    }

    private fun isAudioFile(file: File, config: ExtractionConfig): Boolean {
        val ext = file.extension.lowercase()
        return ext in config.supportedExtensions
    }

    /**
     * Extract metadata for one file. Returns null if the file should be
     * skipped (too large, not readable, or corrupt).
     */
    private fun analyzeFile(
        file: File,
        config: ExtractionConfig,
        candidates: MutableList<QuarantineCandidate>
    ): ExtractedTrack? {
        if (!file.exists() || !file.canRead()) return null
        if (file.length() > config.maxFileSizeBytes) {
            Log.i(tag, "Skipping oversized file: ${file.name} (${file.length()} bytes)")
            return null
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            val durationStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                // Duration = 0 is the reliable signal of a truncated or
                // corrupt header — MediaMetadataRetriever will not throw
                // on some formats, it just returns 0.
                if (config.quarantineEnabled) {
                    candidates.add(QuarantineCandidate(file.absolutePath, "zero_duration"))
                }
                return null
            }

            val bitrateBps = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull() ?: 0

            val title = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_TITLE
            )?.takeIf { it.isNotBlank() }
            val artist = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ARTIST
            )?.takeIf { it.isNotBlank() }
            val album = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ALBUM
            )?.takeIf { it.isNotBlank() }

            val ext = file.extension.lowercase()
            val category = TrackCategory.fromExtension(ext)

            return ExtractedTrack(
                id = stableIdFor(file.absolutePath),
                filePath = file.absolutePath,
                fileName = file.name,
                sizeBytes = file.length(),
                durationMs = durationMs,
                bitrateKbps = bitrateBps / 1000,
                format = ext.uppercase(),
                category = category,
                title = title,
                artist = artist,
                album = album,
                addedAtMs = System.currentTimeMillis(),
                lastScannedAtMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            // MediaMetadataRetriever throws IllegalArgumentException for
            // files it cannot parse, IOException for unreadable sources.
            Log.w(tag, "Metadata extraction failed for ${file.name}", e)
            if (config.quarantineEnabled) {
                candidates.add(
                    QuarantineCandidate(file.absolutePath, e.javaClass.simpleName + ": " + (e.message ?: ""))
                )
            }
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Moves the files the user confirmed into the app-private quarantine
     * directory. [scan] never moves anything itself. Returns how many moved.
     */
    suspend fun quarantineConfirmed(
        confirmed: List<QuarantineCandidate>,
        config: ExtractionConfig
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            confirmed.count { candidate ->
                quarantine.quarantine(
                    File(candidate.filePath),
                    candidate.reason,
                    config.quarantineDirectoryName
                )
            }
        }
    }

    /**
     * Stable path-based id: SHA-256 of the absolute path, hex-encoded.
     * The same path gives the same id across process restarts. A 32-bit
     * hashCode collided after roughly 77k files, and equal ids make
     * distinct tracks compare equal, so the full digest is used instead.
     */
    private fun stableIdFor(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
