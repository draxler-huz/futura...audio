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
 * Alarm foreground service.
 *
 * Runs as `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+: it keeps a
 * user-set alarm session alive until it is dismissed, which is not one of
 * the standard types. The manifest carries the matching permission and
 * subtype property.
 *
 * On start, the service first calls startForeground (required promptly),
 * then asks [AlarmCoordinator] to resume the persisted session. If there is
 * none, it stops itself. A resumed session keeps the watchdog running, which
 * re-posts the notification if the OS drops it.
 *
 * The native engine is not wired to this service yet. Once playback runs
 * here, the type may change to MEDIA_PLAYBACK, which must then match the
 * manifest and the permission.
 *
 * Restart policy is `START_STICKY`, so the OS restarts the service after
 * an OEM kill and the session is restored from storage.
 */
@AndroidEntryPoint
class FuturaAlarmService : Service() {

    @Inject lateinit var coordinator: AlarmCoordinator
    @Inject lateinit var watchdog: MissedAlarmWatchdog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // The watchdog's wake lease follows the alarm lifecycle: armed on
        // Firing, released on Snoozed or Dismissed.
        scope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is AlarmCoordinator.AlarmEvent.Firing -> watchdog.arm(event.specId)
                    is AlarmCoordinator.AlarmEvent.Snoozed,
                    is AlarmCoordinator.AlarmEvent.Dismissed -> watchdog.disarm()
                    else -> Unit
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must run promptly after startForegroundService, so it comes first.
        ensureForeground()
        scope.launch {
            val hasSession = try {
                coordinator.restoreIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Session restore failed", e)
                false
            }
            if (hasSession) {
                // A restored FIRING session needs its lease again.
                coordinator.session.value
                    ?.takeIf { it.phase == AlarmCoordinator.Phase.FIRING }
                    ?.let { watchdog.arm(it.specId) }
                startWatchdog()
            } else {
                Log.i(TAG, "No session to resume; stopping")
                stopSelf()
            }
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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
