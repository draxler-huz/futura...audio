package com.futura.alarm

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Small utility that centralises all window‑configuration calls required
 * for a full‑screen alarm activity.
 *
 * The logic mirrors what is already done inside [AlarmActivity.configureWindow],
 * but exposing it here makes the intent‑creation code independent of the UI
 * implementation and eases unit‑testing.
 */
object AlarmWindowHelper {

    /** Apply the standard flags (show‑when‑locked, turn‑screen‑on, keep‑screen‑on, …). */
    fun applyWindowFlags(window: Window) {
        // Show when locked / turn screen on – use the newer APIs on O_MR1+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.setDecorFitsSystemWindows(false)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Keep the screen on for the whole alarm lifetime.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system bars and enable transient‑bars‑by‑swipe behavior.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
