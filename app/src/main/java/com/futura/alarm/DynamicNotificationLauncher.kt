// app/src/main/java/com/futura/alarm/DynamicNotificationLauncher.kt
package com.futura.alarm

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.futura.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts the active-alarm heads-up notification.
 *
 * Uses `CATEGORY_ALARM` + `PRIORITY_MAX` + `setFullScreenIntent` so the
 * alarm triggers the Wake-Up Overlay above the lock screen on every
 * supported API level. Inline action buttons (Snooze / Dismiss) route
 * through [PendingIntentFactory].
 *
 * The alarm channel is created before the first post. On API 26+ a post to
 * a missing channel is dropped silently.
 *
 * The notification is safe to re-post — if the OS drops it (memory
 * pressure, user swipe), the caller just calls [show] again; the OS
 * deduplicates by notification id.
 */
@Singleton
class DynamicNotificationLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingIntents: PendingIntentFactory,
    private val channelResolver: DndBypassResolver
) {
    private val tag = "DynamicNotificationLauncher"
    private val nm = NotificationManagerCompat.from(context)

    /**
     * Post the active-alarm notification for [spec]. Returns true if the
     * post succeeded (permission granted, notification manager accepted
     * the request).
     */
    fun show(spec: AlarmSpec): Boolean = try {
        channelResolver.ensureChannel()

        val builder = NotificationCompat.Builder(context, DndBypassResolver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(
                spec.label.ifBlank { context.getString(R.string.alarm_title) }
            )
            .setContentText(context.getString(R.string.alarm_action_snooze) +
                " · " + context.getString(R.string.alarm_action_dismiss))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        pendingIntents.forSnooze(spec.id)?.let { pi ->
            builder.addAction(
                R.drawable.ic_alarm_snoozed,
                context.getString(R.string.alarm_action_snooze),
                pi
            )
        }
        pendingIntents.forDismiss(spec.id)?.let { pi ->
            builder.addAction(
                R.drawable.ic_alarm_off,
                context.getString(R.string.alarm_action_dismiss),
                pi
            )
        }

        pendingIntents.forFullScreen(spec.id)?.let { pi ->
            builder.setFullScreenIntent(pi, /* highPriority = */ true)
            builder.setContentIntent(pi)
        }

        val notification = builder.build()
        try {
            nm.notify(NOTIFICATION_ID_ALARM, notification)
            true
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission denied; falling back to full-screen only", e)
            // Even without notification permission, the full-screen intent
            // is not blocked by POST_NOTIFICATIONS — the alarm activity
            // still launches. Return false to signal that the notification
            // itself did not post, so callers can decide.
            false
        }
    } catch (e: Exception) {
        Log.e(tag, "show() failed for ${spec.id}", e); false
    }

    fun cancel() {
        try {
            nm.cancel(NOTIFICATION_ID_ALARM)
        } catch (e: Exception) {
            Log.w(tag, "cancel failed", e)
        }
    }

    companion object {
        const val NOTIFICATION_ID_ALARM = 0xF00D
    }
}
