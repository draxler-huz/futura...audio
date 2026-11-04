package com.futura.audio.pipeline.compliant

import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.playback.ExoPlayerQueueManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for the UI. Resolves a URL, then splits the result by
 * route. Direct audio goes to ExoPlayer on the main thread. Web embeds are
 * returned to the caller, which shows them in the WebView container. They
 * never reach ExoPlayer.
 */
@Singleton
class MusicSourceRepository @Inject constructor(
    private val factory: Category1Factory,
    private val queue: ExoPlayerQueueManager
) {

    data class PlayResult(
        /** Items the caller must show in the WebView container. */
        val webEmbeds: List<MediaItem>,
        /** How many direct items were sent to ExoPlayer. */
        val directCount: Int
    )

    suspend fun play(url: String, startIndex: Int = 0): PlayResult {
        val items = factory.resolve(url)
        val direct = items.filter { it.route == PlaybackRoute.EXOPLAYER_DIRECT }
        val embeds = items.filter { it.route == PlaybackRoute.WEB_EMBED_CONTAINER }

        if (direct.isNotEmpty()) {
            val start = startIndex.coerceIn(0, direct.lastIndex)
            withContext(Dispatchers.Main) {
                queue.loadAndPlayQueue(direct, start)
            }
        }
        return PlayResult(webEmbeds = embeds, directCount = direct.size)
    }
}
