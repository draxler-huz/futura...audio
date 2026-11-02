// app/src/main/java/com/futura/alarm/StealthModePolicy.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stealth-mode key interception policy.
 *
 * When stealth mode is engaged, hardware volume buttons and the power
 * key must not be able to silence or dismiss the alarm. Interception
 * itself happens in the Activity's `dispatchKeyEvent` override (see
 * `AlarmActivity`, delivered later) — this class only owns the policy
 * state and the whitelist of keys that are permitted.
 *
 * **Why not a global key interceptor:** intercepting `KEYCODE_VOLUME_*`
 * at the OS level requires accessibility-service privileges, which
 * would require the user to grant Accessibility Services permission —
 * far too invasive for a music app. Intercepting in the Activity
 * (which is what actually renders the alarm screen) is sufficient,
 * because the alarm screen is `showWhenLocked` + `turnScreenOn` + full
 * screen, so it's the only surface the user can interact with.
 *
 * The status bar may still allow swipe-to-dismiss of the notification.
 * Blocking that is not implemented yet.
 */
@Singleton
class StealthModePolicy @Inject constructor() {
    private val tag = "StealthModePolicy"
    private val mutex = Mutex()

    private val _engaged = MutableStateFlow(false)
    val engaged: StateFlow<Boolean> = _engaged.asStateFlow()

    // Volume keys only. KEYCODE_POWER is not included: the window manager
    // consumes it before any app receives it. Navigation is not blocked
    // either: window flags cannot do that, and the swipe-up gesture can
    // still reach Home.
    private val blockedKeys: Set<Int> = setOf(
        android.view.KeyEvent.KEYCODE_VOLUME_UP,
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
        android.view.KeyEvent.KEYCODE_VOLUME_MUTE
    )

    suspend fun engage() {
        mutex.withLock {
            if (_engaged.value) return@withLock
            _engaged.value = true
            Log.i(tag, "Stealth mode engaged")
        }
    }

    suspend fun disengage() {
        mutex.withLock {
            if (!_engaged.value) return@withLock
            _engaged.value = false
            Log.i(tag, "Stealth mode disengaged")
        }
    }

    /** True if the given key code must be swallowed while engaged. */
    fun shouldSwallow(keyCode: Int): Boolean {
        return _engaged.value && keyCode in blockedKeys
    }
}
