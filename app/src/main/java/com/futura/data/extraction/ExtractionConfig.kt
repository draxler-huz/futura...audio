// app/src/main/java/com/futura/data/extraction/ExtractionConfig.kt
package com.futura.data.extraction

/**
 * Configuration for one scan.
 *
 * The [customPaths] list is the driving input for **Feature #1
 * (multi-directory custom scope)** — callers append user-selected
 * directories and external SD card mount paths. It must not be empty;
 * use [defaultsFor] to start from the primary music directory.
 *
 * Every feature flag maps to a phase:
 *   • quarantineEnabled → Feature 3
 *   • fingerprintingEnabled → Feature 2 (executed by DuplicateFinder)
 *   • extractAlbumArt → Feature 6
 *   • computeTelemetry → Features 7, 8, 9, 10
 *   • watchDirectories → Feature 4
 */
data class ExtractionConfig(
    val customPaths: List<String>,
    /** Max concurrent file analyses. Default 4. */
    val concurrency: Int = 4,
    /** Per-file size ceiling; files above this are skipped. Default 500 MB. */
    val maxFileSizeBytes: Long = 500L * 1024L * 1024L,
    /** Audio file extensions the scanner accepts, lowercase, without the dot. */
    val supportedExtensions: Set<String> = DEFAULT_AUDIO_EXTENSIONS,
    /** Feature 3 — flag corrupt/unreadable files. They are moved only after the user confirms. */
    val quarantineEnabled: Boolean = true,
    /** Subdirectory under `context.filesDir` where quarantined files go. Single name, no separators. */
    val quarantineDirectoryName: String = "quarantine",
    /** Feature 2 — compute fingerprints for duplicate detection. */
    val fingerprintingEnabled: Boolean = true,
    /** Feature 6 — extract and cache album art during scan. */
    val extractAlbumArt: Boolean = true,
    /** Features 7, 8, 9, 10 — run BPM / waveform / silence / bitrate. */
    val computeTelemetry: Boolean = true,
    /** Feature 4 — install FileObserver on scanned directories. */
    val watchDirectories: Boolean = true
) {
    init {
        require(customPaths.isNotEmpty()) {
            "customPaths must contain at least one directory; an empty scope would scan nothing"
        }
        require(concurrency in 1..16) { "concurrency must be in [1, 16]" }
        require(maxFileSizeBytes > 0L) { "maxFileSizeBytes must be positive" }
        require(supportedExtensions.isNotEmpty()) {
            "supportedExtensions must contain at least one extension"
        }
        require(quarantineDirectoryName.isNotBlank() && !quarantineDirectoryName.contains('/')) {
            "quarantineDirectoryName must be a single directory name"
        }
    }

    companion object {
        /** Standard audio extensions across Android and lossless formats. */
        val DEFAULT_AUDIO_EXTENSIONS: Set<String> = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
            "wma", "aiff", "aif", "alac", "dsf", "dff"
        )

        /** Convenience: config with only the platform's default music folder. */
        fun defaultsFor(primaryMusicDir: String): ExtractionConfig =
            ExtractionConfig(customPaths = listOf(primaryMusicDir))
    }
}
