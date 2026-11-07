package com.futura.ui.screens.hurzy

import com.futura.sources.SourceMediaItem

/**
 * Everything the Hurzy feed draws. Held in one place so the screen stays
 * presentational and the same rules drive the grid, the empty state, and
 * the progress indicator.
 */
data class HurzyUiState(
    val query: String = "",
    /** True while a search or the initial feed load is running. */
    val loading: Boolean = false,
    /** True once a search has been run, so an empty list can say "no matches". */
    val searched: Boolean = false,
    /** The results to show, already filtered to playable items. */
    val items: List<SourceMediaItem> = emptyList(),
    /** Sources that errored, shown as a quiet footnote. */
    val failedSources: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = items.isEmpty()
}
