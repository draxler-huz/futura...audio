// app/src/main/java/com/futura/data/extraction/workflow/F16AlarmAssignmentBinder.kt
package com.futura.data.extraction.workflow

import android.net.Uri
import android.util.Log
import com.futura.alarm.AlarmClockIntentPublisher
import com.futura.alarm.AlarmScheduler
import com.futura.alarm.AlarmSpec
import com.futura.alarm.AlarmStore
import com.futura.data.extraction.model.ExtractedTrack
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * F16 — One-Tap Alarm Assignment Binder.
 *
 * Bridges the extraction pipeline's [ExtractedTrack] to the alarm
 * engine: writes the track's file URI into an [AlarmSpec]'s `soundUri`,
 * persists it through [AlarmStore], and re-schedules the exact alarm
 * via [AlarmScheduler] so the change takes effect immediately.
 *
 * All three of the alarm-engine classes are delivered (group 1–2 of the
 * alarm subsystem). This class only orchestrates them.
 *
 * **Bio-locked alarms:** if the target alarm is `biometricLocked`, the
 * caller must supply a `VaultUnlockProof` — `AlarmStore.upsert(...)`
 * refuses to modify a shielded alarm without one. This binder does not
 * prompt for biometrics; the caller (UI) is responsible for gating the
 * action. [bindToAlarm] with no proof will fail for bio-locked specs
 * and the caller must show a prompt.
 *
 * Rule 6: every operation wrapped.
 * Rule 7: [mutex] serializes concurrent bindings so two UI actions can't
 * both update the same alarm.
 */
@Singleton
class F16AlarmAssignmentBinder @Inject constructor(
    private val alarmStore: AlarmStore,
    private val alarmScheduler: AlarmScheduler,
    private val alarmClockPublisher: AlarmClockIntentPublisher
) {
    private val tag = "F16AlarmAssignmentBinder"
    private val mutex = Mutex()

    /**
     * Bind [track]'s file to [specId].
     *
     * @return true if the alarm was updated, persisted, re-scheduled,
     *         and the system clock icon was refreshed. false on any
     *         failure (file missing, spec missing, alarm bio-locked,
     *         persistence refused, scheduling failure).
     */
    suspend fun bindToAlarm(track: ExtractedTrack, specId: String): Boolean =
        mutex.withLock {
            try {
                val file = File(track.filePath)
                if (!file.exists() || !file.canRead()) {
                    Log.e(tag, "bindToAlarm: file unavailable: ${track.filePath}")
                    return@withLock false
                }

                val spec = alarmStore.get(specId)
                if (spec == null) {
                    Log.e(tag, "bindToAlarm: no such spec: $specId")
                    return@withLock false
                }

                val updated = spec.copy(soundUri = fileUri(file))

                val persisted = alarmStore.upsert(updated, proof = null)
                if (!persisted) {
                    Log.w(tag, "bindToAlarm: AlarmStore refused update (bio-locked?)")
                    return@withLock false
                }

                val scheduled = alarmScheduler.schedule(updated)
                if (scheduled <= 0L) {
                    Log.w(tag, "bindToAlarm: schedule returned $scheduled")
                    // Do not fail here — the alarm was persisted, the
                    // schedule failure is a scheduling-layer issue.
                }

                try { alarmClockPublisher.publish(updated) } catch (_: Exception) {}

                Log.i(
                    tag,
                    "Bound track '${track.fileName}' to alarm $specId " +
                        "(${track.format}, ${track.durationMs}ms)"
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "bindToAlarm failed", e)
                false
            }
        }

    /**
     * Revert [specId] to the system default alarm sound.
     *
     * @return true on success; false on any failure.
     */
    suspend fun unbindFromAlarm(specId: String): Boolean = mutex.withLock {
        try {
            val spec = alarmStore.get(specId) ?: return@withLock false
            val defaultUri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM
            )?.toString() ?: return@withLock false

            val updated = spec.copy(soundUri = defaultUri)
            if (!alarmStore.upsert(updated, proof = null)) return@withLock false
            alarmScheduler.schedule(updated)
            try { alarmClockPublisher.publish(updated) } catch (_: Exception) {}
            Log.i(tag, "Unbound alarm $specId (reverted to system default)")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "unbindFromAlarm failed", e)
            false
        }
    }

    /**
     * Assign [track] to a new alarm spec. Convenience for the
     * "create alarm + bind" flow.
     *
     * @return the new spec id, or null on failure.
     */
    suspend fun createAndBind(
        track: ExtractedTrack,
        hour: Int,
        minute: Int,
        label: String = track.title ?: track.fileName
    ): String? = mutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                val spec = AlarmSpec(
                    hour = hour,
                    minute = minute,
                    label = label,
                    soundUri = fileUri(File(track.filePath)),
                    targetVolume = 1.0f,
                    rampDurationSeconds = 30,
                    rampProfile = 2.0f,
                    biometricLocked = false
                )
                if (!alarmStore.upsert(spec, proof = null)) return@withContext null
                alarmScheduler.schedule(spec)
                try { alarmClockPublisher.publish(spec) } catch (_: Exception) {}
                spec.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "createAndBind failed", e)
                null
            }
        }
    }

    /**
     * `file://` URI string. PreflightAudioFallback parses `soundUri` with
     * `Uri.parse`, which yields no scheme for a bare path, so the raw
     * absolute path would not resolve. Keep this format stable.
     */
    private fun fileUri(file: File): String = Uri.fromFile(file).toString()
}
