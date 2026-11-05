// app/src/main/java/com/futura/data/extraction/model/ExtractionFilter.kt
package com.futura.data.extraction.model

/**
 * Declarative filter over the extracted catalog. Every null field means
 * "don't constrain on this". [matches] is the single source of truth:
 * callers must not shortcut on [ALL], because the default
 * `includeQuarantined = false` also applies to [ALL].
 *
 * A malformed [regexPattern] is treated as "no regex constraint", so the
 * list does not go empty while the user is still typing.
 */
data class ExtractionFilter(
    val textQuery: String? = null,
    val regexPattern: String? = null,
    val regexCaseInsensitive: Boolean = true,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
    val formatWhitelist: Set<String>? = null,
    val category: TrackCategory? = null,
    val minBpm: Float? = null,
    val maxBpm: Float? = null,
    val artistContains: String? = null,
    val albumContains: String? = null,
    val hasDuplicates: Boolean? = null,
    val hasReplayGain: Boolean? = null,
    val hasStems: Boolean? = null,
    val includeQuarantined: Boolean = false,
    val assignedToAlarm: Boolean? = null
) {
    /** Compiled once per instance. Null when no pattern or the pattern is invalid. */
    private val compiledRegex: Regex? by lazy {
        val pattern = regexPattern
        if (pattern.isNullOrBlank()) null
        else try {
            Regex(pattern, if (regexCaseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (_: Exception) {
            null
        }
    }

    fun matches(track: ExtractedTrack): Boolean {
        if (!includeQuarantined && track.quarantined) return false

        val searchable = listOf(track.title, track.artist, track.album, track.fileName)

        textQuery?.takeIf { it.isNotBlank() }?.let { query ->
            if (searchable.none { it.orEmpty().contains(query, ignoreCase = true) }) return false
        }

        compiledRegex?.let { regex ->
            if (searchable.none { regex.containsMatchIn(it.orEmpty()) }) return false
        }

        minSizeBytes?.let { if (track.sizeBytes < it) return false }
        maxSizeBytes?.let { if (track.sizeBytes > it) return false }
        minDurationMs?.let { if (track.durationMs < it) return false }
        maxDurationMs?.let { if (track.durationMs > it) return false }

        formatWhitelist?.takeIf { it.isNotEmpty() }?.let { formats ->
            val allowed = formats.map { it.uppercase() }.toSet()
            if (track.format.uppercase() !in allowed) return false
        }

        category?.let { if (track.category != it) return false }

        if (minBpm != null || maxBpm != null) {
            // Unknown BPM cannot satisfy a BPM constraint.
            val bpm = track.bpm ?: return false
            if (minBpm != null && bpm < minBpm) return false
            if (maxBpm != null && bpm > maxBpm) return false
        }

        artistContains?.takeIf { it.isNotBlank() }?.let { needle ->
            if (!track.artist.orEmpty().contains(needle, ignoreCase = true)) return false
        }
        albumContains?.takeIf { it.isNotBlank() }?.let { needle ->
            if (!track.album.orEmpty().contains(needle, ignoreCase = true)) return false
        }

        hasDuplicates?.let { if (track.isDuplicate != it) return false }
        hasReplayGain?.let { if ((track.replayGainDb != null) != it) return false }
        hasStems?.let { if (track.stemsAvailable != it) return false }
        assignedToAlarm?.let { if (track.assignedToAlarm != it) return false }

        return true
    }

    companion object {
        val ALL: ExtractionFilter = ExtractionFilter()
    }
}
