// app/src/main/java/com/futura/alarm/ForegroundNotificationLifecycleBinder.kt
package com.futura.alarm

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resilient binder that keeps the active-alarm notification visible
 * even after system memory pressure drops it.
 *
 * Two mechanisms:
 *
 *   1. **Watchdog re-post** — [repairIfMissing] is called periodically
 *      (by `FuturaAlarmService`'s watchdog, delivered in group 1). If
 *      the notification is missing, this re-posts it.
 *   2. **Direct re-post on the active session** — [rebind] forces a
 *      re-post of the current session's notification with the same
 *      notification id, triggering OS dedup.
 *
 * On modern Android the OS does not ordinarily drop an ongoing
 * notification of a foreground service, but some OEM customisations
 * (MiUI, EMUI) do — this binder exists specifically for those cases.
 */
@Singleton
class ForegroundNotificationLifecycleBinder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dynamicNotificationLauncher: DynamicNotificationLauncher
) {
    private val tag = "ForegroundNotificationLifecycleBinder"
    private val nm = NotificationManagerCompat.from(context)

    /**
     * If the alarm notification is not currently active, re-post it.
     * Idempotent — safe to call repeatedly.
     */
    fun repairIfMissing(spec: AlarmSpec): Boolean {
        if (!isNotificationActive()) {
            Log.i(tag, "Alarm notification missing; re-posting")
            return dynamicNotificationLauncher.show(spec)
        }
        return true
    }

    /**
     * Force a re-post of the alarm notification for [spec]. Uses the
     * same id as [DynamicNotificationLauncher.NOTIFICATION_ID_ALARM], so
     * the OS replaces the existing notification. No cancel first: that
     * would remove it briefly, which flickers and can retrigger the heads-up.
     */
    fun rebind(spec: AlarmSpec): Boolean {
        return dynamicNotificationLauncher.show(spec)
    }

    /**
     * True if a notification with the alarm id is currently active.
     * Uses `NotificationManagerCompat.getActiveNotifications` on
     * API 23+; below 23 we conservatively return true (can't inspect,
     * so assume it's there — repairing on a false assumption would be
     * harmless but wasteful).
     */
    private fun isNotificationActive(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                nm.activeNotifications.any {
                    it.id == DynamicNotificationLauncher.NOTIFICATION_ID_ALARM
                }
            } else {
                true
            }
        } catch (e: Exception) {
            // SecurityException if notification listener permission missing.
            // Assume "active" — repair is best-effort anyway.
            true
        }
    }
}
