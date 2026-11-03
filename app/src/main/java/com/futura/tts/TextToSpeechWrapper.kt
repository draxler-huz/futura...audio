// app/src/main/java/com/futura/tts/TextToSpeechWrapper.kt
package com.futura.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Non-blocking TTS wrapper.
 *
 * Manages the [TextToSpeech] engine lifecycle and exposes a suspend
 * [speak] that returns when the utterance completes. [mutex] guards engine
 * setup and teardown. [speakLock] makes concurrent speak() calls queue
 * instead of racing over the single utterance listener.
 *
 * Engine initialisation is lazy: the first [speak] call, or an explicit
 * [initialize], constructs the TextToSpeech instance. A later call reuses it.
 *
 * Every engine call is wrapped in try/catch. On any failure, speak() returns
 * false and the caller falls back to a pre-recorded drop or skips it.
 */
@Singleton
class TextToSpeechWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "TextToSpeechWrapper"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val speakLock = Mutex()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized: Boolean = false
    @Volatile private var ready: Boolean = false

    private var initJob: Job? = null

    /**
     * Initialise the engine if not yet initialised. Idempotent.
     *
     * @return true if the engine is ready to speak, false on any failure
     *         (engine unavailable, TTS data not installed, and so on).
     */
    suspend fun initialize(): Boolean = mutex.withLock {
        if (ready) return@withLock true
        if (initJob?.isActive == true) {
            // Another coroutine is initialising; wait for it.
            return@withLock withContext(Dispatchers.Default) {
                initJob?.join()
                ready
            }
        }

        initJob = scope.launch {
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val engine = TextToSpeech(context) { status ->
                        initialized = status == TextToSpeech.SUCCESS
                        if (cont.isActive) cont.resume(Unit)
                    }
                    tts = engine
                }
                ready = initialized
                if (ready) {
                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {}
                            @Deprecated("Deprecated in API 21")
                            override fun onError(utteranceId: String?) {}
                            override fun onError(utteranceId: String?, errorCode: Int) {}
                        }
                    )
                    Log.i(tag, "TTS engine ready")
                } else {
                    Log.w(tag, "TTS engine initialisation failed")
                }
            } catch (e: Exception) {
                Log.e(tag, "TTS init threw", e)
                ready = false
            }
        }
        initJob?.join()
        ready
    }

    /**
     * Speak [text]. Suspends until the utterance completes, is interrupted,
     * or fails.
     *
     * @param text Plain text. SSML is not supported; the briefing builder
     *        emits plain prose to avoid SSML differences across OEM engines.
     * @param queueMode [TextToSpeech.QUEUE_FLUSH] (interrupt the current
     *        utterance) or [TextToSpeech.QUEUE_ADD] (queue after it).
     * @param utteranceId Caller-supplied identifier, used for diagnostics.
     *        Generated if not provided.
     * @return true if the utterance completed; false on init failure, engine
     *         error, or cancellation.
     */
    suspend fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        utteranceId: String? = null
    ): Boolean {
        if (text.isBlank()) return true
        if (!initialize()) return false
        val engine = tts ?: return false

        return try {
            speakLock.withLock {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val id = utteranceId ?: "futura-${System.nanoTime()}"
                        engine.setOnUtteranceProgressListener(
                            object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {}
                                override fun onDone(utteranceId: String?) {
                                    if (cont.isActive) cont.resume(true)
                                }
                                @Deprecated("Deprecated in API 21")
                                override fun onError(utteranceId: String?) {
                                    if (cont.isActive) cont.resume(false)
                                }
                                override fun onError(utteranceId: String?, errorCode: Int) {
                                    if (cont.isActive) cont.resume(false)
                                }
                            }
                        )
                        val result = engine.speak(text, queueMode, null, id)
                        if (result != TextToSpeech.SUCCESS) {
                            if (cont.isActive) cont.resume(false)
                        }
                        cont.invokeOnCancellation {
                            try { engine.stop() } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "speak failed", e)
            false
        }
    }

    /** Stop any in-progress utterance and clear the queue. */
    suspend fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(tag, "stop failed", e)
        }
    }

    /**
     * Release the engine. Safe to call more than once. After [close], further
     * [speak] calls re-initialise on demand.
     */
    suspend fun close() = mutex.withLock {
        initJob?.cancel()
        initJob = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(tag, "shutdown failed", e)
        }
        tts = null
        initialized = false
        ready = false
        Log.i(tag, "TTS engine closed")
    }
}
