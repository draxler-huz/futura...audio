package com.futura.audio.pipeline.compliant

import android.net.Uri
import com.futura.audio.data.MediaDownloader
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.pipeline.advanced.Category2Factory
import com.futura.audio.pipeline.advanced.LocalhostProxyServer
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
 * - [resolveAdvanced] routes a link through the advanced (Category 2) pipeline.
 * - [playDirect] sends only EXOPLAYER_DIRECT items to ExoPlayer, on Main.
 *   Web embeds are filtered out and never reach ExoPlayer.
 * - [download] saves an item's stream to the device Downloads collection.
 */
@Singleton
class MusicSourceRepository @Inject constructor(
    private val factory: Category1Factory,
    private val advancedFactory: Category2Factory,
    private val queue: ExoPlayerQueueManager,
    private val proxyServer: LocalhostProxyServer,
    private val downloader: MediaDownloader
) {

    suspend fun resolve(url: String): List<MediaItem> = factory.resolve(url)

    suspend fun resolveAdvanced(url: String): List<MediaItem> = advancedFactory.resolve(url)

    suspend fun playDirect(items: List<MediaItem>, startIndex: Int = 0) {
        val direct = items.filter { it.route == PlaybackRoute.EXOPLAYER_DIRECT }
        if (direct.isEmpty()) return
        // A proxied item is only playable while the loopback server runs. It is
        // started when a link is resolved, but a saved item played in a later
        // session has no live server, so make sure it is up first.
        if (direct.any { proxyServer.isProxyUrl(it.streamUrl) }) {
            proxyServer.start()
        }
        val start = startIndex.coerceIn(0, direct.lastIndex)
        withContext(Dispatchers.Main) {
            queue.loadAndPlayQueue(direct, start)
        }
    }

    /** Saves [item] to the device Downloads collection. Returns the saved location, or null on failure. */
    suspend fun download(item: MediaItem): Uri? {
        // A proxied item is fetched through the loopback server; make sure it runs.
        if (proxyServer.isProxyUrl(item.streamUrl)) {
            proxyServer.start()
        }
        return downloader.download(item)
    }
}
