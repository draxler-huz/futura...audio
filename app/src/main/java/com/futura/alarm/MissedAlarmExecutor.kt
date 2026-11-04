package com.futura.alarm

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.futura.tts.TextToSpeechWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Performs the user-visible actions for stages emitted by MissedAlarmWatchdog.
 * GENTLE starts with the alarm; the existing watchdog advances through the
 * remaining stages on its configured cadence.
 */
@Singleton
class MissedAlarmExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchdog: MissedAlarmWatchdog,
    private val vibration: VibrationPatternSynthesizer,
    private val tts: TextToSpeechWrapper
) {
    private val tag = "MissedAlarmExecutor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var observeJob: Job? = null
    @Volatile private var activeSpecId: String? = null
    private var originalAlarmVolume: Int? = null
    private var maxAlarmVolume: Int? = null

    /** Arm the watchdog and observe its stage flow. Safe for repeated starts. */
    suspend fun start(spec: AlarmSpec) = mutex.withLock {
        if (observeJob?.isActive == true && activeSpecId == spec.id) return@withLock

        stopLocked()
        activeSpecId = spec.id
        try {
            watchdog.arm(spec.id)
            observeJob = scope.launch {
                try {
                    watchdog.currentStage.collectLatest { stage ->
                        if (activeSpecId == spec.id) executeStage(stage)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, "Stage observer failed", e)
                }
            }
            Log.i(tag, "Executor started for " + spec.id)
        } catch (e: Exception) {
            activeSpecId = null
            try { watchdog.disarm() } catch (_: Exception) {}
            Log.e(tag, "Executor start failed for " + spec.id, e)
        }
    }

    /** Stop escalation and restore any alarm-stream level changed here. */
    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        try { observeJob?.cancelAndJoin() } catch (_: Exception) {}
        observeJob = null
        activeSpecId = null
        try { watchdog.disarm() } catch (e: Exception) {
            Log.w(tag, "Watchdog disarm failed", e)
        }
        try { vibration.stop() } catch (_: Exception) {}
        try { tts.stop() } catch (_: Exception) {}
        restoreAlarmVolume()
    }

    private suspend fun executeStage(stage: EscalationStage) {
        try {
            Log.i(tag, "Executing stage: " + stage)
            when (stage) {
                EscalationStage.GENTLE -> Unit // AlarmAudioSession owns the gentle tier.
                EscalationStage.VOICE -> tts.speak(
                    "Your alarm is still ringing. Please wake up and dismiss it.",
                    queueMode = TextToSpeech.QUEUE_FLUSH
                )
                EscalationStage.VOLUME -> {
                    bumpAlarmVolume()
                    vibration.stop()
                    vibration.start(EscalationStage.VOLUME)
                }
                EscalationStage.SCREEN -> launchAlarmOverlay()
                EscalationStage.SIREN -> {
                    bumpAlarmVolume()
                    vibration.stop()
                    vibration.start(EscalationStage.SIREN)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Stage action failed: " + stage, e)
        }
    }

    private fun bumpAlarmVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (originalAlarmVolume == null) {
                originalAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (max > 0) {
                maxAlarmVolume = max
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (e: Exception) {
            Log.w(tag, "Alarm volume escalation failed", e)
        }
    }

    private fun restoreAlarmVolume() {
        val original = originalAlarmVolume ?: return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Do not overwrite a volume change the user made while the alarm rang.
            if (maxAlarmVolume != null &&
                am.getStreamVolume(AudioManager.STREAM_ALARM) == maxAlarmVolume) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
            }
        } catch (e: Exception) {
            Log.w(tag, "Alarm volume restore failed", e)
        } finally {
            originalAlarmVolume = null
            maxAlarmVolume = null
        }
    }

    private fun launchAlarmOverlay() {
        val specId = activeSpecId ?: return
        if (!OverlayPermissionHelper.canDrawOverlays(context)) {
            Log.i(tag, "Overlay permission missing; notification path remains available")
            return
        }
        try {
            context.startActivity(AlarmActivity.intent(context, specId))
            Log.i(tag, "AlarmActivity re-launched for " + specId)
        } catch (e: Exception) {
            Log.e(tag, "AlarmActivity launch failed", e)
        }
    }
}
