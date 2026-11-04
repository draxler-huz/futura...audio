package com.futura.audio.playback

import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.exoplayer.ExoPlayer
import com.futura.audio.domain.MediaItem
import javax.inject.Inject

/**
 * Feeds direct audio items to ExoPlayer. ExoPlayer must be used from the main
 * thread; callers switch with `withContext(Dispatchers.Main)`.
 */
class ExoPlayerQueueManager @Inject constructor(
    private val exoPlayer: ExoPlayer
) {

    fun loadAndPlayQueue(items: List<MediaItem>, startIndex: Int = 0) {
        val media3Items = items.map { item ->
            Media3Item.Builder()
                .setMediaId(item.id)
                .setUri(item.streamUrl)
                .setSubtitle(item.artist)
                .build()
        }

        exoPlayer.setMediaItems(media3Items, startIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.play()
    }
}
