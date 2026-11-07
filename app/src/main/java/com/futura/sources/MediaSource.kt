package com.futura.sources

/**
 * One provider of search results. Implementations must only return items
 * whose license they can state, and must never return a full copyrighted
 * track from a metadata-only source.
 *
 * A source that throws is caught by the repository, logged, and treated as
 * returning no results, so one broken provider never hides the others.
 */
interface MediaSource {
    val config: SourceConfig

    suspend fun search(query: String, filter: SearchFilter): List<SourceMediaItem>
}

/**
 * The filters applied to a search. Defaults match the "safe by default"
 * posture: only downloadable, commercially usable items, and no live radio
 * unless the user asks for it.
 */
data class SearchFilter(
    val fileType: SourceFileType? = null,
    val commercialOnly: Boolean = true,
    val includeRadio: Boolean = false,
    val includeMetadataLinks: Boolean = false,
    val requireDownloadable: Boolean = true,
    val bpmRange: IntRange? = null,
    val hasLyrics: Boolean = false,
    val limit: Int = 20
) {
    /** True when [item] may be shown under these filters. */
    fun allows(item: SourceMediaItem): Boolean {
        if (item.isLive && !includeRadio) return false
        if (item.sourceId.startsWith("meta.") && !includeMetadataLinks) return false
        if (commercialOnly && !item.license.commercialUseAllowed) return false
        if (requireDownloadable && !item.downloadable) return false
        if (bpmRange != null && (item.bpm == null || item.bpm !in bpmRange)) return false
        return true
    }
}
