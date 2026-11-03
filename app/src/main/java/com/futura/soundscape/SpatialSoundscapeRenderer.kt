// app/src/main/java/com/futura/soundscape/SpatialSoundscapeRenderer.kt
package com.futura.soundscape

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.futura.alarm.VoiceDropStitcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Renders a [SoundscapePlan] to PCM and pushes the mix into
 * [VoiceDropStitcher]'s ambient input.
 *
 * Pipeline per 14-second refresh cycle:
 *   1. For each active layer, read a 15-second window from the cached PCM.
 *   2. Apply equal-power stereo panning from the layer's azimuth, plus
 *      distance attenuation.
 *   3. Sum all layers into one stereo buffer.
 *   4. Scale by [SoundscapePlan.overallVolume] (zero if muted).
 *   5. Push to `stitcher.pushAmbient(...)`.
 *
 * The 15 s window and 14 s refresh overlap by 1 s. The stitcher replaces its
 * ambient buffer on each push, so the overlap is dropped, not queued.
 *
 * Failures degrade the affected layer to silence instead of crashing.
 * Plan state is guarded by [mutex]. The PCM cache and failed-preload set are
 * [ConcurrentHashMap]s, so they are safe to touch from any thread.
 */
@Singleton
class SpatialSoundscapeRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stitcher: VoiceDropStitcher
) {
    private val tag = "SpatialSoundscapeRenderer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _plan = MutableStateFlow(SoundscapePlan())
    val plan: StateFlow<SoundscapePlan> = _plan.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var renderJob: Job? = null

    // Written during preload from any thread, read during render.
    private val pcmCache = ConcurrentHashMap<SoundscapeKind, FloatArray>()
    private val failedPreloads = ConcurrentHashMap.newKeySet<SoundscapeKind>()

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Update the active plan. Preloads newly enabled kinds before updating
     * state, so the render loop never starts without cached PCM.
     */
    suspend fun setPlan(newPlan: SoundscapePlan) {
        // Decoding can take hundreds of ms. Do it outside the mutex so
        // concurrent toggle() calls are not blocked.
        for (kind in newPlan.activeKinds) {
            if (!pcmCache.containsKey(kind) && !failedPreloads.contains(kind)) {
                preload(kind)
            }
        }
        mutex.withLock {
            _plan.value = newPlan
            if (newPlan.isEmpty && _running.value) {
                stopLocked()
            } else if (!newPlan.isEmpty && !_running.value) {
                startLocked()
            }
        }
    }

    /** Convenience for the UI toggle path. */
    suspend fun toggle(kind: SoundscapeKind) {
        setPlan(_plan.value.toggle(kind))
    }

    /** Stop rendering. Safe to call when not running. */
    suspend fun stop() = mutex.withLock { stopLocked() }

    /**
     * Pre-decode a kind's PCM. Returns true if the cache now holds this
     * kind's data. On failure the kind is remembered, so later preloads skip
     * it.
     */
    suspend fun preload(kind: SoundscapeKind): Boolean = withContext(Dispatchers.IO) {
        try {
            pcmCache[kind]?.let { return@withContext true }
            if (failedPreloads.contains(kind)) return@withContext false

            val decoded = decodeAsset(kind.assetPath)
            if (decoded != null && decoded.isNotEmpty()) {
                pcmCache[kind] = decoded
                Log.i(tag, "Preloaded ${kind.name}: ${decoded.size} samples")
                true
            } else {
                failedPreloads.add(kind)
                Log.w(tag, "Failed to preload ${kind.name}; will not retry")
                false
            }
        } catch (e: Exception) {
            failedPreloads.add(kind)
            Log.e(tag, "preload(${kind.name}) threw", e)
            false
        }
    }

    // ── Render loop ────────────────────────────────────────────────

    private fun startLocked() {
        if (renderJob?.isActive == true) return
        _running.value = true
        renderJob = scope.launch {
            try {
                // Configure the stitcher with default 48 kHz stereo. This is
                // idempotent and must run before pushAmbient() succeeds.
                stitcher.configure(VoiceDropStitcher.Config())

                while (isActive) {
                    val currentPlan = _plan.value
                    if (currentPlan.isEmpty) break

                    // Decode any active kind that is not cached yet.
                    for (kind in currentPlan.activeKinds) {
                        if (!pcmCache.containsKey(kind) && !failedPreloads.contains(kind)) {
                            preload(kind)
                        }
                    }

                    val mixed = renderWindow(currentPlan, CHUNK_FRAMES)
                    if (mixed != null) {
                        stitcher.pushAmbient(
                            pcm = mixed,
                            sourceRate = SAMPLE_RATE,
                            sourceChannels = CHANNELS
                        )
                    }

                    delay(CHUNK_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(tag, "Render loop failed", e)
            } finally {
                _running.value = false
            }
        }
    }

    private fun stopLocked() {
        renderJob?.cancel()
        renderJob = null
        _running.value = false
    }

    private fun renderWindow(plan: SoundscapePlan, frames: Int): FloatArray? {
        return try {
            val out = FloatArray(frames * CHANNELS)
            val globalGain = if (plan.muted) 0f else plan.overallVolume
            var anyLayerRendered = false

            for (layer in plan.layers.values) {
                val pcm = pcmCache[layer.kind] ?: continue
                val layerFrames = pcm.size / CHANNELS
                if (layerFrames == 0) continue

                val layerGain = layer.volume * layer.distanceGain() * globalGain
                val leftGain = layer.leftGain() * layerGain
                val rightGain = layer.rightGain() * layerGain

                // Each window reads from the start of the buffer. The assets
                // are seam-matched, so the window boundary is continuous.
                val toMix = min(frames, layerFrames)
                for (f in 0 until toMix) {
                    val l = pcm[f * CHANNELS]
                    val r = pcm[f * CHANNELS + 1]
                    out[f * CHANNELS] += l * leftGain
                    out[f * CHANNELS + 1] += r * rightGain
                }
                anyLayerRendered = true
            }

            if (!anyLayerRendered) null else out
        } catch (e: Exception) {
            Log.e(tag, "renderWindow failed", e)
            null
        }
    }

    // ── Asset decoding (MediaCodec) ────────────────────────────────

    private suspend fun decodeAsset(assetPath: String): FloatArray? =
        withContext(Dispatchers.IO) {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                val ex = MediaExtractor()
                extractor = ex
                context.assets.openFd(assetPath).use { afd ->
                    ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = fmt
                        break
                    }
                }
                if (trackIndex < 0 || format == null) {
                    Log.w(tag, "No audio track in $assetPath")
                    return@withContext null
                }
                ex.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val dec = MediaCodec.createDecoderByType(mime)
                codec = dec
                dec.configure(format, null, null, 0)
                dec.start()

                val decoderOut = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = dec.getInputBuffer(inIdx)!!
                            val sampleSize = ex.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(
                                    inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(
                                    inIdx, 0, sampleSize,
                                    ex.sampleTime, 0
                                )
                                ex.advance()
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx >= 0 -> {
                            val outBuf = dec.getOutputBuffer(outIdx)!!
                            if (info.size > 0) {
                                val chunk = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(chunk)
                                decoderOut.write(chunk)
                            }
                            dec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Ignored. The source format is fixed for our assets.
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Try again on the next loop iteration.
                        }
                    }
                }

                // Decoded PCM is int16 little-endian. Convert to float32.
                val rawBytes = decoderOut.toByteArray()
                val samples = rawBytes.size / 2
                val floats = FloatArray(samples)
                val bb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until samples) {
                    floats[i] = bb.short / 32768f
                }

                val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

                val stereoFloats = if (sourceChannels == 1) {
                    val out = FloatArray(floats.size * 2)
                    for (i in floats.indices) {
                        out[i * 2] = floats[i]
                        out[i * 2 + 1] = floats[i]
                    }
                    out
                } else if (sourceChannels == 2) {
                    floats
                } else {
                    Log.w(tag, "Unsupported channel count $sourceChannels for $assetPath")
                    return@withContext null
                }

                if (sourceRate == SAMPLE_RATE) {
                    stereoFloats
                } else {
                    resampleLinear(stereoFloats, 2, sourceRate, SAMPLE_RATE)
                }
            } catch (e: Exception) {
                Log.e(tag, "decodeAsset($assetPath) failed", e)
                null
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
            }
        }

    private fun resampleLinear(
        src: FloatArray,
        channels: Int,
        fromRate: Int,
        toRate: Int
    ): FloatArray {
        val srcFrames = src.size / channels
        val dstFrames = (srcFrames.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(dstFrames * channels)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (f in 0 until dstFrames) {
            val srcPos = f * ratio
            val i0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val i1 = (i0 + 1).coerceIn(0, srcFrames - 1)
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until channels) {
                val a = src[i0 * channels + c]
                val b = src[i1 * channels + c]
                out[f * channels + c] = a + (b - a) * frac
            }
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK_FRAMES = 15 * SAMPLE_RATE    // 15 seconds
        const val CHUNK_INTERVAL_MS = 14_000L        // 14 s refresh
    }
}
