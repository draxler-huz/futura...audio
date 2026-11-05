package com.futura.audio.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.audio.data.DeviceMediaScanner
import com.futura.audio.data.SavedSourcesStore
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.pipeline.compliant.MusicSourceRepository
import com.futura.audio.playback.IncomingMediaHandler
import com.futura.audio.playback.PlaybackVideoState
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
    private val repository: MusicSourceRepository,
    private val handler: IncomingMediaHandler,
    private val scanner: DeviceMediaScanner,
    private val videoState: PlaybackVideoState
) : ViewModel() {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _activeWebViewUrl = MutableStateFlow<String?>(null)
    val activeWebViewUrl: StateFlow<String?> = _activeWebViewUrl.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

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

    /** Adds files the user picked. Any type the picker offers is accepted; playback decides what plays. */
    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            try {
                val playable = uris.filter { handler.isPlayable(it) }
                if (playable.isEmpty()) {
                    _error.value = "Those files are not audio or video Futura can play."
                    return@launch
                }
                val items = playable.map { uri ->
                    handler.persistReadAccess(uri)
                    handler.itemFor(uri)
                }
                handler.addToLibrary(items)
                _items.value = store.load()
                _error.value = if (playable.size < uris.size) {
                    "${uris.size - playable.size} file(s) skipped: not audio or video."
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Could not add those files"
            }
        }
    }

    /** Adds every music and video file MediaStore knows about. Needs media permission first. */
    fun scanDevice() {
        viewModelScope.launch {
            _scanning.value = true
            try {
                val found = scanner.scan()
                handler.addToLibrary(found)
                _items.value = store.load()
                _error.value = if (found.isEmpty()) "No music or video found on this device." else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Could not scan device media"
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * Direct audio goes to ExoPlayer, then [onPlaying] opens Now Playing.
     * Video plays through the same player and shows its picture instead.
     * Web embeds open in the WebView dialog and never reach ExoPlayer.
     */
    fun play(item: MediaItem, onPlaying: () -> Unit) {
        when (item.route) {
            PlaybackRoute.EXOPLAYER_DIRECT -> viewModelScope.launch {
                try {
                    repository.playDirect(listOf(item))
                    _error.value = null
                    if (item.isVideo) videoState.show() else onPlaying()
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
