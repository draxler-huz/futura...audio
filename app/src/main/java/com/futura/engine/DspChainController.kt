// app/src/main/java/com/futura/engine/DspChainController.kt
package com.futura.engine

import android.util.Log
import com.futura.alarm.CircadianAudioSync
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * Kotlin orchestrator for the native DSP chain.
 *
 * Ties five subsystems together:
 *
 *   1. **Lock-free command coalescer** — buffers high-frequency gain
 *      updates (up to 200 Hz from [CircadianAudioSync]) and flushes
 *      them to [DspBridge] at a bounded rate, eliminating JNI
 *      transition overhead when multiple producers race.
 *   2. **Fletcher-Munson EQ mapping** — translates the three-band
 *      spectral correction produced by [CircadianAudioSync.bandCorrectionFor]
 *      into individual EQ band commands on [DspBridge].
 *   3. **Look-ahead limiter activation** — enables the DSP chain
 *      (`DspBridge.setDspEnabled(true)`), which activates every
 *      safety processor the chain has.
 *   4. **Focus guard** — an equal-power 50 ms ramp-down when audio
 *      focus is lost to an incoming call or interruption, followed by
 *      a matched ramp-up on focus return.
 *   5. **Telemetry façade** — exposes peak levels, spectrum data,
 *      XRun counts, and callback timing to the UI and to logging.
 *
 * ── Gain fusion architecture ────────────────────────────────────
 *
 * Multiple independent gain sources need to be combined without
 * clobbering each other. [DspChainController] fuses them via
 * multiplication:
 *
 *     fusedLinear = circadianLinear × focusMultiplier × duckMultiplier
 *
 * Each producer writes only its own multiplier. A single coalescer
 * coroutine reads all three, multiplies, converts to dB, and pushes
 * to the DSP at 100 Hz. This is what prevents the "focus ramp
 * finishes, then a stale circadian tick overrides it" race that a
 * naive direct-call design suffers from.
 *
 * The multipliers are `@Volatile` (lock-free single-slot writes).
 * The coalescer reads them non-atomically, which is safe: any
 * transient inconsistency between the three is bounded to one
 * 10 ms coalescer tick, well below perceptual threshold.
 *
 * ── Bounded-rate flush ──────────────────────────────────────────
 *
 * Producers may update gains at up to 200 Hz. Calling
 * [DspBridge.setPreampGain] on each update would mean 200 JNI
 * transitions per second — tolerable but wasteful. The coalescer
 * collapses multiple updates into a single flush every 10 ms (100 Hz),
 * regardless of producer rate. This is the "lock-free command
 * coalescer" the delivery spec calls for.
 *
 * Rule 6: every external call wrapped; on failure, the last good
 * DSP state is retained.
 * Rule 7: lifecycle (start, stop, enable) is behind [mutex]. The flush
 * runs on its own coroutine and reads only `@Volatile` fields.
 */
