// app/src/main/java/com/futura/data/extraction/AudioTelemetryHelper.kt
package com.futura.data.extraction

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes per-track audio telemetry from the encoded file:
 *
 *   • Feature 7  — BPM (autocorrelation on a low-passed energy envelope)
 *   • Feature 8  — waveform preview (peak per bucket across the WHOLE track)
 *   • Feature 9  — leading / trailing silence durations (whole track)
 *   • Feature 10 — CBR vs. VBR (declared bitrate vs. computed actual rate)
 *
 * The analysis is *fast and approximate*, not studio-grade:
 *   • BPM uses the first [MAX_BPM_SECONDS] of audio, a 60–180 BPM band and
 *     ±2 BPM resolution. Adequate for the "sort by energy" use case; not
 *     adequate for pro DJ beat-matching (native `analysis/BpmCounter.h`).
 *   • Waveform is 200 peak samples, sufficient for a scrubber preview.
 *   • Silence is measured against −45 dBFS; runs shorter than 500 ms are
 *     ignored as noise. Leading silence is measured over the first 30 s.
 *   • VBR detection compares declared vs. computed bitrate — a heuristic.
 *
 * The PCM is streamed: nothing holds the whole decoded track in memory.
 *
 * Rule 6: every extraction call wrapped; on failure returns a partial
 * result or null, never throws.
 * This class is stateless per call; no mutex needed.
 */
@Singleton
class AudioTelemetryHelper @Inject constructor() {

    private val tag = "AudioTelemetryHelper"

