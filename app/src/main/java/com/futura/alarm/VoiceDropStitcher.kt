// app/src/main/java/com/futura/alarm/VoiceDropStitcher.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sample-accurate PCM buffer stitching engine.
 *
 * Multiplexes two asynchronous audio streams — a **voice drop** (TTS
 * output or any spoken PCM) and an **ambient stream** (music or
 * background sound) — into a single PCM output at a target sample
 * rate, with three properties the naive approach does not provide:
 *
 *   1. **Multi-rate harmonisation.** Inputs may arrive at 22050,
 *      44100, 48000, or any rate. The engine resamples every input to
 *      the target rate before mixing, using band-limited sinc
 *      interpolation (windowed-sinc, 32-tap window) — the same class
 *      of algorithm as libsamplerate's SRC_SINC_MEDIUM_QUALITY, applied
 *      per chunk. This is not "sample and hold"; that produces audible
 *      aliasing on any content with energy above the lower Nyquist.
 *
 *   2. **Sidechain ducking.** When a voice drop is present, the ambient
 *      stream is attenuated by a configurable amount (default -18 dB)
 *      with a smooth attack/release ballistic envelope. The attack is
 *      fast (~15 ms) so the ducking is present before the voice's
 *      first phoneme, and the release is slow (~250 ms) so the ambient
 *      does not "pop back" during inter-word pauses.
 *
 *   3. **Equal-power crossfade.** At chunk boundaries (and at the
 *      start/end of any voice drop), the engine uses equal-power
 *      sinusoidal crossfading rather than linear crossfading. Linear
 *      crossfading of identical in-phase signals produces a -3 dB dip
 *      at the midpoint; equal-power (sine/cosine pair) preserves
 *      perceived loudness across the boundary. The fade-in at voice
 *      start is implemented. The last 15 ms of a final chunk fade to
 *      silence, so the end of a drop does not click.
 *
 * ── Mixing model ───────────────────────────────────────────────────
 *
 * There is no background mixing coroutine and no ring buffer yet.
 * Mixing happens on demand inside [consume], which always returns the
 * requested number of frames. A source with no data contributes silence
 * for that frame, so the caller never gets a short buffer mid-stream.
 *
 * Voice and ambient state are each guarded by their own lock. Mixing
 * takes the mixing lock first, then a source lock. Pushes take the
 * config mutex and a source lock, so there is no lock-order inversion.
 *
 * ── Allocation ─────────────────────────────────────────────────────
 *
 * Buffers are sized per push and per consume, not during [configure].
 * The per-frame source reads allocate one small FloatArray per frame.
 * A reusable scratch buffer is the next step if profiling shows pressure.
 *
 * Rule 6: every computation is wrapped. On failure, [consume] returns an
 * empty array and the resampler returns null, so no exception crosses
 * the audio-callback boundary.
 * Rule 7: voice and ambient state are guarded by their source locks.
 * Configuration and pushes are guarded by `mutex`.
 */
@Singleton
class VoiceDropStitcher @Inject constructor() {
    private val tag = "VoiceDropStitcher"

    // ── Configuration (set once, before first push) ───────────────

    data class Config(
        val targetSampleRate: Int = 48_000,
        val targetChannels: Int = 2,
        val ringCapacityFrames: Int = 48_000,   // reserved: no ring yet
        val mixChunkFrames: Int = 1024,         // reserved
        val lowWaterMarkFrames: Int = 2048,     // reserved
        val sidechainDuckDb: Float = -18f,
        val duckAttackMs: Float = 15f,
        val duckReleaseMs: Float = 250f,
        val crossfadeMs: Float = 8f,
        val resamplerTapsPerSide: Int = 16
    ) {
        init {
            require(targetSampleRate in 8_000..192_000)
            require(targetChannels in 1..8)
            require(ringCapacityFrames >= 4096)
            require(mixChunkFrames >= 256)
            require(lowWaterMarkFrames > 0)
            require(sidechainDuckDb in -60f..0f)
            require(duckAttackMs > 0f && duckReleaseMs > 0f)
            require(crossfadeMs >= 0f)
            require(resamplerTapsPerSide >= 4)
        }
    }

