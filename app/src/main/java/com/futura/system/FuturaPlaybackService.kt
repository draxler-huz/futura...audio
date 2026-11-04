package com.futura.system

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Owns the Media3 [MediaSession] for the app's shared [ExoPlayer]. The same
 * player instance drives NowPlayingViewModel, so the notification, lock
 * screen and headset controls stay in step with the in-app screens.
 */
@AndroidEntryPoint
class FuturaPlaybackService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
