package com.futura.audio.playback

import androidx.media3.exoplayer.ExoPlayer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the video picture is shown over the app. The same player that plays
 * audio renders video, so this only controls the dialog that displays it.
 */
@Singleton
class PlaybackVideoState @Inject constructor(
    val player: ExoPlayer
) {
    private val _showVideo = MutableStateFlow(false)
    val showVideo: StateFlow<Boolean> = _showVideo.asStateFlow()

    fun show() {
        _showVideo.value = true
    }

    fun hide() {
        _showVideo.value = false
    }
}
