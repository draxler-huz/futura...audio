// app/src/main/java/com/futura/alarm/FullScreenIntentReceiver.kt
package com.futura.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Launches [AlarmActivity] over everything, from a background context.
 *
 * Uses `SYSTEM_ALERT_WINDOW` (Background Activity Launch exemption) to
 * bypass the Android 10+ BAL restrictions. When the permission is not
 * granted, the receiver falls through silently — the notification
 * path (full-screen intent on lock screen, heads-up when unlocked)
 * remains active as the fallback.
 */
class FullScreenIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val specId = intent.getStringExtra(EXTRA_SPEC_ID)
            ?: intent.data?.lastPathSegment

        if (specId.isNullOrBlank()) {
            Log.w(TAG, "FullScreenIntentReceiver: missing specId — aborting")
            return
        }

        if (!OverlayPermissionHelper.canDrawOverlays(context)) {
            Log.i(
                TAG,
                "SYSTEM_ALERT_WINDOW not granted; cannot foreground alarm UI. " +
                    "Notification path remains active."
            )
            return
        }

        try {
            val alarmIntent = AlarmActivity.intent(context, specId).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(alarmIntent)
            Log.i(TAG, "Alarm UI foregrounded for $specId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch AlarmActivity for $specId", e)
        }
    }

    companion object {
        private const val TAG = "FullScreenIntentReceiver"
        const val EXTRA_SPEC_ID = "com.futura.alarm.EXTRA_SPEC_ID"

        fun intent(context: Context, specId: String): Intent =
            Intent(context, FullScreenIntentReceiver::class.java).apply {
                action = "com.futura.action.FULL_SCREEN_LAUNCH"
                putExtra(EXTRA_SPEC_ID, specId)
            }
    }
}
