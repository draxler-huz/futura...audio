// app/src/main/java/com/futura/alarm/AudioFocusStealer.kt
package com.futura.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-priority audio focus requester for the alarm stream.
 *
 * Requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` on the
 * [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE] contract, which
 * forces any active media player to duck fully silent. The alarm
 * outranks every ordinary media stream, notification sound, and call
 * ringing tone.
 *
 * Reentrant-safe: multiple acquires from the same session collapse to a
 * single physical request; release is refcount-based. If two alarms
 * fire in rapid succession (rare but possible if the user has stacked
 * alarms), both share the same physical focus.
 */
@Singleton
class AudioFocusStealer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "AudioFocusStealer"
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutex = Mutex()

    private var focusRequest: AudioFocusRequest? = null
    private var refCount = 0

    // Written under [mutex] and by the focus-change listener, which runs on
    // the main thread. Volatile so readers see the latest value.
    @Volatile private var lastGranted = false

    /** True while the OS has granted focus and has not since taken it away. */
    val isHoldingFocus: Boolean
        get() = lastGranted

    suspend fun acquire(): Boolean = mutex.withLock {
        if (refCount > 0) {
            refCount++
            return@withLock lastGranted
        }
        try {
            val request = buildRequest()
            val result = audioManager.requestAudioFocus(request)
            lastGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (lastGranted) {
                focusRequest = request
                refCount = 1
                Log.i(tag, "Audio focus acquired")
            } else {
                Log.w(tag, "Audio focus request denied")
            }
            lastGranted
        } catch (e: Exception) {
            Log.e(tag, "Audio focus acquire failed", e)
            lastGranted = false
            false
        }
    }

    suspend fun release() = mutex.withLock {
        if (refCount <= 0) return@withLock
        refCount--
        if (refCount > 0) return@withLock
        try {
            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                Log.i(tag, "Audio focus released")
            }
        } catch (e: Exception) {
            Log.w(tag, "Audio focus release failed", e)
        }
        focusRequest = null
        lastGranted = false
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun buildRequest(): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val requestBuilder = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        )
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                Log.i(tag, "Audio focus change: $change")
                // Negative values are the LOSS variants: focus is gone.
                if (change < 0) lastGranted = false
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestBuilder.setForceDucking(false)
        }
        return requestBuilder.build()
    }
}