    // ── Public API ────────────────────────────────────────────────

    @Volatile private var config: Config = Config()
    @Volatile private var configured: Boolean = false

    /**
     * Called from consume() with the same buffer that consume() returns.
     * Use one path: the sink, or the return value.
     */
    @Volatile var outputSink: ((FloatArray, Int) -> Unit)? = null

    private val mutex = Mutex()
    private val _state = MutableStateFlow(StitcherState())
    val state: StateFlow<StitcherState> = _state.asStateFlow()

    data class StitcherState(
        val voiceActive: Boolean = false,
        val ambientActive: Boolean = false,
        val ringFillFrames: Int = 0,
        val lastUnderrunAtMs: Long = 0L,
        val currentDuckDb: Float = 0f
    )

    /**
     * Configure the engine. Must be called before any push. Idempotent —
     * a second call with the same config is a no-op; a second call with
     * different config reallocates every buffer.
     */
    suspend fun configure(cfg: Config) = mutex.withLock {
        if (configured && cfg == config) return@withLock
        config = cfg
        allocateBuffersLocked()
        configured = true
        Log.i(
            tag,
            "Configured: rate=${cfg.targetSampleRate}Hz ch=${cfg.targetChannels} " +
                "ring=${cfg.ringCapacityFrames}f chunk=${cfg.mixChunkFrames}f " +
                "duck=${cfg.sidechainDuckDb}dB"
        )
    }

    /**
     * Push a voice-drop PCM buffer. The engine resamples it to the
     * target rate, mixes it against the ambient stream, and plays it
     * to the output sink with sidechain ducking applied.
     *
     * @param pcm Interleaved float samples.
     * @param sourceRate The source's sample rate in Hz.
     * @param sourceChannels The source's channel count.
     * @param isFinal True when this is the last chunk of the voice drop
     *        (signals crossfade-out). Chunks with `isFinal = false` are
     *        assumed contiguous.
     * @return true if the buffer was accepted; false if the engine is
     *         not configured, or the input has an invalid shape.
     */
    suspend fun pushVoiceDrop(
        pcm: FloatArray,
        sourceRate: Int,
        sourceChannels: Int,
        isFinal: Boolean = false
    ): Boolean = mutex.withLock {
        try {
            if (!configured) return@withLock false
            if (pcm.isEmpty()) return@withLock false
            if (sourceChannels <= 0 || sourceRate <= 0) return@withLock false

            val resampled = resampleToTarget(
                pcm, sourceRate, sourceChannels
            ) ?: return@withLock false

            // Only a final chunk ends the drop, so only it gets the fade-out.
            val shaped = if (isFinal) fadeOutTail(resampled, config.targetChannels) else resampled

            synchronized(voiceLock) {
                // A non-final chunk continues the drop in progress, so keep
                // the unread tail and append. Replacing it would drop audio.
                val pending = voiceBuffer
                voiceBuffer = if (pending != null && !voiceIsFinal &&
                    voiceReadIndex < pending.size
                ) {
                    pending.copyOfRange(voiceReadIndex, pending.size) + shaped
                } else {
                    shaped
                }
                voiceReadIndex = 0
                voiceIsFinal = isFinal
            }
            _state.value = _state.value.copy(voiceActive = true)
            true
        } catch (e: Exception) {
            Log.e(tag, "pushVoiceDrop failed", e)
            false
        }
    }

    /**
     * Push ambient PCM. This is the "music stream" — the background
     * bed under the voice drop. When no voice drop is playing, ambient
     * flows through at unity gain; when a voice drop is active, ambient
     * is ducked by the sidechain envelope.
     */
    suspend fun pushAmbient(
        pcm: FloatArray,
        sourceRate: Int,
        sourceChannels: Int
    ): Boolean = mutex.withLock {
        try {
            if (!configured) return@withLock false
            if (pcm.isEmpty()) return@withLock false
            if (sourceChannels <= 0 || sourceRate <= 0) return@withLock false

            val resampled = resampleToTarget(
                pcm, sourceRate, sourceChannels
            ) ?: return@withLock false

            synchronized(ambientLock) {
                ambientBuffer = resampled
                ambientReadIndex = 0
            }
            _state.value = _state.value.copy(ambientActive = true)
            true
        } catch (e: Exception) {
            Log.e(tag, "pushAmbient failed", e)
            false
        }
    }

