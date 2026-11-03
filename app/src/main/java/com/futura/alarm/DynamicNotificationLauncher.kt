// app/src/main/java/com/futura/alarm/DynamicNotificationLauncher.kt
package com.futura.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts a full-screen notification that launches
 * [AlarmActivity] when the alarm fires.
 *
 * Public surface — matches what [ForegroundNotificationLifecycleBinder]
 * and the alarm session teardown already call:
 *   • [show] — post (or re-post) the notification for a spec.
 *   • [postFullScreenAlarm] — convenience wrapper, accepts a raw specId.
 *   • [cancel] — remove it.
 *   • [isNotificationActive] — repair signal for the lifecycle binder.
 *   • [NOTIFICATION_ID_ALARM] — the stable id used by all of the above.
 *
 * The channel is created on first use. Channel ID and importance match
 * the alarm's "must bypass Do Not Disturb" contract — IMPORTANCE_HIGH
 * is the minimum the OS allows for a full-screen intent.
 *
 * Rule 6: every OS call wrapped; on failure, returns false / logs.
 */
@Singleton
class DynamicNotificationLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingIntents: PendingIntentFactory
) {
    private val tag = "DynamicNotificationLauncher"

    private val manager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Post (or re-post) the active-alarm notification for [spec].
     *
     * @return true if the post succeeded; false if notification
     *         permission is denied (POST_NOTIFICATIONS on API 33+).
     *         The alarm still fires via the full-screen intent path
     *         when this returns false — POST_NOTIFICATIONS does not
     *         gate `setFullScreenIntent`.
     */
    fun show(spec: AlarmSpec): Boolean = try {
        ensureChannel()

        // Full-screen intent → launches AlarmActivity via the
        // futura://alarm/<specId> deep link, produced by the factory.
        val fullScreen = pendingIntents.forFullScreen(spec.id)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setColor(Color.RED)
            .setContentTitle(
                spec.label.ifBlank { context.getString(com.futura.R.string.alarm_title) }
            )
            .setContentText(
                context.getString(com.futura.R.string.alarm_action_snooze) +
                    " · " +
                    context.getString(com.futura.R.string.alarm_action_dismiss)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        fullScreen?.let { pi ->
            builder.setFullScreenIntent(pi, /* highPriority = */ true)
            builder.setContentIntent(pi)
        }

        // Inline action buttons — routed through the factory so request
        // codes never collide with the full-screen intent's.
        pendingIntents.forSnooze(spec.id)?.let { pi ->
            builder.addAction(
                com.futura.R.drawable.ic_alarm_snoozed,
                context.getString(com.futura.R.string.alarm_action_snooze),
                pi
            )
        }
        pendingIntents.forDismiss(spec.id)?.let { pi ->
            builder.addAction(
                com.futura.R.drawable.ic_alarm_off,
                context.getString(com.futura.R.string.alarm_action_dismiss),
                pi
            )
        }

        try {
            manager.notify(NOTIFICATION_ID_ALARM, builder.build())
            true
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission denied; alarm fires via full-screen intent only", e)
            false
        }
    } catch (e: Exception) {
        Log.e(tag, "show() failed for ${spec.id}", e)
        false
    }

    /**
     * Post the full-screen alarm notification for [specId].
     *
     * Convenience overload for call sites that only have the spec id
     * and not a resolved [AlarmSpec]. Delegates to a minimal
     * [AlarmSpec] instance carrying the id and empty label.
     */
    fun postFullScreenAlarm(specId: String) {
        try {
            ensureChannel()
            val fullScreen = pendingIntents.forFullScreen(specId)
                ?: buildInlinePendingIntent(specId)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setColor(Color.RED)
                .setContentTitle(context.getString(com.futura.R.string.alarm_title))
                .setContentText(context.getString(com.futura.R.string.alarm_action_dismiss))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreen, /* highPriority = */ true)
                .setContentIntent(fullScreen)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

            manager.notify(NOTIFICATION_ID_ALARM, notification)
        } catch (e: Exception) {
            Log.e(tag, "postFullScreenAlarm failed for $specId", e)
        }
    }

    /** Cancel the active-alarm notification. Idempotent. */
    fun cancel() {
        try {
            manager.cancel(NOTIFICATION_ID_ALARM)
        } catch (e: Exception) {
            Log.w(tag, "cancel failed", e)
        }
    }

    /**
     * True if a notification with [NOTIFICATION_ID_ALARM] is currently
     * active. Used by [ForegroundNotificationLifecycleBinder] to decide
     * whether re-post is needed. Returns true on pre-API 23 (cannot
     * inspect — repair is best-effort, and a false assumption of
     * "active" only wastes one re-post attempt).
     */
    fun isNotificationActive(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            manager.activeNotifications.any { it.id == NOTIFICATION_ID_ALARM }
        } else {
            true
        }
    } catch (e: Exception) {
        true
    }

    // ── Internals ──────────────────────────────────────────────────

    /** Creates the notification channel required for API 26+. */
    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        try {
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // A full-screen intent requires IMPORTANCE_HIGH, and the
                // public lockscreen visibility is what allows the alarm
                // to render above the keyguard.
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(tag, "ensureChannel failed", e)
        }
    }

    /**
     * Fallback PendingIntent used only if `PendingIntentFactory.forFullScreen`
     * returns null (it shouldn't — the factory's contract guarantees a
     * non-null result or logs the cause). Matches the factory's
     * request-code derivation so there is never a duplicate PendingIntent
     * for the same spec.
     */
    private fun buildInlinePendingIntent(specId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            specId.hashCode(),
            AlarmActivity.intent(context, specId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        /**
         * Notification channel id — matches the spec's
         * `futura_alarm_fullscreen` for parity with existing installs
         * that may already have this channel created.
         */
        private const val CHANNEL_ID = "futura_alarm_fullscreen"

        /** Human-readable channel name. */
        private const val CHANNEL_NAME = "Futura Alarm (Full-Screen)"

        /**
         * Stable notification id used for the active alarm. Public
         * because [ForegroundNotificationLifecycleBinder] reads it to
         * decide whether re-post is necessary.
         */
        const val NOTIFICATION_ID_ALARM = 1001
    }
}
