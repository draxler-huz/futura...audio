// app/src/main/java/com/futura/data/extraction/AudioTranscoder.kt
package com.futura.data.extraction

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feature 14 — Lightweight audio transcoder.
 *
 * Converts a source file (FLAC, WAV, etc.) to a compressed target
 * (AAC in MP4) via the platform's MediaCodec + MediaMuxer.
 *
 * The output container is MP4 (.m4a) for AAC — the universally supported
 * combination on Android. A raw .mp3 output is deliberately avoided
 * because Android's MP3 encoder output through MediaMuxer requires the
 * `.mp3` container which some platforms refuse to write.
 *
 * Bitrate targets:
 *   • [TargetFormat.AAC_128] — speech / portable (128 kbps stereo)
 *   • [TargetFormat.AAC_192] — music (192 kbps stereo)
 *   • [TargetFormat.AAC_256] — high-quality music (256 kbps stereo)
 *
 * Rule 6: every codec call wrapped; failure returns null.
 * Cancellation: a cancelled transcode deletes its partial output and
 * rethrows the cancellation.
 */
@Singleton
class AudioTranscoder @Inject constructor() {

    private val tag = "AudioTranscoder"

    enum class TargetFormat(val bitrateBps: Int, val extension: String) {
        AAC_128(128_000, "m4a"),
        AAC_192(192_000, "m4a"),
        AAC_256(256_000, "m4a")
    }

    data class Result(
        val outputPath: String,
        val outputSizeBytes: Long,
        val sourceSizeBytes: Long
    ) {
        val compressionRatio: Float
            get() = if (sourceSizeBytes <= 0L) 0f
                    else outputSizeBytes.toFloat() / sourceSizeBytes
    }

    /** Muxer plus the track index and start state the encoder output needs. */
    private class MuxState(val muxer: MediaMuxer) {
        var trackIndex = -1
        var started = false
    }

    /**
     * Transcode [source] to the target format, writing to [outputDir].
     *
     * @return [Result] on success, or null on any failure. The output
     *         file is deleted on failure or cancellation.
     */
    suspend fun transcode(
        source: File,
        outputDir: File,
        target: TargetFormat = TargetFormat.AAC_192
    ): Result? = withContext(Dispatchers.IO) {
        if (!source.exists() || !source.canRead()) return@withContext null
        if (!outputDir.exists() && !outputDir.mkdirs()) return@withContext null

        val outputFile = File(
            outputDir,
            source.nameWithoutExtension + "." + target.extension
        )
        // If the output already exists, bail out to avoid overwriting.
        if (outputFile.exists()) return@withContext null

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(source.absolutePath)

            // Source track.
            var sourceTrackIndex = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    sourceTrackIndex = i
                    sourceFormat = fmt
                    break
                }
            }
            if (sourceTrackIndex < 0 || sourceFormat == null) return@withContext null
            extractor.selectTrack(sourceTrackIndex)

            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)!!
            val sourceSampleRate =
                sourceFormat.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            val sourceChannels =
                sourceFormat.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2

            // Decoder.
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            // Encoder.
            val targetMime = MediaFormat.MIMETYPE_AUDIO_AAC
            val encoderFormat = MediaFormat.createAudioFormat(
                targetMime, sourceSampleRate, sourceChannels
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, target.bitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(targetMime)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Muxer. Samples are written only after the encoder reports its format.
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mux = MuxState(muxer)

            // Separate BufferInfo per codec: sharing one would overwrite the
            // decoder's flags and size while the encoder is being drained.
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var stalls = 0

            while (!encoderDone) {
                // Feed decoder.
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIdx, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder → feed encoder.
                if (!decoderDone) {
                    val decIdx = decoder.dequeueOutputBuffer(decInfo, 10_000L)
                    if (decIdx >= 0) {
                        stalls = 0
                        val decBuf = decoder.getOutputBuffer(decIdx)!!
                        if (decInfo.size > 0) {
                            check(decInfo.size <= 64 * 1024) {
                                "decoder chunk ${decInfo.size} B exceeds encoder input"
                            }
                            // Wait for a free encoder input buffer, emptying encoder output meanwhile.
                            var encIn = encoder.dequeueInputBuffer(10_000L)
                            while (encIn < 0) {
                                drainEncoder(encoder, mux, encInfo, 0L)
                                encIn = encoder.dequeueInputBuffer(10_000L)
                            }
                            val encBuf = encoder.getInputBuffer(encIn)!!
                            encBuf.clear()
                            decBuf.position(decInfo.offset)
                            decBuf.limit(decInfo.offset + decInfo.size)
                            encBuf.put(decBuf)
                            encoder.queueInputBuffer(
                                encIn, 0, decInfo.size, decInfo.presentationTimeUs, 0
                            )
                        }
                        val endOfDecoder =
                            (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(decIdx, false)
                        if (endOfDecoder) {
                            decoderDone = true
                            // Signal end of stream to the encoder, emptying its output while waiting.
                            var encIn = encoder.dequeueInputBuffer(10_000L)
                            while (encIn < 0) {
                                drainEncoder(encoder, mux, encInfo, 0L)
                                encIn = encoder.dequeueInputBuffer(10_000L)
                            }
                            encoder.queueInputBuffer(
                                encIn, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                    } else if (decIdx == MediaCodec.INFO_TRY_AGAIN_LATER && extractorDone) {
                        // Decoder has no output and no input left: a stuck decoder would loop forever.
                        stalls++
                        check(stalls < MAX_STALLS) { "decoder stalled after end of input" }
                    }
                }

                encoderDone = drainEncoder(encoder, mux, encInfo, 10_000L)
            }

            check(mux.started) { "encoder produced no output" }
            muxer.stop()
            muxer.release()
            muxer = null

            Result(
                outputPath = outputFile.absolutePath,
                outputSizeBytes = outputFile.length(),
                sourceSizeBytes = source.length()
            )
        } catch (e: CancellationException) {
            try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            Log.e(tag, "transcode failed for ${source.name}", e)
            try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
            null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Writes every encoder output that is ready. Starts the muxer on the
     * first format report and skips codec-config buffers, which the muxer
     * already gets from the format. Returns true once end of stream arrives.
     */
    private fun drainEncoder(
        encoder: MediaCodec,
        mux: MuxState,
        info: MediaCodec.BufferInfo,
        timeoutUs: Long
    ): Boolean {
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!mux.started) {
                        mux.trackIndex = mux.muxer.addTrack(encoder.outputFormat)
                        mux.muxer.start()
                        mux.started = true
                    }
                }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)!!
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (info.size > 0 && mux.started && !config) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        mux.muxer.writeSampleData(mux.trackIndex, buf, info)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
                else -> {}
            }
        }
    }

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        try { if (containsKey(key)) getInteger(key) else null } catch (_: Exception) { null }

    private companion object {
        /** About 5 s of empty decoder polls after end of input before giving up. */
        const val MAX_STALLS = 500
    }
}
