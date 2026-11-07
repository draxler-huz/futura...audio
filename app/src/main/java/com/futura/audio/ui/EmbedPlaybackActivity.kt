package com.futura.audio.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futura.audio.playback.EmbedPlaybackSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Hosts the web-embed WebView so it can keep playing while the app is in the
 * background.
 *
 * A WebView pauses media when its window is not visible. This activity enters
 * picture-in-picture as soon as the user leaves it, which keeps a (small,
 * possibly off-screen) window visible, so the embed keeps playing and stays
 * audible. It shares the same session state as the in-app dialog through
 * [EmbedPlaybackSession], so only one of the two ever plays a given URL.
 */
@AndroidEntryPoint
class EmbedPlaybackActivity : ComponentActivity() {

    @Inject
    lateinit var session: EmbedPlaybackSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.hostRunning = true

        if (intent?.action == ACTION_STOP) {
            finishAndRemoveTask()
            return
        }

        setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                EmbedSurface(
                    url = intent?.getStringExtra(EXTRA_URL),
                    session = session
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Launched as a background handoff: shrink into PiP straight away.
        if (intent?.getBooleanExtra(EXTRA_IMMEDIATE_PIP, false) == true) enterPip()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleInstance: a stop request arrives here when the host is already in PiP.
        if (intent.action == ACTION_STOP) finishAndRemoveTask()
    }

    override fun onDestroy() {
        session.hostRunning = false
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onStop() {
        super.onStop()
        // Home / recents: shrink into picture-in-picture so the embed keeps playing.
        // Skipped while finishing, so an explicit stop does not re-enter PiP.
        if (!isInPictureInPictureMode && !isFinishing) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Expanding the PiP window keeps playing full screen here; playback is
        // handed back to the in-app dialog only when the app returns to the
        // foreground, which sends ACTION_STOP.
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) return
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            // Some devices/OEMs refuse; playback simply stops when backgrounded.
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_IMMEDIATE_PIP = "extra_immediate_pip"
        const val ACTION_STOP = "com.futura.action.STOP_EMBED"
    }
}

@Composable
private fun EmbedSurface(url: String?, session: EmbedPlaybackSession) {
    // The session is the source of truth, so a later embed replaces the
    // currently playing one; the launch extra is only the initial URL.
    val active by session.activeUrl.collectAsStateWithLifecycle()
    val target = active ?: url
    if (target == null) return

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    loadUrl(target)
                }
            },
            update = { view -> if (view.url != target) view.loadUrl(target) },
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
            }
        )
        Text(
            text = "Playing in picture-in-picture",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
        )
    }
}
