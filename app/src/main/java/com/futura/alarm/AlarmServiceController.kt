// app/src/main/java/com/futura/alarm/AlarmServiceController.kt
package com.futura.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronous controller for [FuturaAlarmService] lifecycle.
 *
 * Owns `startForeground` (via the service itself) and `stopSelf`-style
 * shutdown. Does NOT own binding — binding is a caller-side concern
 * (MediaSessionCoordinator, when delivered, will bind for transport
 * controls).
 */
@Singleton
class AlarmServiceController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "AlarmServiceController"

    fun start() {
        try {
            val intent = Intent(context, FuturaAlarmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start FuturaAlarmService", e)
        }
    }

    fun stop() {
        try {
            val intent = Intent(context, FuturaAlarmService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop FuturaAlarmService", e)
        }
    }
}
