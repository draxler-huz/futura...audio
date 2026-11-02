// app/src/main/java/com/futura/alarm/AlarmScheduler.kt
package com.futura.alarm

/**
 * Contract for scheduling alarms with the Android AlarmManager.
 *
 * Implementations are responsible for:
 *   • Using the strongest available API on the running OS
 *     (`setExactAndAllowWhileIdle` on API 31+, `setExact` below).
 *   • Registering a secondary inexact fallback window so the OS cannot
 *     silently drop the alarm under deep-doze restrictions.
 *   • Cancelling the fallback when the primary fires successfully.
 *   • Reconciling all persisted alarms on boot, timezone change, and
 *     wall-clock update.
 */
interface AlarmScheduler {

    /** Schedule [spec] for its next trigger. Returns the epoch ms of the
     *  scheduled primary trigger, or -1 on failure. Idempotent — a
     *  second call with the same id replaces the previous schedule. */
    fun schedule(spec: AlarmSpec): Long

    /** Arm a one-shot trigger for [specId] at [triggerAtMillis], replacing
     *  any existing schedule for that id. Used for snoozes. */
    fun scheduleAt(specId: String, triggerAtMillis: Long): Boolean

    /** Cancel both the primary and fallback pending intents for [specId]. */
    fun cancel(specId: String)

    /** Re-read all alarms from the store and reschedule them. Called on
     *  BOOT_COMPLETED, TIMEZONE_CHANGED, TIME_SET, MY_PACKAGE_REPLACED.
     *  The store must already be loaded. */
    fun rescheduleAll()

    /** Whether the OS currently grants exact-alarm permission. */
    fun canScheduleExact(): Boolean
}