    /**
     * Compute telemetry for [file].
     *
     * @param declaredBitrateKbps the bitrate MediaMetadataRetriever
     *        already extracted (from the container header). Pass 0 if
     *        unknown.
     * @param durationMs the track duration. Required for VBR heuristics
     *        and for scaling the waveform.
     */
    suspend fun compute(
        file: File,
        declaredBitrateKbps: Int,
        durationMs: Long
    ): TelemetryResult? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) return@withContext null
            if (durationMs <= 0L) return@withContext null

            val extractor = MediaExtractor()
            var sampleRate = 0
            var channelCount = 0
            var encodedBytes = 0
            try {
                extractor.setDataSource(file.absolutePath)
                val (index, format) = findAudioTrack(extractor) ?: return@withContext null
                extractor.selectTrack(index)
                sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
                channelCount = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
                encodedBytes = readEncodedBytes(extractor, MAX_ENCODED_BYTES)
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }

            // VBR heuristic from size/duration vs. declared bitrate.
            val actualKbps = ((file.length() * 8L) / (durationMs / 1000L).coerceAtLeast(1L))
                .toInt() / 1000
            val isVbr = when {
                declaredBitrateKbps <= 0 || actualKbps <= 0 -> false
                abs(actualKbps - declaredBitrateKbps) > declaredBitrateKbps * 0.15 -> true
                else -> false
            }

            // Waveform, silence and BPM need decoded PCM. If decode fails we
            // still return the VBR flag and basic info.
            val analysis = decodeAnalysis(file, durationMs)
                ?: return@withContext TelemetryResult(
                    sampleRateHz = sampleRate,
                    channelCount = channelCount,
                    isVbr = isVbr,
                    bpm = null,
                    waveformPreview = null,
                    silentLeadingMs = null,
                    silentTrailingMs = null,
                    encodedBytesSampled = encodedBytes
                )

            TelemetryResult(
                sampleRateHz = sampleRate,
                channelCount = channelCount,
                isVbr = isVbr,
                bpm = detectBpm(analysis.bpmEnvelope),
                waveformPreview = analysis.waveform,
                silentLeadingMs = analysis.leadingSilentMs,
                silentTrailingMs = analysis.trailingSilentMs,
                encodedBytesSampled = encodedBytes
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "compute failed for ${file.name}", e)
            null
        }
    }

    // ── Streaming decode ──────────────────────────────────────────

    private data class Analysis(
        val sampleRate: Int,
        val channels: Int,
        /** 20 ms RMS envelope of the first [MAX_BPM_SECONDS] of audio. */
        val bpmEnvelope: FloatArray,
        val waveform: FloatArray?,
        val leadingSilentMs: Long,
        val trailingSilentMs: Long
    )

    /**
     * Decodes the whole track once, in chunks, and collects everything the
     * UI needs without keeping the PCM: waveform peaks, leading and trailing
     * silence, and the first slice of audio for BPM.
     */
    private fun decodeAnalysis(file: File, durationMs: Long): Analysis? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val (index, format) = findAudioTrack(extractor) ?: return null
            extractor.selectTrack(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            var sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            var channels = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var floatOutput = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val totalFrames = durationMs * sampleRate / 1000L
            val peaks = FloatArray(WAVEFORM_BUCKETS)
            // BPM envelope: one RMS value per 20 ms of the first MAX_BPM_SECONDS.
            val hopInterleaved = (sampleRate * channels * ENVELOPE_HOP_SECONDS).toInt().coerceAtLeast(1)
            val envelope = FloatArray(MAX_BPM_SECONDS * ENVELOPE_HOPS_PER_SECOND)
            var envelopeCount = 0
            var lowPass = 0f
            var hopSumSq = 0f
            var hopFill = 0
            var frameIndex = 0L
            val silenceWindowFrames = SILENCE_WINDOW_SECONDS.toLong() * sampleRate
            var leadingFrames = 0L
            var leadingEnded = false
            var trailingRunFrames = 0L

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var stalls = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx >= 0 -> {
                        stalls = 0
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0 && channels > 0) {
                            val pcm = readPcmFloats(outBuf, info, floatOutput)
                            val frames = pcm.size / channels
                            for (f in 0 until frames) {
                                var peak = 0f
                                val base = f * channels
                                for (c in 0 until channels) {
                                    val a = abs(pcm[base + c])
                                    if (a > peak) peak = a
                                }
                                for (c in 0 until channels) {
                                    lowPass += ENVELOPE_ALPHA * (pcm[base + c] - lowPass)
                                    if (envelopeCount < envelope.size) {
                                        hopSumSq += lowPass * lowPass
                                        hopFill++
                                        if (hopFill == hopInterleaved) {
                                            envelope[envelopeCount++] = sqrt(hopSumSq / hopInterleaved)
                                            hopSumSq = 0f
                                            hopFill = 0
                                        }
                                    }
                                }
                                if (totalFrames > 0L) {
                                    val bucket = minOf(
                                        WAVEFORM_BUCKETS - 1L,
                                        frameIndex * WAVEFORM_BUCKETS / totalFrames
                                    ).toInt()
                                    if (peak > peaks[bucket]) peaks[bucket] = peak
                                }
                                if (peak > SILENCE_THRESHOLD) {
                                    leadingEnded = true
                                    trailingRunFrames = 0L
                                } else {
                                    if (!leadingEnded && frameIndex < silenceWindowFrames) leadingFrames++
                                    trailingRunFrames++
                                }
                                frameIndex++
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channels = out.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        floatOutput = out.getIntOrNull(MediaFormat.KEY_PCM_ENCODING) ==
                            android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                        stalls++
                        check(stalls < MAX_STALLS) { "decoder stalled after end of input" }
                    }
                }
            }

            if (frameIndex == 0L || sampleRate <= 0 || channels <= 0) return null
            val waveform = if (totalFrames > 0L) peaks else null
            return Analysis(
                sampleRate = sampleRate,
                channels = channels,
                bpmEnvelope = envelope.copyOf(envelopeCount),
                waveform = waveform,
                leadingSilentMs = silenceMs(leadingFrames, sampleRate),
                trailingSilentMs = silenceMs(
                    minOf(trailingRunFrames, silenceWindowFrames), sampleRate
                )
            )
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Silence shorter than [MIN_SILENCE_MS] is noise and reported as 0. */
    private fun silenceMs(frames: Long, sampleRate: Int): Long {
        val ms = frames * 1000L / sampleRate
        return if (ms >= MIN_SILENCE_MS) ms else 0L
    }

    private fun findAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return i to fmt
            }
        }
        return null
    }

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

    // ── BPM detection (autocorrelation on energy envelope) ────────

    /**
     * Detects BPM via autocorrelation of a 20 ms energy envelope, which
     * [decodeAnalysis] builds from a ~150 Hz low-passed signal.
     * Resolution ±2 BPM in the 60–180 BPM band.
     */
    private fun detectBpm(envelope: FloatArray): Float? {
        return try {
            val frames = envelope.size
            if (frames < 100) return null // need >2 s of audio

            // Remove DC.
            val mean = envelope.average().toFloat()
            val centred = FloatArray(frames) { envelope[it] - mean }

            // 60–180 BPM at a 20 ms hop: lag = 60 / bpm / 0.02 frames.
            // 180 BPM → 16.7 frames (use 17); 60 BPM → 50 frames.
            val minLag = 17
            val maxLag = 50
            if (frames < 2 * maxLag + 1) return null

            var bestLag = 0
            var bestCorr = Float.NEGATIVE_INFINITY
            for (lag in minLag..maxLag) {
                var corr = 0f
                for (i in 0 until frames - lag) {
                    corr += centred[i] * centred[i + lag]
                }
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                }
            }
            if (bestLag == 0 || bestCorr <= 0f) return null

            val bpm = 60f / (bestLag * 0.02f)
            bpm.coerceIn(40f, 240f)
        } catch (e: Exception) {
            Log.w(tag, "detectBpm failed", e)
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun readEncodedBytes(
        extractor: MediaExtractor,
        maxBytes: Int
    ): Int {
        var read = 0
        val buf = ByteBuffer.allocateDirect(64 * 1024)
        while (read < maxBytes) {
            buf.clear()
            val n = extractor.readSampleData(buf, 0)
            if (n <= 0) break
            read += n
            if (!extractor.advance()) break
        }
        return read
    }

    private fun MediaFormat.getIntOrNull(key: String): Int? {
        return try {
            if (containsKey(key)) getInteger(key) else null
        } catch (_: Exception) { null }
    }

    // ── Result type ───────────────────────────────────────────────

    data class TelemetryResult(
        val sampleRateHz: Int,
        val channelCount: Int,
        val isVbr: Boolean,
        val bpm: Float?,
        val waveformPreview: FloatArray?,
        val silentLeadingMs: Long?,
        val silentTrailingMs: Long?,
        val encodedBytesSampled: Int
    )

    private companion object {
        const val MAX_ENCODED_BYTES = 512 * 1024   // 512 KB sample for VBR heuristic
        const val MAX_BPM_SECONDS = 90             // BPM uses the first 90 s only
        const val ENVELOPE_HOP_SECONDS = 0.02f
        const val ENVELOPE_HOPS_PER_SECOND = 50
        const val ENVELOPE_ALPHA = 0.05f           // ~150 Hz cutoff at 44.1 kHz
        const val WAVEFORM_BUCKETS = 200
        const val SILENCE_THRESHOLD = 0.0056f      // −45 dBFS ≈ 0.0056 linear
        const val SILENCE_WINDOW_SECONDS = 30      // leading silence is measured over this window
        const val MIN_SILENCE_MS = 500L
        const val MAX_STALLS = 500                 // ~5 s of empty decoder polls after end of input
    }
}
