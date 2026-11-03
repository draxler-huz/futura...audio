// app/src/main/java/com/futura/alarm/AlarmWindowHelper.kt
package com.futura.alarm

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

/**
 * Window configuration for the full-screen alarm activity.
 *
 * Extracted from `AlarmActivity.configureWindow` so the intent-creation
 * code and unit tests can exercise the flags without instantiating the
 * activity.
 *
 * ── What this DOES ─────────────────────────────────────────────────
 *   • Shows the alarm above the lock screen  (FLAG_SHOW_WHEN_LOCKED).
 *   • Turns the screen on                    (FLAG_TURN_SCREEN_ON).
 *   • Keeps the screen on for the alarm lifetime
 *                                            (FLAG_KEEP_SCREEN_ON).
 *   • Opts into edge-to-edge layout          (decor-fits=false).
 *
 * ── What this DELIBERATELY DOES NOT DO ─────────────────────────────
 *   • Hide the navigation bar. `WindowInsetsControllerCompat.hide(nav)`
 *     removes the bar visually but CANNOT prevent the OS from consuming
 *     a swipe-up-from-edge gesture — that gesture is handled by SystemUI
 *     at the WindowManager layer, not by the app. Advertising immersive
 *     mode here would falsely promise gesture trapping; see
 *     `StealthModePolicy`'s KDoc for the full reasoning.
 *   • Intercept the power button. OS-level, not app-level.
 *
 * The alarm's actual anti-dismiss protection comes from
 * `StealthModePolicy` (volume-key swallow + back-button swallow) and
 * `AlarmActivity`'s two-stage dismiss gate — not from window flags.
 */
object AlarmWindowHelper {

    /**
     * Apply the standard alarm-window flags. Idempotent; safe to call
     * multiple times.
     */
    fun applyWindowFlags(window: Window) {
        try {
            // Show-when-locked + turn-screen-on. On O_MR1+ the OS
            // recommends the Activity-level setters
            // (`setShowWhenLocked(true)` / `setTurnScreenOn(true)`)
            // because they auto-clear when the activity finishes; below
            // O_MR1 the window flags are the only mechanism. We call
            // both here for consistency; the Activity calls the
            // O_MR1+ setters itself and this helper is safe to call
            // from either path.
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )

            // Keep the screen on for the alarm lifetime.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Opt into edge-to-edge (lets the Compose content draw
            // under the status bar — the safe-drawing insets handle
            // the padding).
            WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (e: Exception) {
            // Window configuration failing is not fatal; the alarm still
            // fires via the notification + foreground service. Log and
            // continue.
            android.util.Log.e("AlarmWindowHelper", "applyWindowFlags failed", e)
        }
    }
}