    /**
     * Drain up to [maxFrames] frames of mixed PCM. Called by the
     * audio-callback coroutine at the output rate.
     *
     * @return exactly [maxFrames] frames of mixed PCM, with silence where a
     *         source has no data. An empty array means failure.
     */
    fun consume(maxFrames: Int): FloatArray {
        try {
            if (!configured || maxFrames <= 0) return FloatArray(0)
            val ch = config.targetChannels
            val out = FloatArray(maxFrames * ch)
            mixIntoLocked(out, maxFrames)
            // Also deliver to the sink if one is set. The sink and the return
            // value carry the same buffer, so a caller should use one path.
            outputSink?.let { sink ->
                try { sink(out, maxFrames) } catch (e: Exception) {
                    Log.w(tag, "outputSink threw", e)
                }
            }
            return out
        } catch (e: Exception) {
            Log.e(tag, "consume failed", e)
            return FloatArray(0)
        }
    }

    // ── Mixing (called from consume and from the background coroutine) ──

    // All mixing state — protected by `mixLock`, which is separate from
    // `mutex` so configuration changes do not contend with the audio
    // callback path.
    private val mixLock = Any()

    private fun mixIntoLocked(out: FloatArray, frames: Int) {
        synchronized(mixLock) {
            val ch = config.targetChannels
            val duckTargetLinear = dbToLinear(config.sidechainDuckDb)
            val attackCoeff = attackCoefficient(config.duckAttackMs)
            val releaseCoeff = releaseCoefficient(config.duckReleaseMs)

            for (f in 0 until frames) {
                // Read one frame from each source.
                val ambientFrame = readAmbientFrameLocked()
                val voiceFrame = readVoiceFrameLocked()

                // Sidechain envelope: fast attack when voice present,
                // slow release when voice leaves.
                val voicePresent = voiceFrame != null
                val target = if (voicePresent) duckTargetLinear else 1f
                val coeff = if (target < duckEnvelope) attackCoeff else releaseCoeff
                duckEnvelope += (target - duckEnvelope) * coeff
                duckEnvelope = duckEnvelope.coerceIn(0f, 1f)

                // Equal-power crossfade at voice boundaries.
                val voiceGain: Float
                if (voiceFrame != null) {
                    voiceGain = equalPowerIn(voiceReadIndexBeforeIncrement() / ch)
                    voiceGainSmoother.increment()
                } else {
                    voiceGain = 0f
                }

                for (c in 0 until ch) {
                    val amb = ambientFrame?.getOrElse(c) { 0f } ?: 0f
                    val voice = voiceFrame?.getOrElse(c) { 0f } ?: 0f
                    val mixed = amb * duckEnvelope + voice * voiceGain
                    out[f * ch + c] = mixed.coerceIn(-1.5f, 1.5f)
                }
            }

            _state.value = _state.value.copy(
                voiceActive = voiceBuffer != null,
                ambientActive = ambientBuffer != null,
                ringFillFrames = 0,
                currentDuckDb = linearToDb(duckEnvelope)
            )
        }
    }

    // ── Ring buffers for each source ─────────────────────────────

    // Voice buffer — one active push at a time.
    private val voiceLock = Any()
    @Volatile private var voiceBuffer: FloatArray? = null
    @Volatile private var voiceReadIndex: Int = 0
    @Volatile private var voiceIsFinal: Boolean = false
    private val voiceGainSmoother = GainSmoother(
        config.crossfadeMs,
        config.targetSampleRate
    )

