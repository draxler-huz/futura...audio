// app/src/main/java/com/futura/alarm/DynamicNotificationLauncher.kt
package com.futura.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicNotificationLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingIntents: PendingIntentFactory
) {
    private val tag = "DynamicNotificationLauncher"

    private val manager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun show(spec: AlarmSpec): Boolean = try {
        ensureChannel()

        // Overlay path: fire the FullScreenIntentReceiver broadcast when
        // SYSTEM_ALERT_WINDOW is granted. The receiver holds a BAL
        // exemption through SYSTEM_ALERT_WINDOW and can foreground
        // AlarmActivity over whatever is on screen. Without the
        // permission the broadcast is not sent — the notification's
        // full-screen intent remains as the lock-screen fallback.
        if (OverlayPermissionHelper.canDrawOverlays(context)) {
            try {
                context.sendBroadcast(
                    FullScreenIntentReceiver.intent(context, spec.id)
                )
            } catch (e: Exception) {
                Log.w(tag, "Overlay launch broadcast failed", e)
            }
        }

        val canUseFullScreen = OverlayPermissionHelper.canUseFullScreenIntent(context)
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
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        fullScreen?.let { pi ->
            builder.setFullScreenIntent(pi, true)
            builder.setContentIntent(pi)
        }

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

        if (!canUseFullScreen) {
            Log.w(
                tag,
                "USE_FULL_SCREEN_INTENT revoked on API 34+; " +
                    "notification will be heads-up only when unlocked"
            )
        }

        try {
            manager.notify(NOTIFICATION_ID_ALARM, builder.build())
            true
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission denied", e)
            false
        }
    } catch (e: Exception) {
        Log.e(tag, "show() failed for ${spec.id}", e)
        false
    }

    fun cancel() {
        try {
            manager.cancel(NOTIFICATION_ID_ALARM)
        } catch (e: Exception) {
            Log.w(tag, "cancel failed", e)
        }
    }

    fun isNotificationActive(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            manager.activeNotifications.any { it.id == NOTIFICATION_ID_ALARM }
        } else true
    } catch (e: Exception) { true }

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        try {
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(tag, "ensureChannel failed", e)
        }
    }

    companion object {
        private const val CHANNEL_ID = "futura_alarm_fullscreen"
        private const val CHANNEL_NAME = "Futura Alarm (Full-Screen)"
        const val NOTIFICATION_ID_ALARM = 1001
    }
}
