// app/src/main/java/com/futura/alarm/PermissionGuard.kt
package com.futura.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime permission auditor.
 *
 * Reports the state of every permission an alarm needs and provides a
 * single `checkAll()` used at startup to decide whether to prompt the
 * user with a guided onboarding flow.
 *
 * Note: `SCHEDULE_EXACT_ALARM` is a *special app-access* permission, not
 * a runtime permission — the user grants it from the OS Settings screen,
 * not via `requestPermissions`. `PermissionGuard` reports the current
 * state; the caller decides how to route the user to Settings.
 */
@Singleton
class PermissionGuard @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Status { GRANTED, DENIED, RESTRICTED, NOT_APPLICABLE }

    data class Report(
        val exactAlarm: Status,
        val notification: Status,
        val mediaAudio: Status,
        val vibrate: Status
    ) {
        /** True if the app can currently fire an alarm end-to-end. */
        val canFireAlarm: Boolean
            get() = (notification == Status.GRANTED || notification == Status.NOT_APPLICABLE) &&
                    vibrate == Status.GRANTED
    }

    fun checkAll(): Report = Report(
        exactAlarm = checkExactAlarm(),
        notification = checkNotification(),
        mediaAudio = checkMediaAudio(),
        vibrate = checkVibrate()
    )

    // ── Individual checks ─────────────────────────────────────────

    fun checkExactAlarm(): Status {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (am.canScheduleExactAlarms()) Status.GRANTED else Status.DENIED
            } else {
                Status.NOT_APPLICABLE
            }
        } catch (e: Exception) {
            Status.DENIED
        }
    }

    fun checkNotification(): Status {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) Status.GRANTED else Status.DENIED
            } else {
                // Pre-Tiramisu, notifications are granted at install time
                // unless the user disabled them in Settings.
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                if (nm.areNotificationsEnabled()) Status.GRANTED else Status.DENIED
            }
        } catch (e: Exception) {
            Status.DENIED
        }
    }

    fun checkMediaAudio(): Status {
        return try {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            val granted = ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) Status.GRANTED else Status.DENIED
        } catch (e: Exception) {
            Status.DENIED
        }
    }

    fun checkVibrate(): Status {
        return try {
            // VIBRATE is a normal (install-time) permission — always granted
            // on modern Android unless the user revoked it via OEM overrides.
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.VIBRATE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) Status.GRANTED else Status.DENIED
        } catch (e: Exception) {
            Status.DENIED
        }
    }
}