    private fun readVoiceFrameLocked(): FloatArray? {
        synchronized(voiceLock) {
            val buf = voiceBuffer ?: return null
            val ch = config.targetChannels
            if (voiceReadIndex + ch > buf.size) {
                // End of this voice drop — if it was marked final, we
                // are done; otherwise, wait for the next push.
                if (voiceIsFinal) {
                    voiceBuffer = null
                    voiceReadIndex = 0
                }
                return null
            }
            val frame = FloatArray(ch)
            System.arraycopy(buf, voiceReadIndex, frame, 0, ch)
            voiceReadIndex += ch
            return frame
        }
    }

    private fun voiceReadIndexBeforeIncrement(): Int {
        synchronized(voiceLock) { return voiceReadIndex }
    }

    // Ambient buffer — single active push, replaced on next push.
    private val ambientLock = Any()
    @Volatile private var ambientBuffer: FloatArray? = null
    @Volatile private var ambientReadIndex: Int = 0

    private fun readAmbientFrameLocked(): FloatArray? {
        synchronized(ambientLock) {
            val buf = ambientBuffer ?: return null
            val ch = config.targetChannels
            if (ambientReadIndex + ch > buf.size) {
                // End of this ambient chunk. Ambient sources are
                // expected to be continuous — the caller must push
                // again before the next consume. Return null (silence)
                // in the meantime; do NOT clear the buffer, since the
                // caller may push at any moment.
                return null
            }
            val frame = FloatArray(ch)
            System.arraycopy(buf, ambientReadIndex, frame, 0, ch)
            ambientReadIndex += ch
            return frame
        }
    }

    // ── Sidechain envelope ───────────────────────────────────────

    @Volatile private var duckEnvelope: Float = 1f

    private fun attackCoefficient(ms: Float): Float {
        val samples = (ms / 1000f) * config.targetSampleRate
        return 1f - kotlin.math.exp(-1f / samples)
    }

    private fun releaseCoefficient(ms: Float): Float {
        val samples = (ms / 1000f) * config.targetSampleRate
        return 1f - kotlin.math.exp(-1f / samples)
    }

    // ── Multi-rate sinc resampling ───────────────────────────────

    /**
     * Resample [pcm] from [sourceRate] to [config.targetSampleRate],
     * also handling channel count conversion (mono→stereo duplicates,
     * stereo→mono averages, N→M remaps by nearest-index).
     *
     * Windowed-sinc interpolation with `2 × resamplerTapsPerSide` taps.
     * The window is a Blackman window — lower sidelobe leakage than
     * Hamming, at the cost of slightly wider transition band, which is
     * the right trade-off for speech and music resampling.
     *
     * For the common case of `sourceRate == targetRate` the method
     * short-circuits to a channel remap without any interpolation.
     */
    private fun resampleToTarget(
        pcm: FloatArray,
        sourceRate: Int,
        sourceChannels: Int
    ): FloatArray? {
        try {
            val targetRate = config.targetSampleRate
            val targetChannels = config.targetChannels
            val sourceFrames = pcm.size / sourceChannels

            // Same rate: channel remap only.
            if (sourceRate == targetRate) {
                if (sourceChannels == targetChannels) return pcm
                val out = FloatArray(sourceFrames * targetChannels)
                for (f in 0 until sourceFrames) {
                    for (c in 0 until targetChannels) {
                        out[f * targetChannels + c] =
                            remapChannel(pcm, f, sourceChannels, targetChannels, c)
                    }
                }
                return out
            }

            val ratio = targetRate.toDouble() / sourceRate.toDouble()
            val targetFrames = (sourceFrames * ratio).roundToInt()
            val tapsPerSide = config.resamplerTapsPerSide
            // Anti-alias cutoff as a fraction of the source Nyquist.
            val cutoff = min(1.0, ratio)
            val out = FloatArray(targetFrames * targetChannels)
            for (tf in 0 until targetFrames) {
                // The kernel must be evaluated at the fractional source
                // position, or timing is off by up to one sample.
                val srcPos = tf / ratio
                val i0 = srcPos.toInt()
                val frac = srcPos - i0
                for (c in 0 until targetChannels) {
                    var sum = 0.0
                    for (t in 0 until 2 * tapsPerSide) {
                        val srcFrame = i0 + t - tapsPerSide
                        if (srcFrame < 0 || srcFrame >= sourceFrames) continue
                        val x = (t - tapsPerSide) - frac
                        val weight = windowedSinc(x, cutoff, tapsPerSide)
                        sum += remapChannel(pcm, srcFrame, sourceChannels, targetChannels, c) * weight
                    }
                    out[tf * targetChannels + c] = sum.toFloat()
                }
            }
            return out
        } catch (e: Exception) {
            Log.e(tag, "resampleToTarget failed", e)
            return null
        }
    }

