// app/src/main/java/com/futura/alarm/AlarmReceiver.kt
package com.futura.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the app's own `ACTION_FIRE`, `ACTION_DISMISS` and
 * `ACTION_SNOOZE` intents, which only [AlarmSchedulerImpl] creates as
 * PendingIntents. Acquires a short partial wake-lock before dispatching to
 * [AlarmCoordinator] so the CPU doesn't sleep during the handoff.
 * System clock integration is outward only, via [AlarmClockIntentPublisher].
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: AlarmCoordinator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val specId = intent.getStringExtra(EXTRA_SPEC_ID) ?: return
        val action = intent.action ?: return

        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "futura:alarm_receiver"
        )
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            when (action) {
                ACTION_FIRE -> {
                    scope.launch {
                        try {
                            coordinator.onAlarmFired(specId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Alarm fire dispatch failed", e)
                        } finally {
                            if (wakeLock.isHeld) wakeLock.release()
                        }
                    }
                }
                ACTION_DISMISS -> {
                    scope.launch {
                        try {
                            coordinator.onAlarmDismissed(specId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Alarm dismiss dispatch failed", e)
                        } finally {
                            if (wakeLock.isHeld) wakeLock.release()
                        }
                    }
                }
                ACTION_SNOOZE -> {
                    scope.launch {
                        try {
                            coordinator.onAlarmSnoozed(specId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Alarm snooze dispatch failed", e)
                        } finally {
                            if (wakeLock.isHeld) wakeLock.release()
                        }
                    }
                }
                else -> if (wakeLock.isHeld) wakeLock.release()
            }
        } catch (e: Exception) {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

        const val ACTION_FIRE = "com.futura.action.ALARM_FIRE"
        const val ACTION_DISMISS = "com.futura.action.ALARM_DISMISS"
        const val ACTION_SNOOZE = "com.futura.action.ALARM_SNOOZE"
        const val EXTRA_SPEC_ID = "com.futura.alarm.EXTRA_SPEC_ID"

        fun buildIntent(context: Context, specId: String): Intent =
            Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra(EXTRA_SPEC_ID, specId)
            }
    }
}
