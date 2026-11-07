package com.futura.audio.ui.viewmodel

import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory

/**
 * Single declaration of the Media Hub state. Search and category filtering
 * are derived here, so the screen and the viewmodel read the same rules.
 */
data class MediaHubUiState(
    val allItems: List<MediaItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedCategory: PipelineCategory = PipelineCategory.COMPLIANT_STANDARD,
    val activeWebViewUrl: String? = null, // Non-null when a web embed needs to open in a container
    val downloadingId: String? = null, // Non-null while that item's download runs
    val downloadMessage: String? = null // One-shot result banner after a download
) {
    // Backward compatibility alias so legacy references to `state.items` continue to work
    val items: List<MediaItem> get() = allItems

    val filteredItems: List<MediaItem> get() = allItems.filter { item ->
        val matchesCategory = item.category == selectedCategory
        val matchesQuery = searchQuery.isBlank() ||
            item.title.contains(searchQuery, ignoreCase = true) ||
            (item.artist?.contains(searchQuery, ignoreCase = true) == true) ||
            item.sourceName.contains(searchQuery, ignoreCase = true) ||
            item.subcategory.value.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesQuery
    }
}
