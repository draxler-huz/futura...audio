// app/src/main/java/com/futura/data/extraction/ExtractionEvents.kt
package com.futura.data.extraction

/** One-shot events from the extraction pipeline, consumed by the UI to toast. */
sealed interface ExtractionEvents {

    // Scan lifecycle
    data class ScanStarted(val pathCount: Int) : ExtractionEvents
    data class ScanProgressed(val processed: Int, val total: Int) : ExtractionEvents
    data class ScanCompleted(
        val trackCount: Int,
        val errorCount: Int,
        val durationMs: Long
    ) : ExtractionEvents
    data class ScanFailed(val reason: String) : ExtractionEvents

    // Batch lifecycle
    data class BatchStarted(val operation: String, val total: Int) : ExtractionEvents
    data class BatchProgressed(
        val operation: String,
        val processed: Int,
        val total: Int
    ) : ExtractionEvents
    data class BatchCompleted(val operation: String, val processed: Int) : ExtractionEvents
    data class BatchFailed(val operation: String, val reason: String) : ExtractionEvents

    // Per-track enrichment
    data class TrackEnriched(
        val trackId: String,
        val kind: EnrichmentKind
    ) : ExtractionEvents
    data class TrackEnrichmentFailed(
        val trackId: String,
        val kind: EnrichmentKind,
        val reason: String
    ) : ExtractionEvents
    enum class EnrichmentKind { TELEMETRY, ARTWORK }

    // Alarm assignment
    data class AlarmAssigned(val trackId: String, val specId: String) : ExtractionEvents
    data class AlarmAssignmentFailed(
        val trackId: String,
        val reason: String
    ) : ExtractionEvents
    data class AlarmUnbound(val specId: String) : ExtractionEvents

    // Export / sync
    data class ExportCompleted(val filePath: String, val format: ExportFormat) : ExtractionEvents
    data class ExportFailed(val reason: String) : ExtractionEvents
    enum class ExportFormat { JSON, CSV }

    data class SyncPushed(val remoteKey: String?) : ExtractionEvents
    data class SyncFailed(val reason: String) : ExtractionEvents
}
