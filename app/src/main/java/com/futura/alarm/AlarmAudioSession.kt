package com.futura.alarm

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.futura.engine.NativePlayerBridge
import com.futura.engine.SourceType
import com.futura.tone.AlarmToneGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.futura.engine.DspChainController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
* Runs the audio side of an alarm session. AlarmSessionOrchestrator drives
 * its lifecycle; the circadian ramp drives the DSP pre-amp, and nothing else.
 *
 * - Firing starts the DSP chain, the ramp and the gain loop.
 * - Snoozed and Dismissed stop them. The gain loop sends 0f before it ends.
 *
 * The sink carries only the circadian gain. The Fletcher-Munson boost is
 * already part of that gain, so no band correction goes to the EQ.
 * Ducking stays in [VoiceDropStitcher], so no duck goes to the DSP.
 */
@Singleton
class AlarmAudioSession @Inject constructor(
    private val coordinator: AlarmCoordinator,
    private val ramp: CircadianRampModule,
    private val sync: CircadianAudioSync,
    private val dsp: DspChainController,
    private val audioFocus: AudioFocusStealer,
    private val vibration: VibrationPatternSynthesizer,
    @ApplicationContext private val context: Context,
) {
    private val tag = "AlarmAudioSession"
    private val lifecycleMutex = Mutex()
    private var activeSpecId: String? = null


    /** Restart the audio after the service is restored with a FIRING session. */
    suspend fun resumeIfFiring() {
        val session = coordinator.session.value ?: return
        if (session.phase == AlarmCoordinator.Phase.FIRING) start(session.spec)
    }

    internal suspend fun start(spec: AlarmSpec) = lifecycleMutex.withLock {
        if (activeSpecId == spec.id) return@withLock
        if (activeSpecId != null) stopLocked()
        activeSpecId = spec.id

        try {
            // Keep the native player; add focus and gentle haptics around it.
            // The watchdog already owns the wake lease for the active alarm.
            if (!audioFocus.acquire()) {
                Log.w(tag, "Audio focus denied; continuing with alarm")
            }

            val now = System.currentTimeMillis()
            val plan = CircadianRampModule.RampPlan(
                startMs = now,
                endMs = now + spec.rampDurationSeconds.coerceAtLeast(1) * 1000L,
                aggression = spec.rampProfile
            )
            sync.nativeGainSink = { gain -> dsp.setCircadianLinear(gain) }
            dsp.start()
            if (!dsp.enableChain()) {
                Log.w(tag, "DSP chain did not enable; the ramp runs without the DSP")
            }
            ramp.start(plan)
            sync.start()
            startTone()
            vibration.start(EscalationStage.GENTLE)
            Log.i(tag, "Audio session started for " + spec.id)
        } catch (e: CancellationException) {
            stopLocked()
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Audio session start failed for " + spec.id, e)
            stopLocked()
        }
    }

    /** Stop native playback, ramp, haptics, and audio focus. Idempotent. */
    internal suspend fun stop() = lifecycleMutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        if (activeSpecId == null) return
        try { sync.stop() } catch (e: Exception) {
            Log.w(tag, "sync stop failed", e)
        }
        try { delay(FLUSH_GRACE_MS) } catch (_: Exception) {}
        try { ramp.stop() } catch (e: Exception) {
            Log.w(tag, "ramp stop failed", e)
        }
        try { NativePlayerBridge.stop() } catch (e: Exception) {
            Log.w(tag, "native player stop failed", e)
        }
        try { NativePlayerBridge.destroy() } catch (e: Exception) {
            Log.w(tag, "native player destroy failed", e)
        }
        try { dsp.stop() } catch (e: Exception) {
            Log.w(tag, "DSP stop failed", e)
        }
        sync.nativeGainSink = null
        try { vibration.stop() } catch (e: Exception) {
            Log.w(tag, "vibration stop failed", e)
        }
        try { audioFocus.release() } catch (e: Exception) {
            Log.w(tag, "audio focus release failed", e)
        }
        activeSpecId = null
        Log.i(tag, "Audio session stopped")
    }

    /**
     * Stage 1 alarm sound: the bundled tone, played by the native engine.
     * The tone file is generated on first use, on an IO thread. Each alarm
     * starts from a fresh native player, so a previous session cannot leak.
     */
    private suspend fun startTone() {
        // Render at the device's output rate, so the native stream needs no resampling.
        val rate = context.getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: 48_000
        val tone = withContext(Dispatchers.IO) {
            AlarmToneGenerator.ensureTone(File(context.filesDir, "tones"), rate)
        }
        NativePlayerBridge.destroy()
        if (!NativePlayerBridge.create()) {
            Log.w(tag, "native player create failed: ${NativePlayerBridge.getLastError()}")
            return
        }
        if (!NativePlayerBridge.prepare(tone.absolutePath, SourceType.FILE)) {
            Log.w(tag, "tone prepare failed: ${NativePlayerBridge.getLastError()}")
            return
        }
        if (!NativePlayerBridge.play()) {
            Log.w(tag, "tone play failed: ${NativePlayerBridge.getLastError()}")
        }
    }



    private companion object {
        /** Longer than the coalescer's 10 ms flush interval. */
        const val FLUSH_GRACE_MS = 20L
    }
}
