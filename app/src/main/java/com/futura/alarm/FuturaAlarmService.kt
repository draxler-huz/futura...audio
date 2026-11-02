// app/src/main/java/com/futura/alarm/FuturaAlarmService.kt
package com.futura.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.futura.R
import com.futura.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 1 alarm foreground service.
 *
 * Runs as `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`, keeps the alarm
 * engine alive across system memory pressure, and runs an in-process
 * watchdog coroutine that verifies the service is still foregrounded and
 * re-posts the notification if the OS or an OEM background-killer
 * stripped it.
 *
 * The native C++ engine integration (continuous audio-engine resurrection
 * via `NativeEngine` / `NativePlayerBridge`) is stubbed — this service
 * does not currently call into the native engine directly. The audio
 * pipeline is driven by [AlarmCoordinator] → [SnoozeToPlayerHandshake]
 * (group 2). Once that handshake is wired, the service will host the
 * engine. The service's responsibility today is:
 *
 *   1. Foreground presence + notification channel maintenance.
 *   2. Watchdog that re-posts the notification on OEM kills.
 *   3. Graceful stop when the alarm session ends.
 *
 * Restart policy is `START_STICKY`, so the OS restarts the service after
 * an OEM kill; the watchdog picks up the existing session from
 * [AlarmCoordinator] and continues.
 */
@AndroidEntryPoint
class FuturaAlarmService : Service() {

    @Inject lateinit var coordinator: AlarmCoordinator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        startWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── Foreground / watchdog ────────────────────────────────────

    private fun ensureForeground() {
        try {
            createNotificationChannel()
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    val session = coordinator.session.value
                    if (session == null ||
                        session.phase == AlarmCoordinator.Phase.DISMISSED) {
                        Log.i(TAG, "No active session; stopping service")
                        stopSelf()
                        return@launch
                    }
                    // Re-post — cheap and idempotent; ensures the OS
                    // always sees a live notification even after an OEM
                    // background-killer stripped it.
                    ensureForeground()
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog tick failed", e)
                }
            }
        }
    }

    // ── Notification ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_notification_channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    getString(R.string.alarm_notification_channel_service_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val session = coordinator.session.value
        val title = session?.spec?.label?.takeIf { it.isNotBlank() }
            ?: getString(R.string.alarm_title)
        val text = when (session?.phase) {
            AlarmCoordinator.Phase.FIRING -> getString(R.string.alarm_title)
            AlarmCoordinator.Phase.SNOOZED -> getString(R.string.alarm_missed)
            else -> getString(R.string.alarm_notification_channel_service_name)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "FuturaAlarmService"
        private const val CHANNEL_ID = "futura_alarm_service"
        private const val NOTIFICATION_ID = 0xF01A
        private const val REQUEST_CODE_CONTENT = 0xF01B
        private const val WATCHDOG_INTERVAL_MS = 15_000L
    }
}