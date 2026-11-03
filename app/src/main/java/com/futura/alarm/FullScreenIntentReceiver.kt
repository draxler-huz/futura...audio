package com.futura.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Optional thin wrapper receiver that can be used in the manifest as a
 * separate component for the full‑screen intent.  The receiver simply forwards
 * the intent to [AlarmActivity] via its deep‑link helper.
 *
 * Using a receiver allows us to keep the full‑screen intent “non‑exported”
 * while still giving the system something to launch.
 */
class FullScreenIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val specId = intent.getStringExtra(EXTRA_SPEC_ID)
            ?: intent.data?.lastPathSegment

        if (specId.isNullOrBlank()) {
            Log.w(TAG, "FullScreenIntentReceiver: missing specId – aborting")
            return
        }

        // Forward to the activity using the same deep‑link logic.
        val alarmIntent = AlarmActivity.intent(context, specId)
        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(alarmIntent)
    }

    companion object {
        private const val TAG = "FullScreenIntentReceiver"
        const val EXTRA_SPEC_ID = "com.futura.alarm.EXTRA_SPEC_ID"
    }
}
