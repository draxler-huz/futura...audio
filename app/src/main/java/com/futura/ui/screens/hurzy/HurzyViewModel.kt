package com.futura.ui.screens.hurzy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.audio.playback.ExoPlayerQueueManager
import com.futura.sources.SearchFilter
import com.futura.sources.SearchRepository
import com.futura.sources.SourceFileType
import com.futura.sources.SourceMediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Hurzy discovery feed with real results from every enabled
 * source. The screen keeps no state of its own.
 *
 * On open it loads a default feed so the tab is never empty; typing runs a
 * debounced search instead. Playable hits are queued through
 * [ExoPlayerQueueManager], the same path the Media Hub uses.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class HurzyViewModel @Inject constructor(
    private val search: SearchRepository,
    private val queue: ExoPlayerQueueManager
) : ViewModel() {

    private val _state = MutableStateFlow(HurzyUiState())
    val state: StateFlow<HurzyUiState> = _state.asStateFlow()

    private val queries = MutableStateFlow("")

    /** What the tab shows before the user types anything. */
    private val defaultFeedQuery = "ambient"

    private val filter = SearchFilter(
        fileType = SourceFileType.AUDIO,
        // Radio is a live stream, not a track, so keep it out of the feed.
        includeRadio = false,
        requireDownloadable = false,
        limit = 30
    )

    init {
        viewModelScope.launch {
            queries
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query -> load(query) }
        }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        queries.value = text
    }

    fun clearQuery() = onQueryChange("")

    /** Plays the tapped item and queues the rest of the visible list after it. */
    fun play(item: SourceMediaItem) {
        val playable = _state.value.items.filter { HurzyPlaybackMapper.isPlayable(it) }
        val index = playable.indexOfFirst { it.id == item.id }
        if (index < 0) return
        queue.loadAndPlayQueue(playable.map { HurzyPlaybackMapper.toMediaItem(it) }, index)
    }

    private suspend fun load(rawQuery: String) {
        val query = rawQuery.trim().ifBlank { defaultFeedQuery }
        _state.update { it.copy(loading = true, errorMessage = null) }
        try {
            val result = search.search(query, filter)
            val playable = result.items.filter { HurzyPlaybackMapper.isPlayable(it) }
            _state.update {
                it.copy(
                    loading = false,
                    searched = rawQuery.isNotBlank(),
                    items = playable,
                    failedSources = result.failedSources
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    searched = rawQuery.isNotBlank(),
                    errorMessage = e.localizedMessage ?: "Could not load music"
                )
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