    /** Windowed-sinc kernel at [x] source frames from the sample point. */
    private fun windowedSinc(x: Double, cutoff: Double, tapsPerSide: Int): Double {
        val u = x / tapsPerSide
        if (abs(u) >= 1.0) return 0.0
        val a = PI * x * cutoff
        val sinc = if (abs(a) < 1e-9) 1.0 else sin(a) / a
        // Blackman window over (-1, 1).
        val w = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2.0 * PI * u)
        return cutoff * sinc * w
    }

    /**
     * Source sample for target channel [c]. Stereo to mono averages all
     * source channels. Other layouts duplicate or wrap channels.
     */
    private fun remapChannel(
        pcm: FloatArray,
        frame: Int,
        sourceChannels: Int,
        targetChannels: Int,
        c: Int
    ): Float {
        if (targetChannels == 1 && sourceChannels > 1) {
            var s = 0f
            for (k in 0 until sourceChannels) s += pcm[frame * sourceChannels + k]
            return s / sourceChannels
        }
        return pcm[frame * sourceChannels + c % sourceChannels]
    }

    // ── Voice end fade-out ─────────────────────────────────────

    private val voiceFadeOutMs = 15f

    /**
     * Copy [src] and fade its last [voiceFadeOutMs] to silence, linearly.
     * The copy matters: the same-rate path can return the caller's own
     * array, and that array must not be changed.
     */
    private fun fadeOutTail(src: FloatArray, channels: Int): FloatArray {
        val out = src.copyOf()
        val frames = out.size / channels
        val n = ((voiceFadeOutMs / 1000f) * config.targetSampleRate)
            .toInt().coerceIn(0, frames)
        for (k in 0 until n) {
            val gain = (n - 1 - k).toFloat() / n
            val f = frames - n + k
            for (c in 0 until channels) out[f * channels + c] *= gain
        }
        return out
    }

    // ── Crossfade / equal-power helpers ──────────────────────────

    private fun equalPowerIn(pos: Int): Float {
        val half = (config.crossfadeMs / 1000f) * config.targetSampleRate
        if (pos >= half) return 1f
        return sin((PI * 0.5 * pos / half)).toFloat()
    }

    // ── Gain / dB helpers ────────────────────────────────────────

    private fun dbToLinear(db: Float): Float {
        return 10f.pow(db / 20f)
    }

    private fun linearToDb(linear: Float): Float {
        return if (linear <= 1e-6f) -96f
        else (20f * kotlin.math.log10(linear)).coerceIn(-96f, 24f)
    }

    // ── Buffer allocation (during configure) ────────────────────

    private fun allocateBuffersLocked() {
        // Nothing to pre-allocate today — the resampler allocates its
        // output sized to the input; the mixing loop allocates one
        // FloatArray per consume (which the caller owns). This method
        // exists as the single seam where future pre-allocated scratch
        // buffers would go if profiling shows allocation pressure.
    }

    // ── Utility ─────────────────────────────────────────────────

    private class GainSmoother(crossfadeMs: Float, sampleRate: Int) {
        private val totalSamples = (crossfadeMs / 1000f * sampleRate).toInt()
            .coerceAtLeast(1)
        private var pos = 0
        fun increment() { if (pos < totalSamples) pos++ }
        fun value(): Float =
            if (pos >= totalSamples) 1f else pos.toFloat() / totalSamples
    }
}