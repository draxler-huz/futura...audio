// app/src/main/java/com/futura/alarm/DndBypassResolver.kt
package com.futura.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.futura.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configures the alarm notification channel to pierce Do Not Disturb.
 *
 * On API 26+, Do Not Disturb is a per-channel decision: a channel
 * created with `setBypassDnd(true)` will make notifications posted to it
 * bypass DND unless the user has explicitly revoked that privilege via
 * Settings. This class ensures the channel exists with bypassDnd set.
 *
 * The channel's sound is set at creation time by the OS and cannot be
 * changed afterward — so this class creates the channel with the current
 * alarm default URI. Subsequent user sound changes are applied by the
 * audio pipeline ([PreflightAudioFallback]), not by the notification
 * channel.
 *
 * [CHANNEL_ID] is a fixed identifier, not a localized string. Using a
 * translated name as the id would create a new channel per language.
 */
@Singleton
class DndBypassResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "DndBypassResolver"
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Ensure the alarm notification channel exists with bypassDnd.
     *
     * Idempotent. If the channel already exists, this method cannot
     * change its bypassDnd state — the user's choices prevail. The
     * caller should verify user DND settings are permissive enough (or
     * fall back to a foreground service with a notification already
     * granted bypass).
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
                return
            }
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val soundUri = try {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            } catch (e: Exception) { null }

            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alarm_notification_channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    context.getString(R.string.alarm_notification_channel_alarm_description)
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
            Log.i(tag, "Alarm notification channel created with bypassDnd")
        } catch (e: SecurityException) {
            Log.w(tag, "Cannot create channel with bypassDnd (user policy?)", e)
        } catch (e: Exception) {
            Log.e(tag, "Failed to ensure alarm channel", e)
        }
    }

    companion object {
        /** Stable id for the alarm alert channel. Shared with DynamicNotificationLauncher. */
        const val CHANNEL_ID = "futura_alarm_alerts"
    }
}
