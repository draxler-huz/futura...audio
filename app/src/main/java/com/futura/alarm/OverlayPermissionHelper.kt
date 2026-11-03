// app/src/main/java/com/futura/alarm/OverlayPermissionHelper.kt
package com.futura.alarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Manages the SYSTEM_ALERT_WINDOW ("Display over other apps") special
 * app-access permission.
 *
 * This permission is the ONLY reliable way to launch an activity over
 * whatever is currently on screen from a background context (alarm
 * receiver, foreground service, broadcast) on Android 12+.
 *
 * `setFullScreenIntent` is NOT sufficient: it only launches over the
 * lock screen. When the device is unlocked and the user is in another
 * app, the OS silently converts it to a heads-up notification.
 *
 * `SYSTEM_ALERT_WINDOW` is a hard Background Activity Launch (BAL)
 * exemption [citation:4][citation:6]. With it granted, the app may
 * call `startActivity()` from the background and the activity appears
 * over everything — locked or unlocked.
 *
 * Trade-off: the user must grant it manually from Settings. There is
 * no runtime permission dialog.
 */
object OverlayPermissionHelper {

    private const val TAG = "OverlayPermissionHelper"

    /** True if the app currently holds SYSTEM_ALERT_WINDOW. */
    fun canDrawOverlays(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Granted at install time on pre-M.
        }
    } catch (e: Exception) {
        Log.e(TAG, "canDrawOverlays check failed", e)
        false
    }

    /**
     * Open the system Settings page where the user can grant
     * "Display over other apps" for this app.
     *
     * The user leaves the app to toggle the switch; the return value
     * reflects the state before they left. Re-check [canDrawOverlays]
     * in `onResume` after returning.
     */
    fun requestOverlayPermission(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open overlay permission settings", e)
        }
    }

    /**
     * API 34+ check for USE_FULL_SCREEN_INTENT. Returns true on older
     * APIs (the permission was auto-granted for all apps before 14).
     */
    fun canUseFullScreenIntent(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm?.canUseFullScreenIntent() ?: false
        } else {
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "canUseFullScreenIntent check failed", e)
        false
    }

    /**
     * API 34+ settings page for USE_FULL_SCREEN_INTENT. Only meaningful
     * when [canUseFullScreenIntent] returns false and the user has
     * previously revoked the permission.
     */
    fun requestFullScreenIntentPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open FSI permission settings", e)
        }
    }
}
