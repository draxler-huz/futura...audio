// app/src/main/java/com/futura/alarm/BootCompletionHandler.kt
package com.futura.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Boot and time-change broadcast receiver.
 *
 * Triggers a full alarm re-schedule when:
 *   • `BOOT_COMPLETED` — the OS restarted; all AlarmManager alarms are
 *     gone, must be re-issued.
 *   • `LOCKED_BOOT_COMPLETED` — same, before user unlock (only matters
 *     if alarms are directBootAware; they currently are not, but the
 *     receiver still fires and the store will report empty pre-unlock —
 *     harmless).
 *   • `MY_PACKAGE_REPLACED` — app update wiped the AlarmManager state.
 *   • `TIMEZONE_CHANGED` — wall-clock offset changed; every scheduled
 *     trigger needs recalculation.
 *   • `TIME_SET` — user manually changed the clock or the network
 *     adjusted it; same fix-up as TIMEZONE_CHANGED.
 *
 * Rescheduling runs on an IO coroutine that may take 50–500 ms for many
 * alarms. [goAsync] keeps the process alive until it finishes.
 */
@AndroidEntryPoint
class BootCompletionHandler : BroadcastReceiver() {

    @Inject lateinit var store: AlarmStore
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var publisher: AlarmClockIntentPublisher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> reschedule(goAsync())
            else -> Unit
        }
    }

    private fun reschedule(pending: BroadcastReceiver.PendingResult) {
        scope.launch {
            try {
                store.load()
                val specs = store.specs.value
                for (spec in specs) {
                    if (!spec.enabled) {
                        scheduler.cancel(spec.id)
                        continue
                    }
                    try {
                        val nextMs = scheduler.schedule(spec)
                        if (nextMs > 0L) {
                            publisher.publish(spec)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reschedule ${spec.id}", e)
                    }
                }
                Log.i(TAG, "Rescheduled ${specs.size} alarm(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Reschedule loop failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletionHandler"
    }
}
