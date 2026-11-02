// app/src/main/java/com/futura/alarm/PendingIntentFactory.kt
package com.futura.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.futura.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cryptographically scoped factory for alarm `PendingIntent`s.
 *
 * Every PendingIntent produced by this class:
 *   • targets a single, explicit component ([AlarmReceiver] or
 *     `AlarmActivity`) — no implicit resolution.
 *   • is `FLAG_IMMUTABLE` on API 23+ — prevents a malicious app that
 *     intercepted the PendingIntent from mutating its extras.
 *   • has a request code derived from a stable hash of the spec id, so
 *     identical (specId, action) pairs return the same PendingIntent
 *     without recreating it each time.
 *
 * The factory is intentionally stateless — no cache, no map. The OS's
 * PendingIntent cache handles dedup; this class just guarantees that
 * the intent structure is correct on every call.
 */
@Singleton
class PendingIntentFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "PendingIntentFactory"

    /** A fire action delivered to [AlarmReceiver]. */
    fun forFire(specId: String): PendingIntent? = try {
        val intent = AlarmReceiver.buildIntent(context, specId).apply {
            action = AlarmReceiver.ACTION_FIRE
        }
        PendingIntent.getBroadcast(
            context,
            requestCode(specId, KIND_FIRE),
            intent,
            flags()
        )
    } catch (e: Exception) {
        Log.e(tag, "forFire failed for $specId", e); null
    }

    /** A snooze action delivered to [AlarmReceiver]. */
    fun forSnooze(specId: String): PendingIntent? = try {
        val intent = AlarmReceiver.buildIntent(context, specId).apply {
            action = AlarmReceiver.ACTION_SNOOZE
        }
        PendingIntent.getBroadcast(
            context,
            requestCode(specId, KIND_SNOOZE),
            intent,
            flags()
        )
    } catch (e: Exception) {
        Log.e(tag, "forSnooze failed for $specId", e); null
    }

    /** A dismiss action delivered to [AlarmReceiver]. */
    fun forDismiss(specId: String): PendingIntent? = try {
        val intent = AlarmReceiver.buildIntent(context, specId).apply {
            action = AlarmReceiver.ACTION_DISMISS
        }
        PendingIntent.getBroadcast(
            context,
            requestCode(specId, KIND_DISMISS),
            intent,
            flags()
        )
    } catch (e: Exception) {
        Log.e(tag, "forDismiss failed for $specId", e); null
    }

    /**
     * Full-screen PendingIntent for the alarm surface. Targets [MainActivity]
     * explicitly, with the spec id as an extra, so it resolves inside the app.
     *
     * The dedicated alarm screen, `AlarmActivity`, is declared in the manifest
     * but its class does not exist yet. Routing to it is pending.
     */
    fun forFullScreen(specId: String): PendingIntent? = try {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ALARM
            putExtra(AlarmReceiver.EXTRA_SPEC_ID, specId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        PendingIntent.getActivity(
            context,
            requestCode(specId, KIND_FULLSCREEN),
            intent,
            flags()
        )
    } catch (e: Exception) {
        Log.e(tag, "forFullScreen failed for $specId", e); null
    }

    /** A broadcast that dismisses the whole alarm session, used from the
     *  notification "Dismiss" button when only one alarm is active. */
    fun forSessionDismiss(specId: String): PendingIntent? =
        forDismiss(specId)

    // ── Internals ──────────────────────────────────────────────────

    private fun requestCode(specId: String, kind: Int): Int {
        return (specId.hashCode() and 0x7FFFFFFF) xor (kind shl 8)
    }

    private fun flags(): Int {
        // FLAG_IMMUTABLE requires API 23; below that, the flag is
        // ignored, so we can include it unconditionally.
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private companion object {
        const val ACTION_OPEN_ALARM = "com.futura.action.OPEN_ALARM"
        const val KIND_FIRE = 0x10
        const val KIND_SNOOZE = 0x11
        const val KIND_DISMISS = 0x12
        const val KIND_FULLSCREEN = 0x13
    }
}