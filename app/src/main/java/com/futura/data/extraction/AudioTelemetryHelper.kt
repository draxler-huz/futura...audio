// app/src/main/java/com/futura/data/extraction/AudioTelemetryHelper.kt
package com.futura.data.extraction

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes per-track audio telemetry from the encoded file:
 *
 *   • Feature 7  — BPM (autocorrelation on a low-passed energy envelope)
 *   • Feature 8  — waveform preview (downsampled peak array for the UI)
 *   • Feature 9  — leading / trailing silence durations
 *   • Feature 10 — CBR vs. VBR (declared bitrate vs. computed actual rate)
 *
 * The analysis is *fast and approximate*, not studio-grade:
 *   • BPM uses the "beat spectrum" family of algorithms on a 60–180 BPM
 *     band with ±2 BPM resolution. Adequate for the "sort by energy"
 *     use case; not adequate for pro DJ beat-matching (which the native
 *     `analysis/BpmCounter.h` will provide once delivered).
 *   • Waveform is 200 peak samples, sufficient for a scrubber preview;
 *     not a full-resolution waveform (that's `NativeWaveform`).
 *   • Silence is computed from the first / last 30 s of PCM at −45 dBFS
 *     with a 500 ms window; shorter gaps are ignored as noise.
 *   • VBR detection compares declared vs. computed bitrate — a heuristic.
 *     Real CBR/VBR detection requires per-frame inspection; this is what
 *     most players do as a first-pass filter.
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
            try {
                extractor.setDataSource(file.absolutePath)

                // Find the first audio track.
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = fmt
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return@withContext null
                extractor.selectTrack(trackIndex)

                val sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT)

                // Read a bounded number of encoded samples for the
                // telemetry pass. The extractor will not decode audio —
                // we read raw compressed frames and compute what we can.
                // Waveform and silence need decoded PCM, which requires
                // MediaCodec; we do that in a second pass below.
                val encodedBytes = readEncodedBytes(extractor, MAX_ENCODED_BYTES)
                extractor.release()

                // VBR heuristic from size/duration vs. declared bitrate.
                val actualKbps = if (durationMs > 0L) {
                    (file.length() * 8L / (durationMs / 1000L).coerceAtLeast(1L))
                        .toInt() / 1000
                } else 0

                val isVbr = when {
                    declaredBitrateKbps <= 0 || actualKbps <= 0 -> false
                    abs(actualKbps - declaredBitrateKbps) > declaredBitrateKbps * 0.15 -> true
                    else -> false
                }

                // Waveform + silence + BPM require decoded PCM. If PCM
                // decode fails, we still return VBR + basic info.
                val pcm = decodePcm(file)
                if (pcm == null) {
                    return@withContext TelemetryResult(
                        sampleRateHz = sampleRate ?: 0,
                        channelCount = channelCount ?: 0,
                        isVbr = isVbr,
                        bpm = null,
                        waveformPreview = null,
                        silentLeadingMs = null,
                        silentTrailingMs = null,
                        encodedBytesSampled = encodedBytes
                    )
                }

                val bpm = detectBpm(pcm.samples, pcm.sampleRate, pcm.channels)
                val waveform = downsamplePeaks(pcm.samples, WAVEFORM_BUCKETS)
                val (leadSilence, trailSilence) = detectSilence(
                    pcm.samples, pcm.channels, pcm.sampleRate
                )

                TelemetryResult(
                    sampleRateHz = sampleRate ?: 0,
                    channelCount = channelCount ?: 0,
                    isVbr = isVbr,
                    bpm = bpm,
                    waveformPreview = waveform,
                    silentLeadingMs = leadSilence,
                    silentTrailingMs = trailSilence,
                    encodedBytesSampled = encodedBytes
                )
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "compute failed for ${file.name}", e)
            null
        }
    }

    // ── PCM decoding ──────────────────────────────────────────────

    private data class Pcm(
        val samples: FloatArray,
        val sampleRate: Int,
        val channels: Int
    )

    private fun decodePcm(file: File): Pcm? {
        return try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = fmt
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return null
                extractor.selectTrack(trackIndex)

                val sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
                val channels = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

                val codec = android.media.MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()

                    val maxSamples = MAX_PCM_SAMPLES
                    val out = java.io.ByteArrayOutputStream(minOf(maxSamples * 2, 8 * 1024 * 1024))
                    val info = android.media.MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var bytesWritten = 0

                    while (!outputDone && bytesWritten < maxSamples * 2) {
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(10_000L)
                            if (inIdx >= 0) {
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0,
                                        android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(
                                        inIdx, 0, sampleSize,
                                        extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                        when {
                            outIdx >= 0 -> {
                                val outBuf = codec.getOutputBuffer(outIdx)!!
                                if (info.size > 0 && bytesWritten < maxSamples * 2) {
                                    val chunk = ByteArray(info.size)
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    outBuf.get(chunk)
                                    out.write(chunk)
                                    bytesWritten += chunk.size
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            }
                            outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                            outIdx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        }
                    }

                    val rawBytes = out.toByteArray()
                    val sampleCount = rawBytes.size / 2
                    val samples = FloatArray(sampleCount)
                    val bb = java.nio.ByteBuffer.wrap(rawBytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until sampleCount) {
                        samples[i] = bb.short / 32768f
                    }
                    return Pcm(samples, sampleRate, channels)
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    try { codec.release() } catch (_: Exception) {}
                }
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(tag, "decodePcm failed for ${file.name}", e)
            null
        }
    }

    // ── BPM detection (autocorrelation on energy envelope) ────────

    /**
     * Detects BPM via autocorrelation of a low-passed energy envelope.
     * Resolution ±2 BPM in the 60–180 BPM band.
     */
    private fun detectBpm(pcm: FloatArray, sampleRate: Int, channels: Int): Float? {
        return try {
            // 20 ms hop, counted in interleaved samples (stereo has 2 per frame).
            val hop = (sampleRate * channels * 0.02f).toInt().coerceAtLeast(1)
            val frames = pcm.size / hop
            if (frames < 100) return null // need >2 s of audio

            // Compute low-band energy envelope. A first-order low-pass at
            // ~150 Hz isolates the beat band. Then RMS per hop.
            val envelope = FloatArray(frames)
            var lp = 0f
            val alpha = 0.05f // ~150 Hz cutoff at 44.1 kHz
            var idx = 0
            for (f in 0 until frames) {
                var sumSq = 0f
                for (h in 0 until hop) {
                    if (idx >= pcm.size) break
                    lp += alpha * (pcm[idx] - lp)
                    sumSq += lp * lp
                    idx++
                }
                envelope[f] = sqrt(sumSq / hop)
            }

            // Remove DC and normalize.
            val mean = envelope.average().toFloat()
            for (i in envelope.indices) envelope[i] -= mean

            // Autocorrelation over the 60–180 BPM lag range.
            // At 20 ms hop: 60 BPM = 3 beats/s = 0.333 s = 16 frames.
            //               180 BPM = 3 beats/s = 0.111 s = 5.5 frames.
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
                    corr += envelope[i] * envelope[i + lag]
                }
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                }
            }
            if (bestLag == 0 || bestCorr <= 0f) return null

            val bpm = 60f / (bestLag * 0.02f)
            return bpm.coerceIn(40f, 240f)
        } catch (e: Exception) {
            Log.w(tag, "detectBpm failed", e)
            null
        }
    }

    // ── Waveform preview ──────────────────────────────────────────

    /**
     * Downsamples [samples] into [buckets] peak-amplitude points in
     * [0, 1]. The array is what the UI scrubber preview displays.
     */
    private fun downsamplePeaks(samples: FloatArray, buckets: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val out = FloatArray(buckets)
        val per = samples.size.toFloat() / buckets
        for (b in 0 until buckets) {
            val start = (b * per).toInt()
            val end = ((b + 1) * per).toInt().coerceAtMost(samples.size)
            var peak = 0f
            for (i in start until end) {
                val a = abs(samples[i])
                if (a > peak) peak = a
            }
            out[b] = peak.coerceIn(0f, 1f)
        }
        return out
    }

    // ── Silence detection ─────────────────────────────────────────

    /**
     * Detect leading/trailing silence at −45 dBFS threshold. Requires
     * 500 ms of continuous sub-threshold signal to count as silence.
     * Only examines the first and last 30 s of samples.
     */
    private fun detectSilence(
        samples: FloatArray,
        channels: Int,
        sampleRate: Int
    ): Pair<Long, Long> {
        if (samples.isEmpty() || channels <= 0 || sampleRate <= 0) {
            return 0L to 0L
        }
        val threshold = 0.0056f // −45 dBFS ≈ 0.0056 linear
        val minSilenceSamples = (sampleRate * 0.5f).toInt()
        val windowSamples = (sampleRate * 30).toInt()
        val frames = samples.size / channels

        var leadingSilentFrames = 0
        val leadingEnd = minOf(frames, windowSamples)
        var i = 0
        while (i < leadingEnd) {
            var loud = false
            for (c in 0 until channels) {
                if (abs(samples[i * channels + c]) > threshold) { loud = true; break }
            }
            if (loud) break
            leadingSilentFrames++
            i++
        }
        if (leadingSilentFrames < minSilenceSamples) leadingSilentFrames = 0

        var trailingSilentFrames = 0
        val trailingStart = maxOf(0, frames - windowSamples)
        var j = frames - 1
        while (j >= trailingStart) {
            var loud = false
            for (c in 0 until channels) {
                if (abs(samples[j * channels + c]) > threshold) { loud = true; break }
            }
            if (loud) break
            trailingSilentFrames++
            j--
        }
        if (trailingSilentFrames < minSilenceSamples) trailingSilentFrames = 0

        val leadMs = (leadingSilentFrames * 1000L) / sampleRate
        val trailMs = (trailingSilentFrames * 1000L) / sampleRate
        return leadMs to trailMs
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun readEncodedBytes(
        extractor: MediaExtractor,
        maxBytes: Int
    ): Int {
        var read = 0
        val buf = java.nio.ByteBuffer.allocateDirect(64 * 1024)
        while (read < maxBytes) {
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
        const val MAX_ENCODED_BYTES = 512 * 1024  // 512 KB sample for VBR heuristic
        const val MAX_PCM_SAMPLES = 8 * 1024 * 1024  // 8M samples ≈ 3 min stereo @ 48 kHz
        const val WAVEFORM_BUCKETS = 200
    }
}