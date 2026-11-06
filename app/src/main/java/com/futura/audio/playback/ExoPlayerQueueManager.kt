package com.futura.audio.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.futura.audio.domain.MediaItem
import com.futura.system.FuturaPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds direct audio items to ExoPlayer. ExoPlayer must be used from the main
 * thread; callers switch with `withContext(Dispatchers.Main)`.
 */
@Singleton
class ExoPlayerQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exoPlayer: ExoPlayer
) {

    /** Held so the binding to FuturaPlaybackService stays alive while the app runs. */
    private var connection: ListenableFuture<MediaController>? = null

    fun loadAndPlayQueue(items: List<MediaItem>, startIndex: Int = 0) {
        val media3Items: List<Media3Item> = items.map { item: MediaItem ->
            // Subtitle, artist, and artwork live in MediaMetadata in Media3
            val metadata = MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setArtworkUri(item.artworkUrl?.let { android.net.Uri.parse(it) })
                .build()

            Media3Item.Builder()
                .setMediaId(item.id.value)
                .setUri(item.streamUrl)
                .setMediaMetadata(metadata)
                .build()
        }

        exoPlayer.setMediaItems(media3Items, startIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.play()
        connectService()
    }

    /**
     * Connects a MediaController. That is what starts FuturaPlaybackService,
     * and the service then runs in the foreground while audio plays, so
     * playback continues when the app is in the background.
     * Must be called on the main thread.
     */
    private fun connectService() {
        if (connection != null) return
        val token = SessionToken(context, ComponentName(context, FuturaPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connection = future
        future.addListener({
            try {
                future.get()
            } catch (e: Exception) {
                Log.w(TAG, "Could not connect to the playback service", e)
                connection = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private companion object {
        const val TAG = "ExoPlayerQueueManager"
    }
}
