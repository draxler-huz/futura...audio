package com.futura.audio.playback

import android.content.Context
import android.content.Intent
import com.futura.audio.ui.EmbedPlaybackActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates the single WebView that plays a web embed.
 *
 * A WebView pauses its media the moment its window stops being visible, so an
 * embed cannot keep playing from a Compose dialog once the app is backgrounded.
 * The app therefore hands the embed to [EmbedPlaybackActivity], which enters
 * picture-in-picture so its window stays visible (and audible) off-screen.
 *
 * The handoff is triggered from the host activity's `onUserLeaveHint`, i.e.
 * while the app is still visible, so it is not blocked by the background
 * activity start restriction.
 */
@Singleton
class EmbedPlaybackSession @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _activeUrl = MutableStateFlow<String?>(null)

    /** The URL currently playing in the in-app dialog, or null when none is. */
    val activeUrl: StateFlow<String?> = _activeUrl.asStateFlow()

    /** True while [EmbedPlaybackActivity] is alive. */
    @Volatile
    var hostRunning: Boolean = false

    /** The dialog is now showing [url]; it becomes the embed that can be backgrounded. */
    fun beginPlayback(url: String) {
        _activeUrl.value = url
    }

    /** The dialog was dismissed; there is nothing left to background. */
    fun endPlayback() {
        _activeUrl.value = null
    }

    /** Moves playback into the picture-in-picture host. Call while still foreground. */
    fun startPip(url: String) {
        val intent = Intent(context, EmbedPlaybackActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EmbedPlaybackActivity.EXTRA_URL, url)
            putExtra(EmbedPlaybackActivity.EXTRA_IMMEDIATE_PIP, true)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // A refused start leaves the embed paused, which is the old behaviour.
        }
    }

    /** Removes the picture-in-picture window, if one is showing. */
    fun stopPip() {
        if (!hostRunning) return
        val intent = Intent(context, EmbedPlaybackActivity::class.java).apply {
            action = EmbedPlaybackActivity.ACTION_STOP
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Nothing to stop.
        }
    }
}
