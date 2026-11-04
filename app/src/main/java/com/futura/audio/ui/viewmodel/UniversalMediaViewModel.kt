package com.futura.audio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.pipeline.compliant.MusicSourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UniversalMediaViewModel @Inject constructor(
    private val repository: MusicSourceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaHubUiState())
    val uiState: StateFlow<MediaHubUiState> = _uiState.asStateFlow()

    fun setCategoryFilter(category: PipelineCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun processAndAddUrl(url: String, category: PipelineCategory) {
        if (url.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // Category 2 resolvers will be chained here when built.
                val resolvedItems = when (category) {
                    PipelineCategory.COMPLIANT_STANDARD -> repository.resolve(url)
                    PipelineCategory.ADVANCED_EXTRACTION -> emptyList()
                }

                _uiState.update { state ->
                    state.copy(
                        items = resolvedItems + state.items,
                        isLoading = false
                    )
                }

                // Auto-play only the direct items. Web embeds are never sent to ExoPlayer.
                repository.playDirect(resolvedItems)

            } catch (e: CancellationException) {
                // Rethrow so cancellation of viewModelScope is not swallowed as an error.
                throw e
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to resolve link"
                    )
                }
            }
        }
    }

    fun onMediaItemClicked(item: MediaItem) {
        when (item.route) {
            PlaybackRoute.EXOPLAYER_DIRECT -> {
                viewModelScope.launch {
                    try {
                        repository.playDirect(listOf(item))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "Playback failed") }
                    }
                }
            }
            PlaybackRoute.WEB_EMBED_CONTAINER -> {
                _uiState.update { it.copy(activeWebViewUrl = item.streamUrl) }
            }
        }
    }

    fun dismissWebViewContainer() {
        _uiState.update { it.copy(activeWebViewUrl = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
