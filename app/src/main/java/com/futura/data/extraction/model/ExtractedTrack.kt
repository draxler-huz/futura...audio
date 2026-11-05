// app/src/main/java/com/futura/data/extraction/model/ExtractedTrack.kt
package com.futura.data.extraction.model

/**
 * One audio file discovered by the extraction pipeline.
 *
 * Fields are populated progressively:
 *   • Group 1 (this batch) fills identity + basic metadata from
 *     MediaMetadataRetriever: path, size, duration, bitrate, title,
 *     artist, album, category.
 *   • Group 2 fills: fingerprint, bpm, waveform, silence, vbr flag,
 *     album art path.
 *   • Group 3 fills: replay gain, loop markers, stem state.
 *   • Group 4 fills: duplicateOf, quarantined, assignedToAlarm flags.
 *
 * Nullable fields are exactly the ones a later phase populates.
 */
data class ExtractedTrack(
    /** Stable identifier derived from path. Same file → same id. */
    val id: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val bitrateKbps: Int,
    val format: String,
    val category: TrackCategory,
    val title: String?,
    val artist: String?,
    val album: String?,
    val addedAtMs: Long,
    val lastScannedAtMs: Long,

    // Populated by group 2.
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val bitDepth: Int? = null,
    val isVbr: Boolean? = null,
    val fingerprint: String? = null,
    val albumArtPath: String? = null,
    val bpm: Float? = null,
    val waveformPreview: FloatArray? = null,
    val silentLeadingMs: Long? = null,
    val silentTrailingMs: Long? = null,

    // Populated by group 3.
    val replayGainDb: Float? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val fadeCurveMs: Long? = null,
    val stemsAvailable: Boolean = false,

    // Populated by group 4.
    val duplicateOf: String? = null,
    val quarantined: Boolean = false,
    val quarantineReason: String? = null,
    val assignedToAlarm: Boolean = false,

    // Set from MediaMetadataRetriever's HAS_VIDEO key. Video files play with a picture.
    val isVideo: Boolean = false
) {
    /** True if this track has been flagged as a duplicate of another. */
    val isDuplicate: Boolean get() = duplicateOf != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedTrack) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/** Coarse format bucket for UI grouping and filter chips. */
enum class TrackCategory {
    /** .flac, .wav, .aiff, .alac, .dsf, .dff */
    LOSSLESS,
    /** .mp3, .m4a, .aac, .ogg, .opus, .wma */
    COMPRESSED;

    companion object {
        private val LOSSLESS_EXTENSIONS = setOf(
            "flac", "wav", "aiff", "aif", "alac", "dsf", "dff"
        )

        /** [ext] is the bare extension without the dot, e.g. "flac". */
        fun fromExtension(ext: String): TrackCategory =
            if (ext.lowercase() in LOSSLESS_EXTENSIONS) LOSSLESS else COMPRESSED
    }
}
