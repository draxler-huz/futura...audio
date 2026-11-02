// app/src/main/java/com/futura/alarm/VibrationPatternSynthesizer.kt
package com.futura.alarm

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advanced haptic pattern generator.
 *
 * Two output paths:
 *   • API 26+ — [VibrationEffect] waveforms with amplitude envelopes and
 *     `VibrationEffect.createWaveform(long[], int[], repeatIndex)` for
 *     synchronized tactile feedback with the alarm sound.
 *   • API 21–25 — [VibrationEffect] does not exist; falls back to the
 *     legacy `Vibrator.vibrate(long[], repeat)` with amplitude ignored.
 *
 * The pattern is chosen from the escalation stage, so it gets stronger
 * as the alarm escalates.
 */
@Singleton
class VibrationPatternSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "VibrationPatternSynthesizer"

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to obtain Vibrator", e)
            null
        }
    }

    /** True if the device has a vibrator and it is enabled. */
    fun hasVibrator(): Boolean = vibrator?.hasVibrator() == true

    /**
     * Start a repeating alarm pattern matching [stage] escalation.
     *
     * @return true if the pattern was accepted by the OS.
     */
    fun start(stage: EscalationStage): Boolean {
        val v = vibrator ?: return false
        if (!v.hasVibrator()) return false
        return try {
            val (timings, amplitudes) = buildPattern(stage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (amplitudes != null) {
                    VibrationEffect.createWaveform(timings, amplitudes, REPEAT_FROM_INDEX)
                } else {
                    VibrationEffect.createWaveform(timings, REPEAT_FROM_INDEX)
                }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, REPEAT_FROM_INDEX)
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start vibration", e)
            false
        }
    }

    fun stop() {
        try { vibrator?.cancel() } catch (e: Exception) {
            Log.w(tag, "Failed to cancel vibration", e)
        }
    }

    // ── Internals ──────────────────────────────────────────────────

    /** Pattern timings in ms; amplitudes in [1..255] or null to use the
     *  device default amplitude. */
    private fun buildPattern(stage: EscalationStage): Pair<LongArray, IntArray?> {
        return when (stage) {
            EscalationStage.GENTLE -> Pair(
                longArrayOf(0, 200, 800), null
            )
            EscalationStage.VOICE -> Pair(
                longArrayOf(0, 250, 500, 250, 800), null
            )
            EscalationStage.VOLUME -> Pair(
                longArrayOf(0, 400, 300, 400, 500),
                intArrayOf(0, 128, 0, 128, 0)
            )
            EscalationStage.SCREEN -> Pair(
                longArrayOf(0, 500, 250, 500, 250, 500, 500),
                intArrayOf(0, 192, 0, 192, 0, 192, 0)
            )
            EscalationStage.SIREN -> Pair(
                longArrayOf(0, 300, 200, 300, 200, 300, 200, 300, 500),
                intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0)
            )
        }
    }

    companion object {
        private const val REPEAT_FROM_INDEX = 0
    }
}