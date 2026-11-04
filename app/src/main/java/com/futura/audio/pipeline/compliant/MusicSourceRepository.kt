package com.futura.audio.pipeline.compliant

import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.playback.ExoPlayerQueueManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the UI layer. The ViewModel calls this and never
 * touches factories, parsers, or ExoPlayer directly.
 *
 * - [resolve] returns every item for a URL, unchanged, including web embeds.
 * - [playDirect] sends only EXOPLAYER_DIRECT items to ExoPlayer, on Main.
 *   Web embeds are filtered out and never reach ExoPlayer.
 */
@Singleton
class MusicSourceRepository @Inject constructor(
    private val factory: Category1Factory,
    private val queue: ExoPlayerQueueManager
) {

    suspend fun resolve(url: String): List<MediaItem> = factory.resolve(url)

    suspend fun playDirect(items: List<MediaItem>, startIndex: Int = 0) {
        val direct = items.filter { it.route == PlaybackRoute.EXOPLAYER_DIRECT }
        if (direct.isEmpty()) return
        val start = startIndex.coerceIn(0, direct.lastIndex)
        withContext(Dispatchers.Main) {
            queue.loadAndPlayQueue(direct, start)
        }
    }
}
