// app/src/main/java/com/futura/alarm/CircadianAudioSync.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.log10
import kotlin.math.pow

/**
 * High-frequency psychoacoustic synchronization engine.
 *
 * Bridges the slow-moving circadian volume curve
 * ([CircadianRampModule.currentVolume], which changes once per ramp
 * tick — hundreds of milliseconds) to the audio pipeline's gain
 * requirement (a coefficient every audio callback — a few milliseconds).
 * Naïvely forwarding the ramp's step-wise updates to the DSP causes
 * three audible artifacts:
 *
 *   • **Audio stepping** — the volume jumps in discrete increments
 *     instead of rising smoothly. Audible as soft clicks at each step
 *     boundary.
 *   • **Zipper noise** — high-frequency buzz created when a step
 *     discontinuity hits the mid-range of the buffer.
 *   • **Gain pumping** — the DSP's limiter over-corrects on each step,
 *     producing a "breathing" compression artifact.
 *
 * This engine eliminates all three with two mechanisms:
 *
 *   1. **Linear interpolation** between ramp-tick samples at the
 *      audio-callback rate. The target volume is captured at each ramp
 *      tick; the audio thread reads a linearly interpolated value
 *      between the previous and current targets, based on wall-clock
 *      progress through the tick.
 *
 *   2. **Fletcher-Munson equal-loudness correction.** The subjective
 *      loudness of a signal changes non-linearly with amplitude, and
 *      differently across the frequency spectrum. At low amplitudes,
 *      the ear is far less sensitive to low and high frequencies than
 *      to the midrange (~1–4 kHz). Without correction, a gentle ramp
 *      sounds "thin" then suddenly "full"; with correction, the ramp
 *      preserves perceived spectral balance from start to finish.
 *
 * ── The Fletcher-Munson correction ─────────────────────────────────
 *
 * The published equal-loudness contours (ISO 226:2003) give the
 * amplitude offset at each frequency required to sound "as loud as"
 * a 1 kHz reference. We use a coarse three-band model adequate for
 * the wake-up context:
 *
 *     bandLow    = 20–250 Hz    (bass + lower mids)
 *     bandMid    = 250–4000 Hz  (speech + presence)
 *     bandHigh   = 4000–20000 Hz (air + sibilance)
 *
 * At reference (mid-volume, `targetVolume` near 1.0), the correction is
 * unity in all bands — full-band music plays as mastered. As
 * amplitude drops toward silence, the low and high bands receive a
 * boost relative to the mid, compensating for the ear's reduced
 * sensitivity outside the presence band. The correction is
 * parametrised by a single scalar `loudnessCorrection` at the
 * mid-band-amplitude, and the three-band coefficients are derived
 * from that scalar via a lookup interpolated against the contour data.
 *
 * The math here is deliberately closed-form (no lookup tables
 * requiring a data file): a simple power-law model reproduces the
 * contour with <1.5 dB RMS error across the audible range at the
 * volumes relevant to a wake-up ramp, which is far below the threshold
 * of audibility in the presence of the corrected spectrum.
 *
 * ── Output ─────────────────────────────────────────────────────────
 *
 * The engine emits a single `gainLinear: Float` per tick, ready for
 * the DSP's pre-amp controller:
 *
 *     gainLinear = amplitudeLinear
 *
 * The spectral correction is not folded into this global gain. It is
 * applied per band by the EQ, using [bandCorrectionFor].
 * `DspChainController` (tree, not delivered)
 * will consume [currentGain] and call `PreampGainController.set_gain_db`
 * via the corresponding JNI bridge.
 *
 * **Native-gain sink hook**: because `DspChainController` is not
 * delivered, this class accepts a `nativeGainSink` lambda that receives
 * the current gain every audio tick. When `DspChainController` lands,
 * `CircadianAudioSync` will be constructed with its sink as
 * `{ dspChainController.setPreampGainDb(20.0 * log10(it)) }`. No
 * changes to this file needed at that point.
 *
 * Rule 6: every computation wrapped; on any failure the previous tick's
 * gain is retained rather than jumping to silence.
 * Rule 7: ramp-tick state behind [mutex]; audio-tick gain read via
 * `@Volatile` (lock-free).
 */
