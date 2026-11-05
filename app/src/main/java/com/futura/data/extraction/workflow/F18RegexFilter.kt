// app/src/main/java/com/futura/data/extraction/workflow/F18RegexFilter.kt
package com.futura.data.extraction.workflow

import com.futura.data.extraction.model.ExtractedTrack
import com.futura.data.extraction.model.ExtractionFilter
import com.futura.data.extraction.model.TrackCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * F18 — Reactive regex & advanced filtering operators.
 *
 * Stateless extension functions over `Flow<List<ExtractedTrack>>`.
 * Group 1 delivered [ExtractionFilter] (the model). These operators
 * apply a filter reactively at the flow level.
 *
 * The F5 (smart categorization) responsibility also lives here per the
 * accountability matrix: a reactive partition into "lossless/high-res"
 * vs. "compressed" is just a `filterByCategory` operator with one of the
 * two [TrackCategory] values. The model side of F5 (extension → category
 * mapping) was delivered in group 1's `TrackCategory.fromExtension`.
 *
 * Every operator is cold — nothing runs until the UI collects. Sort
 * operators use `map` (order-preserving, cheap) rather than `mapLatest`
 * (which would cancel a sort already in progress if a new track arrives
 * mid-sort — pointless for pure in-memory list operations).
 */
object F18RegexFilter {

    /** Apply an [ExtractionFilter] reactively. Always goes through [ExtractionFilter.matches]. */
    fun Flow<List<ExtractedTrack>>.filterByExtraction(
        filter: ExtractionFilter
    ): Flow<List<ExtractedTrack>> = map { list -> list.filter { filter.matches(it) } }

    /** Plain-text filter over title / artist / album / filename. */
    fun Flow<List<ExtractedTrack>>.filterByText(
        query: String
    ): Flow<List<ExtractedTrack>> {
        if (query.isBlank()) return this
        val needle = query.lowercase()
        return map { list ->
            list.filter { t ->
                t.title.orEmpty().lowercase().contains(needle) ||
                    t.artist.orEmpty().lowercase().contains(needle) ||
                    t.album.orEmpty().lowercase().contains(needle) ||
                    t.fileName.lowercase().contains(needle)
            }
        }
    }

    /**
     * Regex filter (F18 core). Applies to title / artist / album /
     * filename. A malformed pattern is treated as "match everything" —
     * better UX than an empty list when the user is mid-type.
     */
    fun Flow<List<ExtractedTrack>>.filterByRegex(
        pattern: String,
        caseInsensitive: Boolean = true
    ): Flow<List<ExtractedTrack>> {
        if (pattern.isBlank()) return this
        val opts = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE)
                   else emptySet()
        val regex = try { Regex(pattern, opts) }
                    catch (_: Exception) { null }
                    ?: return this
        return map { list ->
            list.filter { t ->
                regex.containsMatchIn(t.title.orEmpty()) ||
                    regex.containsMatchIn(t.artist.orEmpty()) ||
                    regex.containsMatchIn(t.album.orEmpty()) ||
                    regex.containsMatchIn(t.fileName)
            }
        }
    }

    /** F5 — categorize the flow into one bucket. */
    fun Flow<List<ExtractedTrack>>.filterByCategory(
        category: TrackCategory
    ): Flow<List<ExtractedTrack>> = map { list ->
        list.filter { it.category == category }
    }

    /** File-size range filter (inclusive). */
    fun Flow<List<ExtractedTrack>>.filterBySize(
        minBytes: Long = 0L,
        maxBytes: Long = Long.MAX_VALUE
    ): Flow<List<ExtractedTrack>> = map { list ->
        list.filter { it.sizeBytes in minBytes..maxBytes }
    }

    /** Duration range filter (inclusive). */
    fun Flow<List<ExtractedTrack>>.filterByDuration(
        minMs: Long = 0L,
        maxMs: Long = Long.MAX_VALUE
    ): Flow<List<ExtractedTrack>> = map { list ->
        list.filter { it.durationMs in minMs..maxMs }
    }

    /** Format whitelist filter. */
    fun Flow<List<ExtractedTrack>>.filterByFormat(
        formats: Set<String>
    ): Flow<List<ExtractedTrack>> {
        if (formats.isEmpty()) return this
        val upper = formats.map { it.uppercase() }.toSet()
        return map { list -> list.filter { it.format.uppercase() in upper } }
    }

    /** Duplicate-only or clean-only filter. */
    fun Flow<List<ExtractedTrack>>.filterDuplicates(
        onlyDuplicates: Boolean
    ): Flow<List<ExtractedTrack>> = map { list ->
        list.filter { it.isDuplicate == onlyDuplicates }
    }

    // ── Sorting ────────────────────────────────────────────────

    enum class SortOrder {
        TITLE_ASC, TITLE_DESC,
        ARTIST_ASC, ARTIST_DESC,
        ALBUM_ASC, ALBUM_DESC,
        DURATION_ASC, DURATION_DESC,
        SIZE_ASC, SIZE_DESC,
        ADDED_ASC, ADDED_DESC,
        BPM_ASC, BPM_DESC
    }

    fun Flow<List<ExtractedTrack>>.sortedBy(order: SortOrder): Flow<List<ExtractedTrack>> =
        map { list ->
            when (order) {
                SortOrder.TITLE_ASC -> list.sortedBy { it.title.orEmpty().lowercase() }
                SortOrder.TITLE_DESC -> list.sortedByDescending { it.title.orEmpty().lowercase() }
                SortOrder.ARTIST_ASC -> list.sortedBy { it.artist.orEmpty().lowercase() }
                SortOrder.ARTIST_DESC -> list.sortedByDescending { it.artist.orEmpty().lowercase() }
                SortOrder.ALBUM_ASC -> list.sortedBy { it.album.orEmpty().lowercase() }
                SortOrder.ALBUM_DESC -> list.sortedByDescending { it.album.orEmpty().lowercase() }
                SortOrder.DURATION_ASC -> list.sortedBy { it.durationMs }
                SortOrder.DURATION_DESC -> list.sortedByDescending { it.durationMs }
                SortOrder.SIZE_ASC -> list.sortedBy { it.sizeBytes }
                SortOrder.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
                SortOrder.ADDED_ASC -> list.sortedBy { it.addedAtMs }
                SortOrder.ADDED_DESC -> list.sortedByDescending { it.addedAtMs }
                SortOrder.BPM_ASC -> list.sortedBy { it.bpm ?: Float.MAX_VALUE }
                SortOrder.BPM_DESC -> list.sortedByDescending { it.bpm ?: 0f }
            }
        }
}
