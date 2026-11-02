// app/src/main/java/com/futura/alarm/SnoozeToPlayerHandshake.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Zero-loss audio transition protocol from the alarm's high-priority
 * panic stream into the standard non-disruptive music player queue.
 *
 * Once `SnoozeToPlayerHandshake.begin(specId)` is called, an internal
 * timer waits up to [HANDSHAKE_TIMEOUT_MS] for a caller (typically the
 * NowPlayingViewModel or MediaSessionCoordinator, once delivered) to
 * call [ackTransitioned]. Successful ack transitions the state machine
 * to `Transitioned`; expired timeout transitions it to `TimedOut`;
 * explicit [abort] transitions to `Aborted`.
 *
 * Terminal states are published on [events] so observers can dismiss
 * the alarm overlay and reveal the Now Playing screen.
 *
 * **Forward-reference seam**: the caller that would ack today is the
 * `MediaSessionCoordinator` (group N). Until it exists, the handshake
 * still functions as a pure state machine — begin() → timeout → TimedOut
 * — which is exactly what the calling NowPlayingScreen would want if a
 * coordinator were not available. When the coordinator lands, it calls
 * `ackTransitioned()` on MediaSession connect success.
 */
@Singleton
class SnoozeToPlayerHandshake @Inject constructor(
    private val coordinator: AlarmCoordinator
) {
    private val tag = "SnoozeToPlayerHandshake"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<HandshakeState?>(null)
    val state: StateFlow<HandshakeState?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HandshakeState>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val events: SharedFlow<HandshakeState> = _events.asSharedFlow()

    private var timeoutJob: Job? = null

    /** Begin the handshake for [specId]. Idempotent if a handshake is
     *  already pending for the same spec. */
    suspend fun begin(specId: String, targetPlayerQueueMs: Long = 0L) {
        mutex.withLock {
            val current = _state.value
            if (current is HandshakeState.Pending && current.specId == specId) {
                return // already pending
            }
            if (current is HandshakeState.Pending) {
                // A different spec supersedes the pending one; end it so it
                // reaches a terminal state instead of being dropped.
                val superseded = HandshakeState.Aborted(
                    handshakeId = current.handshakeId,
                    specId = current.specId,
                    reason = "superseded"
                )
                _state.value = superseded
                _events.tryEmit(superseded)
            }
            val pending = HandshakeState.Pending(
                specId = specId,
                targetPlayerQueueMs = targetPlayerQueueMs
            )
            _state.value = pending
            _events.tryEmit(pending)

            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                try {
                    delay(HANDSHAKE_TIMEOUT_MS)
                    onTimeout(pending.handshakeId)
                } catch (e: Exception) {
                    Log.e(tag, "Timeout job failed", e)
                }
            }
        }
    }

    /** Caller signals successful transition. */
    suspend fun ackTransitioned(handshakeId: String) {
        mutex.withLock {
            val current = _state.value
            if (current !is HandshakeState.Pending) return
            if (current.handshakeId != handshakeId) return
            timeoutJob?.cancel()
            val next = HandshakeState.Transitioned(
                handshakeId = current.handshakeId,
                specId = current.specId
            )
            _state.value = next
            _events.tryEmit(next)
        }
    }

    /** Caller explicitly aborts (e.g. the user taps Dismiss before the
     *  player can take over). */
    suspend fun abort(handshakeId: String, reason: String = "") {
        mutex.withLock {
            val current = _state.value
            if (current !is HandshakeState.Pending) return
            if (current.handshakeId != handshakeId) return
            timeoutJob?.cancel()
            val next = HandshakeState.Aborted(
                handshakeId = current.handshakeId,
                specId = current.specId,
                reason = reason
            )
            _state.value = next
            _events.tryEmit(next)
        }
    }

    private suspend fun onTimeout(handshakeId: String) {
        mutex.withLock {
            val current = _state.value
            if (current !is HandshakeState.Pending) return
            if (current.handshakeId != handshakeId) return
            val next = HandshakeState.TimedOut(
                handshakeId = current.handshakeId,
                specId = current.specId,
                timeoutMs = HANDSHAKE_TIMEOUT_MS
            )
            _state.value = next
            _events.tryEmit(next)
            // Escalate the failure to the coordinator — the alarm
            // session remains in FIRING so the watchdog picks it up.
            Log.w(tag, "Handshake timed out for ${current.specId}")
        }
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS: Long = 1500L
    }
}