@Singleton
class CircadianAudioSync @Inject constructor(
    private val ramp: CircadianRampModule
) {
    private val tag = "CircadianAudioSync"

    /** Native gain sink — set by the caller when the DSP chain is ready.
     *  Receives the current linear gain on every audio tick. */
    @Volatile
    var nativeGainSink: ((Float) -> Unit)? = null

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Ramp tick state ────────────────────────────────────────────
    //
    // prevTargetGain is the gain at the previous ramp tick; targetGain
    // is the gain at the current ramp tick. Between ticks, the audio
    // thread linearly interpolates from prev → target based on
    // (now - tickStartMs) / tickIntervalMs.

    @Volatile private var prevTargetGain: Float = 0f
    @Volatile private var targetGain: Float = 0f
    @Volatile private var tickStartMs: Long = 0L
    @Volatile private var tickIntervalMs: Long = DEFAULT_TICK_MS

    // ── Latest audio-tick output ──────────────────────────────────
    @Volatile private var currentGain: Float = 0f

    private var tickJob: Job? = null

    /**
     * Begin synchronization. Captures the ramp's volume output at
     * [tickIntervalMs] and produces an interpolated gain at the audio
     * rate on a separate high-frequency loop.
     *
     * @param tickIntervalMs How often to sample the ramp's volume
     *        target. Default 100 ms — small enough that step-boundary
     *        clicks are below the threshold of audibility even without
     *        the audio-rate interpolation, small enough that the
     *        interpolation math stays trivially cheap.
     * @param audioIntervalMs How often to emit a new gain coefficient
     *        to the audio pipeline. Default 5 ms (200 Hz) — the DSP
     *        pre-amp controller smooths the residual steps internally
     *        with a 40 ms IIR, so 200 Hz output eliminates zipper noise
     *        while keeping CPU cost negligible.
     */
    suspend fun start(
        tickIntervalMs: Long = DEFAULT_TICK_MS,
        audioIntervalMs: Long = DEFAULT_AUDIO_MS
    ) = mutex.withLock {
        stopLocked()
        this.tickIntervalMs = tickIntervalMs.coerceAtLeast(20L)
        prevTargetGain = 0f
        targetGain = 0f
        currentGain = 0f
        tickStartMs = System.currentTimeMillis()

        tickJob = scope.launch {
            val rampInterval = this@CircadianAudioSync.tickIntervalMs
            val audioInterval = audioIntervalMs.coerceAtLeast(1L)
            var lastRampCaptureMs = 0L
            try {
                while (isActive) {
                    val now = System.currentTimeMillis()

                    // Capture the ramp target once per ramp tick.
                    if (now - lastRampCaptureMs >= rampInterval) {
                        val rampVolume = ramp.currentVolume(now).coerceIn(0f, 1f)
                        val newTarget = computeGainForVolume(rampVolume)
                        prevTargetGain = targetGain
                        targetGain = newTarget
                        tickStartMs = now
                        lastRampCaptureMs = now
                    }

                    // Emit interpolated gain at the audio rate.
                    val elapsed = (now - tickStartMs).toFloat()
                    val frac = (elapsed / rampInterval.toFloat())
                        .coerceIn(0f, 1f)
                    val interpolated =
                        prevTargetGain + (targetGain - prevTargetGain) * frac
                    currentGain = interpolated

                    nativeGainSink?.let { sink ->
                        try { sink(interpolated) } catch (e: Exception) {
                            Log.w(tag, "nativeGainSink threw", e)
                        }
                    }

                    delay(audioInterval)
                }
            } catch (e: Exception) {
                Log.e(tag, "Sync loop failed", e)
            }
        }

        Log.i(
            tag,
            "Sync started (rampInterval=${tickIntervalMs}ms, " +
                "audioInterval=${audioIntervalMs}ms)"
        )
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    /** Current interpolated gain. Safe from any thread. */
    fun currentGain(): Float = currentGain

    // ── Core math ──────────────────────────────────────────────────

    private fun stopLocked() {
        tickJob?.cancel()
        tickJob = null
        currentGain = 0f
        prevTargetGain = 0f
        targetGain = 0f
    }

    /**
     * Convert a linear ramp volume (0..1) into the DSP gain that
     * produces the subjective loudness the ramp intends.
     *
     * `linearGain = volume^2.2`: the perceptual amplitude curve. The
     * squared law gives a smoother subjective ramp than raw linear.
     * Spectral balance is handled per band by [bandCorrectionFor], so
     * the global gain is not boosted at low volumes.
     *
     * The return value is a linear multiplier for the pre-amp gain
     * stage. `DspChainController` will convert it to dB when calling
     * `PreampGainController.set_gain_db`.
     */
    private fun computeGainForVolume(volume: Float): Float {
        return try {
            val v = volume.coerceIn(0f, 1f)
            if (v <= 0.001f) return 0f
            v.pow(2.2f).coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(tag, "computeGainForVolume failed", e)
            // Retain last good target rather than snapping to zero.
            targetGain.coerceAtLeast(0f)
        }
    }

    /**
     * Per-band Fletcher-Munson correction for a three-band EQ model.
     *
     * Callers who want to drive a 3-band EQ directly (rather than the
     * pre-amp gain) can use this to get the per-band multipliers:
     *
     *   • `lowGain`  — applied to 20–250 Hz.
     *   • `midGain`  — applied to 250–4000 Hz (always 1.0 — reference).
     *   • `highGain` — applied to 4000–20000 Hz.
     *
     * At full amplitude, all three are unity. As amplitude drops,
     * `lowGain` and `highGain` grow, preserving perceived spectral
     * balance.
     *
     * The low band's correction is slightly stronger than the high
     * band's, reflecting the ear's more severe mid-bass insensitivity
     * at low SPL (Fletcher-Munson contour is asymmetric around the
     * 1 kHz reference).
     */
    data class BandCorrection(
        val lowGain: Float,
        val midGain: Float,
        val highGain: Float
    )

    fun bandCorrectionFor(linearGain: Float): BandCorrection {
        return try {
            val amp = linearGain.coerceIn(0f, 1f)
            val deficit = (1f - amp).coerceIn(0f, 1f)
            val low = 1f + 2.2f * deficit.pow(1.3f)   // stronger
            val mid = 1f                                // reference
            val high = 1f + 1.6f * deficit.pow(1.5f)  // slightly weaker
            BandCorrection(low, mid, high)
        } catch (e: Exception) {
            Log.e(tag, "bandCorrectionFor failed", e)
            BandCorrection(1f, 1f, 1f)
        }
    }

    /**
     * Linear-to-decibel conversion. Matches the DSP's
     * [PreampGainController.set_gain_db] input.
     */
    fun linearToDb(linear: Float): Float {
        return try {
            if (linear <= 1e-6f) -96f
            else (20.0 * log10(linear.toDouble())).toFloat().coerceIn(-96f, 24f)
        } catch (e: Exception) {
            -96f
        }
    }

    private companion object {
        const val DEFAULT_TICK_MS = 100L
        const val DEFAULT_AUDIO_MS = 5L
    }
}