package com.futura.system

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Owns the Media3 [MediaSession] for the app's shared [ExoPlayer]. The same
 * player instance drives NowPlayingViewModel, so the notification, lock
 * screen and headset controls stay in step with the in-app screens.
 *
 * Also publishes the current track to [NowPlayingSnapshot] so the Quick
 * Settings tile can show what is playing.
 */
@AndroidEntryPoint
class FuturaPlaybackService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    private var session: MediaSession? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, player).build()
        player.addListener(playerListener)
        publish()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        player.removeListener(playerListener)
        NowPlayingSnapshot.title = null
        NowPlayingSnapshot.isPlaying = false
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun publish() {
        NowPlayingSnapshot.title = player.currentMediaItem?.mediaMetadata?.title?.toString()
        NowPlayingSnapshot.isPlaying = player.isPlaying
        TileService.requestListeningState(
            this,
            ComponentName(this, FuturaQsTileService::class.java)
        )
    }
}
