// app/src/main/java/com/futura/alarm/AlarmClockIntentPublisher.kt
package com.futura.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.futura.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System-wide status-bar synchroniser.
 *
 * Publishes the next trigger of an alarm with [AlarmManager.setAlarmClock],
 * so the status-bar clock icon shows the pending alarm.
 *
 * The operation intent sent by the system at trigger time is a broadcast
 * to an action nothing receives, so it does nothing. Firing the alarm is
 * owned by [AlarmSchedulerImpl]; using a second firing path would ring the
 * alarm twice. Best-effort: if the OS rejects the call, the icon simply
 * will not appear and the alarm still fires.
 */
@Singleton
class AlarmClockIntentPublisher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "AlarmClockIntentPublisher"
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Publish the next trigger of [spec]. Disabled alarms are withdrawn. */
    fun publish(spec: AlarmSpec) {
        if (!spec.enabled) {
            cancel(spec)
            return
        }
        try {
            val nextMs = spec.nextTriggerMillis()
            val showIntent = PendingIntent.getActivity(
                context,
                requestCode(spec.id),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(nextMs, showIntent),
                operationIntent(spec.id, create = true)!!
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to publish alarm ${spec.id}", e)
        }
    }

    /** Remove the status-bar entry for [spec]. */
    fun cancel(spec: AlarmSpec) {
        try {
            operationIntent(spec.id, create = false)?.let { alarmManager.cancel(it) }
        } catch (e: Exception) {
            Log.w(tag, "Failed to cancel status-bar alarm ${spec.id}", e)
        }
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun operationIntent(specId: String, create: Boolean): PendingIntent? {
        val intent = Intent(ACTION_CLOCK_INDICATOR)
            .setPackage(context.packageName)
            .putExtra(EXTRA_SPEC_ID, specId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode(specId) xor OPERATION_SALT,
            intent,
            if (create) flags else flags or PendingIntent.FLAG_NO_CREATE
        )
    }

    private fun requestCode(specId: String): Int = specId.hashCode() and 0x7FFFFFFF

    private companion object {
        const val ACTION_CLOCK_INDICATOR = "com.futura.action.CLOCK_INDICATOR"
        const val EXTRA_SPEC_ID = "com.futura.alarm.EXTRA_SPEC_ID"
        const val OPERATION_SALT = 0x5A5A
    }
}
