package com.futura.audio.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.audio.data.SavedSourcesStore
import com.futura.audio.domain.MediaId
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.domain.Subcategory
import com.futura.audio.pipeline.advanced.PlaylistResolver
import com.futura.data.extraction.UrlExtractor
import com.futura.audio.pipeline.compliant.MusicSourceRepository
import com.futura.audio.playback.ExoPlayerQueueManager
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
    private val repository: MusicSourceRepository,
    private val store: SavedSourcesStore,
    private val playlistResolver: PlaylistResolver,
    private val urlExtractor: UrlExtractor,
    private val queueManager: ExoPlayerQueueManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaHubUiState())
    val uiState: StateFlow<MediaHubUiState> = _uiState.asStateFlow()

    init {
        // Show previously saved sources when the hub opens.
        viewModelScope.launch {
            try {
                val loaded = store.load()
                _uiState.update { it.copy(allItems = loaded) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "Could not load saved sources") }
            }
        }
    }

    fun setCategoryFilter(category: PipelineCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun processAndAddUrl(url: String, category: PipelineCategory = _uiState.value.selectedCategory) {
        if (url.isBlank()) return

        // Accept "example.com/feed" as well as full links; OkHttp needs a scheme.
        val trimmed = url.trim()
        val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val resolvedItems = when (category) {
                    PipelineCategory.COMPLIANT_STANDARD -> repository.resolve(normalized)
                    PipelineCategory.ADVANCED_EXTRACTION -> resolveAdvanced(normalized)
                }

                _uiState.update { state ->
                    state.copy(
                        allItems = resolvedItems + state.allItems,
                        isLoading = false
                    )
                }

                // Persist everything the hub holds, so the Library sees it.
                store.save(_uiState.value.allItems.distinctBy { it.id })

                // Auto-play direct items via ExoPlayer queue manager
                if (resolvedItems.isNotEmpty() && resolvedItems.first().route == PlaybackRoute.EXOPLAYER_DIRECT) {
                    queueManager.loadAndPlayQueue(resolvedItems)
                }

            } catch (e: CancellationException) {
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

    /**
     * Overload for simpler calls passing just the URL using the currently selected category.
     */
    fun processAndAddUrl(url: String) {
        processAndAddUrl(url, _uiState.value.selectedCategory)
    }

    /**
     * Category 2: publisher playlists (.m3u, .pls) and direct audio or video
     * file links. Web pages are refused by [UrlExtractor], so nothing is
     * pulled out of a page.
     */
    private suspend fun resolveAdvanced(url: String): List<MediaItem> {
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".m3u") || path.endsWith(".pls")) {
            return playlistResolver.resolvePlaylist(url)
        }
        val downloaded = urlExtractor.download(url)
            ?: throw IllegalArgumentException(
                "Could not get audio or video from that link. Use a direct file link, or a .m3u or .pls playlist."
            )
        val fileUri = Uri.fromFile(downloaded.file).toString()
        val title = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { "Downloaded media" }
        return listOf(
            MediaItem(
                id = MediaId(fileUri),
                title = title,
                artist = null,
                streamUrl = fileUri,
                artworkUrl = null,
                sourceName = "Direct link",
                subcategory = Subcategory("extraction.direct"),
                route = PlaybackRoute.EXOPLAYER_DIRECT,
                category = PipelineCategory.ADVANCED_EXTRACTION,
                isVideo = downloaded.contentType.startsWith("video/")
            )
        )
    }

    fun onMediaItemClicked(item: MediaItem) {
        when (item.route) {
            PlaybackRoute.EXOPLAYER_DIRECT -> {
                try {
                    queueManager.loadAndPlayQueue(listOf(item))
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "Playback failed") }
                }
            }
            PlaybackRoute.WEB_EMBED_CONTAINER -> {
                _uiState.update { it.copy(activeWebViewUrl = item.streamUrl) }
            }
        }
    }

    fun dismissWebView() {
        _uiState.update { it.copy(activeWebViewUrl = null) }
    }

    fun dismissWebViewContainer() {
        dismissWebView()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
