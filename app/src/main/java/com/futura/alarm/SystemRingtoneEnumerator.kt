// app/src/main/java/com/futura/alarm/SystemRingtoneEnumerator.kt
package com.futura.alarm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback enumerator fetching system default notification, ringtone,
 * and alarm URIs.
 *
 * Used by [PreflightAudioFallback] when a configured alarm sound cannot
 * be read (file missing, permission revoked, storage unmounted). The
 * enumerated defaults are guaranteed to play: they come from the OS
 * itself and are always readable.
 */
@Singleton
class SystemRingtoneEnumerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "SystemRingtoneEnumerator"

    /** All default alarm tones the OS exposes. */
    fun defaultAlarmTones(): List<Uri> =
        queryDefaults(RingtoneManager.TYPE_ALARM)

    /** All default notification tones. */
    fun defaultNotificationTones(): List<Uri> =
        queryDefaults(RingtoneManager.TYPE_NOTIFICATION)

    /** All default ringtones. */
    fun defaultRingtones(): List<Uri> =
        queryDefaults(RingtoneManager.TYPE_RINGTONE)

    /**
     * The single best fallback for an alarm. Prefers the OS alarm tone,
     * then notification tone, then any system ringtone, then the OS default
     * alarm URI. If even that fails, returns null and
     * [PreflightAudioFallback] degrades to vibration-only.
     */
    fun bestFallback(): Uri? {
        defaultAlarmTones().firstOrNull()?.let { return it }
        defaultNotificationTones().firstOrNull()?.let { return it }
        defaultRingtones().firstOrNull()?.let { return it }
        return try {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        } catch (e: Exception) {
            Log.e(tag, "No fallback ringtone available", e)
            null
        }
    }

    private fun queryDefaults(type: Int): List<Uri> {
        return try {
            val manager = RingtoneManager(context)
            manager.setType(type)
            manager.cursor.use { cursor ->
                val results = ArrayList<Uri>(cursor.count.coerceAtMost(16))
                while (cursor.moveToNext()) {
                    try {
                        manager.getRingtoneUri(cursor.position)?.let { results.add(it) }
                    } catch (e: Exception) {
                        // Skip unreadable cursor entries.
                    }
                }
                results
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to enumerate defaults for type $type", e)
            emptyList()
        }
    }
}
