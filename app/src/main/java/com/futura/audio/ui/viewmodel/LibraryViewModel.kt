package com.futura.audio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.audio.data.SavedSourcesStore
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.pipeline.compliant.MusicSourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val store: SavedSourcesStore,
    private val repository: MusicSourceRepository
) : ViewModel() {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _activeWebViewUrl = MutableStateFlow<String?>(null)
    val activeWebViewUrl: StateFlow<String?> = _activeWebViewUrl.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Reload from disk; called each time the Library screen is shown. */
    fun refresh() {
        viewModelScope.launch {
            try {
                _items.value = store.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Could not load saved sources"
            }
        }
    }

    /**
     * Direct audio goes to ExoPlayer, then [onPlaying] opens Now Playing.
     * Web embeds open in the WebView dialog and never reach ExoPlayer.
     */
    fun play(item: MediaItem, onPlaying: () -> Unit) {
        when (item.route) {
            PlaybackRoute.EXOPLAYER_DIRECT -> viewModelScope.launch {
                try {
                    repository.playDirect(listOf(item))
                    _error.value = null
                    onPlaying()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = e.localizedMessage ?: "Playback failed"
                }
            }
            PlaybackRoute.WEB_EMBED_CONTAINER -> _activeWebViewUrl.value = item.streamUrl
        }
    }

    fun dismissWebView() {
        _activeWebViewUrl.value = null
    }
}
