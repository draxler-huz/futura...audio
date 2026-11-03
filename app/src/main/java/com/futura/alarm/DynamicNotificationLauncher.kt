package com.futura.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import androidx.core.app.NotificationCompat

/**
 * Helper that creates and posts a full‑screen notification which launches
 * [AlarmActivity] when the alarm fires.
 *
 * The notification channel is created on first use.  The caller only needs to
 * invoke [postFullScreenAlarm] with a spec identifier.
 */
object DynamicNotificationLauncher {

    private const val CHANNEL_ID = "futura_alarm_fullscreen"
    private const val CHANNEL_NAME = "Futura Alarm (Full‑Screen)"
    private const val NOTIFICATION_ID = 1001

    /**
     * Posts a full‑screen notification that opens [AlarmActivity] for the given
     * `specId`.
     *
     * @param context Application or activity context.
     * @param specId   The alarm specification ID used to deep‑link the activity.
     */
    fun postFullScreenAlarm(context: Context, specId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        // Intent that opens the alarm UI via a deep link.
        val alarmIntent = AlarmActivity.intent(context, specId)
        val pending = PendingIntent.getActivity(
            context,
            /* requestCode = */ specId.hashCode(),
            alarmIntent,
            // FLAG_IMMUTABLE is required on API 31+, FLAG_UPDATE_CURRENT lets us reuse the same spec.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build the notification – the key part is setFullScreenIntent().
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setColor(Color.RED)
            .setContentTitle("Alarm")
            .setContentText("Tap to dismiss")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)          // <<< forces full‑screen UI
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Creates the notification channel required for API‑26+. */
    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // A full‑screen intent must have high importance.
            enableLights(true)
            lightColor = Color.RED
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(channel)
    }
}
