// app/src/main/java/com/futura/alarm/AlarmSchedulerImpl.kt
package com.futura.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dual-path [AlarmScheduler].
 *
 * Primary: [AlarmManager.setExactAndAllowWhileIdle] (API 31+) or
 * [AlarmManager.setExact] on older APIs, with the
 * [SCHEDULE_EXACT_ALARM] / [USE_EXACT_ALARM] permission check.
 *
 * Fallback: an inexact window alarm scheduled 90 s after the primary,
 * ensuring the alarm still fires even if the OS defers the exact alarm
 * under aggressive doze or battery-saving modes. The fallback is cancelled
 * by [AlarmCoordinator] once the primary has fired or the alarm is dismissed.
 *
 * Drift correction: on TIME_SET / TIMEZONE_CHANGED, the caller
 * (AlarmReceiver / BootCompletionHandler) invokes [rescheduleAll], which
 * re-issues every pending intent against the new wall-clock time.
 */
@Singleton
class AlarmSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AlarmStore
) : AlarmScheduler {

    private val tag = "AlarmSchedulerImpl"
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(spec: AlarmSpec): Long {
        if (!spec.enabled) {
            cancel(spec.id)
            return -1L
        }
        val triggerAt = spec.nextTriggerMillis()
        return if (armAt(spec.id, triggerAt)) triggerAt else -1L
    }

    override fun scheduleAt(specId: String, triggerAtMillis: Long): Boolean =
        armAt(specId, triggerAtMillis)

    override fun cancel(specId: String) {
        try {
            val intent = AlarmReceiver.buildIntent(context, specId)
            val primary = PendingIntent.getBroadcast(
                context, requestCode(specId, PRIMARY), intent,
                pendingIntentFlags() or PendingIntent.FLAG_NO_CREATE
            )
            val fallback = PendingIntent.getBroadcast(
                context, requestCode(specId, FALLBACK), intent,
                pendingIntentFlags() or PendingIntent.FLAG_NO_CREATE
            )
            primary?.let { alarmManager.cancel(it) }
            fallback?.let { alarmManager.cancel(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to cancel alarm $specId", e)
        }
    }

    override fun rescheduleAll() {
        try {
            val specs = store.specs.value
            specs.forEach { schedule(it) }
            Log.i(tag, "rescheduleAll(): ${specs.size} specs")
        } catch (e: Exception) {
            Log.e(tag, "rescheduleAll() failed", e)
        }
    }

    override fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Arms the primary and fallback for [specId] at [triggerAt]. */
    private fun armAt(specId: String, triggerAt: Long): Boolean {
        val intent = AlarmReceiver.buildIntent(context, specId)
        return try {
            val fallback = PendingIntent.getBroadcast(
                context, requestCode(specId, FALLBACK), intent, pendingIntentFlags()
            )
            if (canScheduleExact()) {
                val primary = PendingIntent.getBroadcast(
                    context, requestCode(specId, PRIMARY), intent, pendingIntentFlags()
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, primary
                    )
                } else {
                    @Suppress("DEPRECATION")
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, primary)
                }
                // Inexact backup, 90 s after the primary.
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt + FALLBACK_DELAY_MS,
                    FALLBACK_WINDOW_MS,
                    fallback
                )
            } else {
                // No exact-alarm permission: rely on the inexact path only,
                // scheduled at the intended time.
                Log.w(tag, "Exact alarm permission missing; using inexact path only")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fallback)
            }
            true
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException arming alarm $specId", e)
            false
        } catch (e: Exception) {
            Log.e(tag, "Unexpected exception arming alarm $specId", e)
            false
        }
    }

    private fun requestCode(specId: String, kind: Int): Int {
        // Stable hash of specId XORed with the kind tag; ensures the two
        // PendingIntents (primary, fallback) get different request codes
        // so the OS tracks them separately.
        return (specId.hashCode() and 0x7FFFFFFF) xor kind
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private companion object {
        const val PRIMARY = 0
        const val FALLBACK = 1
        const val FALLBACK_DELAY_MS = 90_000L
        const val FALLBACK_WINDOW_MS = 60_000L
    }
}
