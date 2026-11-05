// app/src/main/java/com/futura/data/extraction/ReplayGainHook.kt
package com.futura.data.extraction

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feature 12 — ReplayGain 2.0 loudness analysis (ITU-R BS.1770-4 LUFS).
 *
 * Computes integrated loudness of a decoded audio file and returns a
 * ReplayGain adjustment (dB of gain to apply so the track hits the
 * −18 LUFS target).
 *
 * Algorithm:
 *   1. Decode PCM via MediaCodec, up to [MAX_ANALYSIS_SECONDS].
 *   2. Convert to stereo (mono is duplicated; channels beyond the first two
 *      are ignored — the 5.1 channel weights are not implemented yet).
 *   3. Resample to 48 kHz if needed (linear interpolation).
 *   4. Apply K-weighting: high-shelf pre-filter + high-pass (RLB).
 *   5. Per 100 ms hop, sum the squared K-weighted samples over both
 *      channels. A 400 ms block is the last four hops (75 % overlap).
 *   6. Absolute gate at −70 LKFS, then relative gate at 10 LU below the
 *      ungated loudness. Both gates work on linear mean-square values.
 *   7. Integrated loudness = −0.691 + 10·log10(mean of gated blocks).
 *
 * The implementation is streaming: PCM is processed chunk by chunk, so a
 * full track never lands in RAM as one array. Memory is bounded by the
 * filter states plus one float per 100 ms block.
 *
 * This is the same algorithm `DspBridge.analyzeReplayGain()` will expose
 * once the native DSP chain is wired; today it runs in Kotlin so the
 * extraction pipeline is useful without waiting for the native path.
 *
 * Rule 6: every codec call wrapped; failure returns null.
 */
@Singleton
class ReplayGainHook @Inject constructor() {

    private val tag = "ReplayGainHook"

