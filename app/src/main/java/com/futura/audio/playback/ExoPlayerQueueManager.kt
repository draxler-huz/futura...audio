package com.futura.audio.playback

import android.net.Uri
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.futura.audio.domain.MediaItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds direct audio items to ExoPlayer. ExoPlayer must be used from the main
 * thread; callers switch with `withContext(Dispatchers.Main)`.
 */
@Singleton
class ExoPlayerQueueManager @Inject constructor(
    private val exoPlayer: ExoPlayer
) {

    fun loadAndPlayQueue(items: List<MediaItem>, startIndex: Int = 0) {
        val media3Items: List<Media3Item> = items.map { item: MediaItem ->
            // Subtitle, artist, and artwork live in MediaMetadata in Media3
            val metadata = MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setArtworkUri(item.artworkUrl?.let { Uri.parse(it) })
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
    }
}
