// app/src/main/java/com/futura/data/extraction/model/ExtractionState.kt
package com.futura.data.extraction.model

import java.util.Locale

/**
 * Reactive state emitted by the extraction pipeline.
 *
 * The ViewModel (group 5) exposes a `StateFlow<ExtractionState>` that
 * transitions between these states. UI observes it directly — no manual
 * loading flags, no adapter layer.
 */
sealed interface ExtractionState {

    /** No scan has started yet. */
    data object Idle : ExtractionState

    /** A scan is in progress. [progress] carries granular detail. */
    data class Scanning(val progress: ScanProgress) : ExtractionState

    /**
     * A scan completed successfully. [result] carries both the extracted
     * tracks and the aggregate stats.
     */
    data class Ready(val result: ScanResult) : ExtractionState

    /** A scan failed at the pipeline level (not per-file). */
    data class Failed(val reason: String, val cause: Throwable? = null) : ExtractionState
}

/**
 * Live progress during a scan. Emitted via the `onProgress` callback once per
 * completed file, from worker threads.
 */
data class ScanProgress(
    val filesProcessed: Int,
    val totalFiles: Int,
    val currentFile: String,
    val phase: Phase
) {
    val percent: Float
        get() = if (totalFiles <= 0) 0f
                else (filesProcessed.toFloat() / totalFiles * 100f).coerceIn(0f, 100f)

    enum class Phase {
        DISCOVERING,   // walking the file tree
        ANALYZING,     // extracting metadata per file
        FINALIZING     // computing stats / sorting
    }
}

/**
 * Output of a completed scan. Immutable; produced by
 * `MediaExtractorService.scan(...)`.
 */
data class ScanResult(
    val tracks: List<ExtractedTrack>,
    val stats: ExtractionStats,
    val errors: List<String>
)

/**
 * Aggregate statistics computed once per scan. Used by the UI's summary
 * card and by the export feature (group 4).
 */
data class ExtractionStats(
    val totalTracks: Int,
    val losslessCount: Int,
    val compressedCount: Int,
    val totalBytes: Long,
    val totalDurationMs: Long,
    val errorCount: Int,
    val scanDurationMs: Long
) {
    val totalBytesDisplay: String
        get() = when {
            totalBytes < 1024L -> "$totalBytes B"
            totalBytes < 1024L * 1024L ->
                String.format(Locale.US, "%.1f KB", totalBytes / 1024.0)
            totalBytes < 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0))
            else ->
                String.format(Locale.US, "%.2f GB", totalBytes / (1024.0 * 1024.0 * 1024.0))
        }

    val totalDurationDisplay: String
        get() {
            val seconds = totalDurationMs / 1000L
            val hours = seconds / 3600L
            val minutes = (seconds % 3600L) / 60L
            return if (hours > 0L) "${hours}h ${minutes}m"
                   else "${minutes}m"
        }
}