    /**
     * Analyze [file] and return the ReplayGain result.
     *
     * @return [ReplayGainResult] with measured LUFS and adjustment in dB,
     *         or null on any failure or for silent files (no reliable gain).
     */
    suspend fun analyze(file: File): ReplayGainResult? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) return@withContext null
            val lufs = computeLufs(file) ?: return@withContext null
            // Silence measures −∞ LUFS; a gain from that would boost it by the clamp.
            if (!lufs.isFinite()) return@withContext null
            ReplayGainResult(
                integratedLufs = lufs,
                // ReplayGain 2.0 reference: −18 LUFS.
                adjustmentDb = (TARGET_LUFS - lufs).coerceIn(-24f, 24f)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "analyze failed for ${file.name}", e)
            null
        }
    }

    // ── Core LUFS measurement ─────────────────────────────────────

    private fun computeLufs(file: File): Float? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = fmt; break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // The decoder can change rate, channels or sample encoding after
            // start; INFO_OUTPUT_FORMAT_CHANGED below updates these.
            var sourceRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            var channels = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var floatOutput = false

            // K-weighting: one shelf and one high-pass per stereo channel.
            val leftShelf = BiquadStage(SHELF_B, SHELF_A)
            val rightShelf = BiquadStage(SHELF_B, SHELF_A)
            val leftHP = BiquadStage(HIGHPASS_B, HIGHPASS_A)
            val rightHP = BiquadStage(HIGHPASS_B, HIGHPASS_A)

            val hopFrames = TARGET_RATE * HOP_MS / 1000        // 4800 @ 48 kHz = 100 ms
            val hopsPerBlock = BLOCK_MS / HOP_MS               // 4
            val hopSums = DoubleArray(hopsPerBlock)            // last four hops of (L²+R²)
            var currentHopFrames = 0
            var currentHopSum = 0.0
            var hopCount = 0
            val blockMeanSquares = ArrayList<Float>()
            var totalFrames = 0L
            val maxFrames = MAX_ANALYSIS_SECONDS * TARGET_RATE

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            val pcm = readPcmFloats(outBuf, info, floatOutput)
                            val stereo = toStereo(pcm, channels)
                            val frames = if (sourceRate != TARGET_RATE) {
                                resampleStereo(stereo, sourceRate, TARGET_RATE)
                            } else stereo

                            val frameCount = frames.size / 2
                            var f = 0
                            while (f < frameCount && totalFrames < maxFrames) {
                                val l = leftHP.process(leftShelf.process(frames[f * 2]))
                                val r = rightHP.process(rightShelf.process(frames[f * 2 + 1]))
                                currentHopSum += (l * l + r * r).toDouble()
                                currentHopFrames++
                                totalFrames++

                                if (currentHopFrames == hopFrames) {
                                    hopSums[hopCount % hopsPerBlock] = currentHopSum
                                    hopCount++
                                    currentHopSum = 0.0
                                    currentHopFrames = 0
                                    if (hopCount >= hopsPerBlock) {
                                        // Sum over channels, mean over the frames in the block.
                                        val blockSum = hopSums.sum()
                                        blockMeanSquares.add(
                                            (blockSum / (hopsPerBlock * hopFrames)).toFloat()
                                        )
                                    }
                                }
                                f++
                            }
                            // Partial-track analysis is allowed by the ReplayGain spec.
                            if (totalFrames >= maxFrames) outputDone = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sourceRate = out.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sourceRate
                        channels = out.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        floatOutput = out.getIntOrNull(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }

            return integratedLoudness(blockMeanSquares)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * BS.1770-4 gating on linear mean-square values: absolute gate at
     * −70 LKFS, then relative gate 10 LU below the ungated loudness.
     * Returns integrated loudness in LUFS, or −∞ when every block is gated out.
     */
    private fun integratedLoudness(blockMeanSquares: List<Float>): Float? {
        if (blockMeanSquares.isEmpty()) return null
        val absoluteThreshold = 10.0.pow((ABSOLUTE_GATE_LKFS + 0.691) / 10.0)
        val aboveAbsolute = blockMeanSquares.filter { it > absoluteThreshold }
        if (aboveAbsolute.isEmpty()) return Float.NEGATIVE_INFINITY

        // −10 LU in linear power is a factor of 0.1.
        val relativeThreshold = aboveAbsolute.average() * 0.1
        val aboveRelative = aboveAbsolute.filter { it > relativeThreshold }
        val gatedMean = (if (aboveRelative.isEmpty()) aboveAbsolute else aboveRelative).average()
        return (-0.691 + 10.0 * log10(gatedMean)).toFloat()
    }

    // ── PCM helpers ───────────────────────────────────────────────

    private fun readPcmFloats(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        floatOutput: Boolean
    ): FloatArray {
        val bb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        bb.position(info.offset)
        bb.limit(info.offset + info.size)
        return if (floatOutput) {
            FloatArray(info.size / 4) { bb.float }
        } else {
            FloatArray(info.size / 2) { bb.short / 32768f }
        }
    }

    /**
     * Interleaved PCM with any channel count → interleaved stereo.
     * Mono is duplicated to both channels; for more than two channels the
     * first two (front left and right) are kept.
     */
    private fun toStereo(pcm: FloatArray, channels: Int): FloatArray = when {
        channels == 2 -> pcm
        channels == 1 -> FloatArray(pcm.size * 2) { i -> pcm[i / 2] }
        channels > 2 -> {
            val frames = pcm.size / channels
            FloatArray(frames * 2) { i -> pcm[(i / 2) * channels + (i % 2)] }
        }
        else -> FloatArray(0)
    }

    /** Simple linear resampler for stereo interleaved float PCM. */
    private fun resampleStereo(
        src: FloatArray,
        fromRate: Int,
        toRate: Int
    ): FloatArray {
        val srcFrames = src.size / 2
        if (srcFrames == 0) return src
        val dstFrames = (srcFrames.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(dstFrames * 2)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (f in 0 until dstFrames) {
            val pos = f * ratio
            val i0 = pos.toInt().coerceIn(0, srcFrames - 1)
            val i1 = (i0 + 1).coerceIn(0, srcFrames - 1)
            val frac = (pos - i0).toFloat()
            out[f * 2]     = src[i0 * 2]     + (src[i1 * 2]     - src[i0 * 2])     * frac
            out[f * 2 + 1] = src[i0 * 2 + 1] + (src[i1 * 2 + 1] - src[i0 * 2 + 1]) * frac
        }
        return out
    }

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        try { if (containsKey(key)) getInteger(key) else null } catch (_: Exception) { null }

    // ── Biquad filter state ───────────────────────────────────────

    /**
     * Transposed Direct Form II biquad. One per channel per stage.
     * Coefficients pre-normalised to a0 = 1.
     */
    private class BiquadStage(b: FloatArray, a: FloatArray) {
        private val b0 = b[0]; private val b1 = b[1]; private val b2 = b[2]
        private val a1 = a[0]; private val a2 = a[1]
        private var z1 = 0f
        private var z2 = 0f
        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    // ── Result type ───────────────────────────────────────────────

    data class ReplayGainResult(
        /** Integrated loudness in LUFS. Typically −30 to −5. */
        val integratedLufs: Float,
        /** dB of gain to apply to reach −18 LUFS. Negative = attenuate. */
        val adjustmentDb: Float
    )

    private companion object {
        const val TARGET_RATE = 48_000
        const val BLOCK_MS = 400
        const val HOP_MS = 100
        const val TARGET_LUFS = -18f
        const val ABSOLUTE_GATE_LKFS = -70.0
        const val MAX_ANALYSIS_SECONDS = 600L  // 10 minutes

        // ITU-R BS.1770-4 K-weighting coefficients at 48 kHz.
        // Stage 1 — high-shelf pre-filter.
        private val SHELF_B = floatArrayOf(
            1.53512485958697f, -2.69169618940638f, 1.19839281085285f
        )
        private val SHELF_A = floatArrayOf(
            -1.69065929318241f, 0.73248077421585f
        )
        // Stage 2 — high-pass (RLB).
        private val HIGHPASS_B = floatArrayOf(1.0f, -2.0f, 1.0f)
        private val HIGHPASS_A = floatArrayOf(
            -1.99004745483398f, 0.99007225036621f
        )
    }
}