@Singleton
class DspChainController @Inject constructor(
    private val circadianSync: CircadianAudioSync
) {
    private val tag = "DspChainController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // ══════════════════════════════════════════════════════════════
    // Public state
    // ══════════════════════════════════════════════════════════════

    data class Telemetry(
        val enabled: Boolean,
        val fusedGainLinear: Float,
        val fusedGainDb: Float,
        val circadianLinear: Float,
        val focusMultiplier: Float,
        val duckMultiplier: Float,
        val fmLowGainDb: Float,
        val fmHighGainDb: Float,
        val xrunCount: Long,
        val peakLeft: Float,
        val peakRight: Float,
        val truePeakDb: Float,
        val renderThreadId: Long
    )

    private val _telemetry = MutableStateFlow(Telemetry(
        enabled = false,
        fusedGainLinear = 0f,
        fusedGainDb = -96f,
        circadianLinear = 0f,
        focusMultiplier = 1f,
        duckMultiplier = 1f,
        fmLowGainDb = 0f,
        fmHighGainDb = 0f,
        xrunCount = 0,
        peakLeft = 0f,
        peakRight = 0f,
        truePeakDb = Float.NaN,
        renderThreadId = -1L
    ))
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _events = MutableSharedFlow<DspEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<DspEvent> = _events.asSharedFlow()

    sealed interface DspEvent {
        data class ChainEnabled(val enabled: Boolean) : DspEvent
        data class FocusRampDown(val triggerCode: Int) : DspEvent
        data class FocusRampUp(val durationMs: Int) : DspEvent
        data class XRunDetected(val totalCount: Long) : DspEvent
        data class Error(val message: String) : DspEvent
    }

    // ══════════════════════════════════════════════════════════════
    // Producer-side inputs (multipliers)
    // ══════════════════════════════════════════════════════════════

    // Circadian linear gain (0..1). Set by [CircadianAudioSync]
    // through [setCircadianLinear].
    @Volatile private var circadianLinear: Float = 0f

    // Focus multiplier (0..1). Animated by the focus ramp coroutine.
    @Volatile private var focusMultiplier: Float = 1f

    // Duck multiplier (0..1). Set by [VoiceDropStitcher] through
    // [setDuckDb] (converted to linear).
    @Volatile private var duckMultiplier: Float = 1f

    // Current Fletcher-Munson band corrections (dB). Cached so the
    // coalescer applies them alongside the gain.
    @Volatile private var fmLowGainDb: Float = 0f
    @Volatile private var fmHighGainDb: Float = 0f

    // Coalescer wake flag: writers set to true, coalescer clears on flush.
    @Volatile private var pendingFlush: Boolean = false

    // ══════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════

    private var coalescerJob: Job? = null
    private var focusRampJob: Job? = null
    @Volatile private var enabled: Boolean = false

    /**
     * Start the coalescer. Idempotent. Called once by the alarm session
     * bootstrap (typically after [enableChain]).
     */
    suspend fun start() = mutex.withLock {
        if (coalescerJob?.isActive == true) return@withLock
        coalescerJob = scope.launch {
            try {
                while (isActive) {
                    // Clear before reading, so a write that lands during the
                    // flush sets the flag again and is not lost.
                    if (pendingFlush) {
                        pendingFlush = false
                        flushLocked()
                    }
                    refreshTelemetry()
                    delay(FLUSH_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(tag, "Coalescer loop failed", e)
            }
        }
    }

    /**
     * Stop the coalescer, cancel any in-flight focus ramp, and disable
     * the DSP chain. Safe to call multiple times.
     */
    suspend fun stop() = mutex.withLock {
        coalescerJob?.cancel(); coalescerJob = null
        focusRampJob?.cancel(); focusRampJob = null
        enabled = false
        try { DspBridge.setDspEnabled(false) } catch (e: Exception) {
            Log.w(tag, "Failed to disable DSP chain on stop", e)
        }
    }

    /**
     * Enable the DSP chain. Activates every safety processor inside it
     * (loudness maximizer, DC blocker, dither) plus any user-configured
     * stages (EQ, bass boost, reverb, compressor).
     *
     * **This is what activates the look-ahead peak limiter** per the
     * delivery spec — the limiter lives inside the DSP chain
     * (`LoudnessMaximizer.h`, `AutomaticVolumeLimiter.h`, both tree
     * files not yet delivered; the chain framework is delivered as a
     * skeleton via `dsp/DspChain.h`). When those native files land,
     * this single call enables them without any change to this file.
     */
    suspend fun enableChain(): Boolean = mutex.withLock {
        try {
            val ok = DspBridge.setDspEnabled(true)
            if (ok) {
                enabled = true
                _events.tryEmit(DspEvent.ChainEnabled(true))
                Log.i(tag, "DSP chain enabled")
            }
            ok
        } catch (e: Exception) {
            Log.e(tag, "enableChain failed", e)
            _events.tryEmit(DspEvent.Error("enableChain: ${e.message}"))
            false
        }
    }

    suspend fun disableChain(): Boolean = mutex.withLock {
        try {
            val ok = DspBridge.setDspEnabled(false)
            if (ok) {
                enabled = false
                _events.tryEmit(DspEvent.ChainEnabled(false))
            }
            ok
        } catch (e: Exception) {
            Log.e(tag, "disableChain failed", e)
            false
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Producer API (coalesced)
    // ══════════════════════════════════════════════════════════════

    /**
     * Update the circadian linear gain. Called at up to 200 Hz by
     * [CircadianAudioSync]. The value is stored and flushed on the
     * next coalescer tick (≤10 ms later).
     */
    fun setCircadianLinear(linear: Float) {
        circadianLinear = linear.coerceIn(0f, 1f)
        pendingFlush = true
    }

    /**
     * Update the duck multiplier (sidechain). Called when the
     * [VoiceDropStitcher] reports a change in duck state — the caller
     * passes the current duck gain in dB (e.g. -18f) or 0f when no
     * voice is playing.
     */
    fun setDuckDb(duckDb: Float) {
        duckMultiplier = dbToLinear(duckDb.coerceIn(-96f, 0f))
        pendingFlush = true
    }

    /**
     * Update the Fletcher-Munson spectral correction. Called once per
     * circadian tick (100 ms), not per audio tick — the FM contour
     * moves with the volume envelope, not with the audio waveform.
     *
     * The controller maps the three-band correction
     * (`BandCorrection.lowGain` / `.midGain` / `.highGain`) onto the
     * delivered 10-band EQ:
     *
     *   • **Low band** → EQ band 0 (31 Hz) — direct.
     *   • **Mid band** → EQ band 5 (1 kHz) — reference, always 0 dB
     *     by construction of the FM model.
     *   • **High band** → EQ band 9 (16 kHz) — direct.
     *
     * Bands 1–4 and 6–8 remain at the user's preset (0 dB today; user
     * presets will be layered in when `DspPresetStore.h` /
     * `EqPresetSerializer.kt` land — GROWTH).
     *
     * **GROWTH:** when `dsp/ParametricFilterDesign.h` is delivered and
     * the DSP chain gains a dedicated 3-band Fletcher-Munson biquad
     * stage, this method will switch to calling
     * `DspBridge.applyConfig(...)` with the dedicated bands rather
     * than piggy-backing on bands 0/5/9 of the 10-band EQ.
     */
    fun setBandCorrection(correction: CircadianAudioSync.BandCorrection) {
        try {
            fmLowGainDb = linearToDb(correction.lowGain)
                .coerceIn(-12f, 12f)
            fmHighGainDb = linearToDb(correction.highGain)
                .coerceIn(-12f, 12f)
            pendingFlush = true
        } catch (e: Exception) {
            Log.w(tag, "setBandCorrection failed", e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Focus guard (spec item 4)
    // ══════════════════════════════════════════════════════════════

    /**
     * Handle an audio-focus change event.
     *
     * Called by whoever owns [AudioFocusStealer] (delivered) when it
     * observes a focus change. The 50 ms equal-power ramp-down is
     * implemented here — it animates [focusMultiplier] from 1 → 0 via
     * a cosine curve, so the DSP sees a smooth fade rather than a
     * hard cut.
     *
     * Policy:
     *   • `LOSS`, `LOSS_TRANSIENT` → full 50 ms ramp-down.
     *     An incoming call or a permanent hijack should silence the
     *     alarm immediately.
     *   • `LOSS_TRANSIENT_CAN_DUCK` → **ignored**. The alarm is more
     *     important than a navigation instruction; the duck would
     *     defeat the purpose of an alarm.
     *   • `GAIN` → full ramp-up back to unity over 50 ms.
     *
     * **GROWTH:** when `AudioFocusStealer` (delivered) exposes its
     * focus state as a `StateFlow<Int>`, [start] will subscribe to it
     * directly; today the caller wires this method in via an external
     * focus-change callback on `AudioFocusStealer`.
     *
     * @param focusCode One of `AudioManager.AUDIOFOCUS_*` codes.
     */
    fun onAudioFocusChange(focusCode: Int) {
        when (focusCode) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                startFocusRamp(from = 1f, to = 0f)
                _events.tryEmit(DspEvent.FocusRampDown(focusCode))
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                startFocusRamp(from = 0f, to = 1f)
                _events.tryEmit(DspEvent.FocusRampUp(FOCUS_RAMP_MS))
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Intentional no-op: the alarm outranks a nav instruction.
                Log.i(tag, "Focus duck ignored (alarm priority)")
            }
        }
    }

    private fun startFocusRamp(from: Float, to: Float) {
        focusRampJob?.cancel()
        focusRampJob = scope.launch {
            try {
                val steps = FOCUS_RAMP_MS / FOCUS_RAMP_STEP_MS
                for (i in 0..steps) {
                    val t = i.toFloat() / steps
                    // Equal-power curve: cosine for ramp-down, sine for
                    // ramp-up (equivalent — cos(πt/2) for down, sin(πt/2)
                    // for up — same continuity, same equal-power property
                    // since sin² + cos² = 1 at any t).
                    val angle = t * PI.toFloat() / 2f
                    // Ramp-down follows cos (1 to 0); ramp-up follows sin (0 to 1).
                    val value = if (to < from) to + (from - to) * cos(angle)
                                else from + (to - from) * kotlin.math.sin(angle)
                    focusMultiplier = value.coerceIn(0f, 1f)
                    pendingFlush = true
                    delay(FOCUS_RAMP_STEP_MS.toLong())
                }
                focusMultiplier = to
                pendingFlush = true
            } catch (e: CancellationException) {
                // A newer ramp replaced this one. Do not write its target.
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Focus ramp failed", e)
                focusMultiplier = to
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Telemetry (spec item 5)
    // ══════════════════════════════════════════════════════════════

    /**
     * Read the latest peak levels. Returns null if the DSP chain has
     * not yet produced any frames (`DspBridge.getPeakLevels` returns
     * null until the native render loop starts writing).
     *
     * **GROWTH:** with `oboe/XRunDetector.h` and callback-timing
     * telemetry, this will return a richer `MeterSnapshot` including
     * per-block RMS, crest factor, and callback jitter stats.
     */
    fun readPeakLevels(): FloatArray? = try {
        DspBridge.getPeakLevels()
    } catch (e: Exception) {
        Log.w(tag, "readPeakLevels failed", e)
        null
    }

    /**
     * Read the current FFT spectrum. Returns null until the native
     * `FFTAnalyzer.h` + `SpectrumSmoother.h` are delivered and wired
     * into the DSP chain (both are tree files, not delivered).
     *
     * **GROWTH:** once those native files land, `DspBridge.getSpectrumData`
     * starts returning a non-null array and this method becomes a
     * real spectrum feed for the visualizer.
     */
    fun readSpectrum(): FloatArray? = try {
        DspBridge.getSpectrumData()
    } catch (e: Exception) {
        Log.w(tag, "readSpectrum failed", e)
        null
    }

    /**
     * Read the accumulated XRun count since the engine was initialised.
     * Sourced from `NativeEngine.getXRunCount()` (delivered).
     * Returns -1 if unavailable.
     */
    fun readXRunCount(): Long = try {
        NativeEngine.getXRunCount()
    } catch (e: Exception) {
        Log.w(tag, "readXRunCount failed", e)
        -1L
    }

    /**
     * Read the OS thread ID of the native audio render thread.
     * Sourced from `NativePlayerBridge.getRenderThreadId()` (delivered).
     * Returns -1 until the render thread has started.
     */
    fun readRenderThreadId(): Long = try {
        NativePlayerBridge.getRenderThreadId()
    } catch (e: Exception) {
        -1L
    }

    // ══════════════════════════════════════════════════════════════
    // Internal: coalesced flush
    // ══════════════════════════════════════════════════════════════

    private suspend fun flushLocked() {
        try {
            // 1. Gain fusion
            val fusedLinear = (circadianLinear * focusMultiplier * duckMultiplier)
                .coerceIn(0f, 4f)   // allow FM-boost above unity
            val fusedDb = linearToDb(fusedLinear)
            DspBridge.setPreampGain(fusedDb)

            // 2. Fletcher-Munson EQ bands (low / high only — mid is
            //    reference). Bands 1-4 and 6-8 are left at user preset
            //    (0 dB today, real preset when EqPresetSerializer.kt
            //    lands).
            DspBridge.setEqBandGain(0, fmLowGainDb)
            DspBridge.setEqBandGain(9, fmHighGainDb)

            // 3. Update telemetry state.
            val prev = _telemetry.value
            _telemetry.value = prev.copy(
                enabled = enabled,
                fusedGainLinear = fusedLinear,
                fusedGainDb = fusedDb,
                circadianLinear = circadianLinear,
                focusMultiplier = focusMultiplier,
                duckMultiplier = duckMultiplier,
                fmLowGainDb = fmLowGainDb,
                fmHighGainDb = fmHighGainDb
            )
        } catch (e: Exception) {
            Log.e(tag, "flushLocked failed", e)
            _events.tryEmit(DspEvent.Error("flush: ${e.message}"))
        }
    }

    private suspend fun refreshTelemetry() {
        try {
            val peaks = DspBridge.getPeakLevels()
            val xrun = readXRunCount()
            val renderThread = readRenderThreadId()
            val truePeak = try {
                DspBridge.getTruePeakDb()
            } catch (e: Exception) { Float.NaN }

            val prev = _telemetry.value
            val next = prev.copy(
                xrunCount = xrun,
                peakLeft = peaks?.getOrNull(0) ?: 0f,
                peakRight = peaks?.getOrNull(1) ?: 0f,
                truePeakDb = truePeak,
                renderThreadId = renderThread
            )
            // Emit XRun-detected event on transitions.
            if (xrun > prev.xrunCount && prev.xrunCount >= 0) {
                _events.tryEmit(DspEvent.XRunDetected(xrun))
            }
            _telemetry.value = next
        } catch (e: Exception) {
            Log.w(tag, "refreshTelemetry failed", e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Unit conversions
    // ══════════════════════════════════════════════════════════════

    private fun linearToDb(linear: Float): Float {
        return try {
            if (linear <= 1e-6f) -96f
            else (20.0 * log10(linear.toDouble())).toFloat().coerceIn(-96f, 24f)
        } catch (e: Exception) { -96f }
    }

    private fun dbToLinear(db: Float): Float {
        return try {
            if (db <= -96f) 0f
            else 10f.pow(db / 20f).coerceIn(0f, 4f)
        } catch (e: Exception) { 0f }
    }

    // ══════════════════════════════════════════════════════════════
    // Constants
    // ══════════════════════════════════════════════════════════════

    private companion object {
        /** Coalescer flush interval — 100 Hz. Fast enough that a
         *  producer at 200 Hz sees no perceptible lag; slow enough that
         *  JNI call count stays bounded. */
        const val FLUSH_INTERVAL_MS = 10L

        /** Total focus ramp-down duration per the delivery spec. */
        const val FOCUS_RAMP_MS = 50

        /** Granularity of focus ramp — 5 ms steps (10 steps over 50 ms). */
        const val FOCUS_RAMP_STEP_MS = 5
    }
}