// app/src/main/java/com/futura/tone/AlarmToneGenerator.kt
package com.futura.tone

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates the built-in alarm tone as a 16-bit mono PCM WAV, on first use.
 *
 * The tone is a 12-second escalating dual-tone pattern: one second per step,
 * pitch rising every four seconds, amplitude rising across the loop. Each
 * step has a short fade in and out, so the loop point and the step edges
 * don't click. The file is lossless and needs no decoder beyond the native
 * WAV parser.
 *
 * Generated at runtime to keep the build self-contained. The file is written
 * to a temporary name and renamed, so an interrupted write never leaves a
 * truncated tone that passes the size check.
 */
object AlarmToneGenerator {

    const val SAMPLE_RATE = 44_100
    const val DURATION_SECONDS = 12

    /** Versioned, so a change to the pattern means a new file name. */
    const val FILE_NAME = "alarm_tone_v1.wav"

    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val HEADER_BYTES = 44
    private const val FADE_MS = 10
    private const val BASE_HZ = 660.0
    private const val FIFTH_RATIO = 1.5
    private const val FIFTH_LEVEL = 0.6
    private const val MIN_LEVEL = 0.30
    private const val MAX_LEVEL = 0.85

    /** Expected file size. A file of any other size is regenerated. */
    private val expectedBytes: Long =
        (HEADER_BYTES + SAMPLE_RATE.toLong() * DURATION_SECONDS * CHANNELS * (BITS_PER_SAMPLE / 8)).toLong()

    /**
     * Returns the tone file under [dir], creating it if it is missing or the
     * wrong size. Blocking file I/O: call from an IO dispatcher, never the
     * main thread or the audio thread.
     */
    fun ensureTone(dir: File): File {
        require(dir.isDirectory || dir.mkdirs()) { "cannot create ${dir.path}" }
        val target = File(dir, FILE_NAME)
        if (target.isFile && target.length() == expectedBytes) return target

        val temp = File(dir, "$FILE_NAME.tmp")
        temp.outputStream().buffered().use { writeWav(it, render()) }
        check(temp.length() == expectedBytes) { "tone size mismatch: ${temp.length()}" }
        if (target.exists() && !target.delete()) error("cannot replace ${target.path}")
        check(temp.renameTo(target)) { "cannot rename tone into place" }
        return target
    }

    /** Renders the pattern into 16-bit samples. Pure, so it can be unit-tested. */
    internal fun render(): ShortArray {
        val total = SAMPLE_RATE * DURATION_SECONDS
        val out = ShortArray(total)
        val fadeSamples = SAMPLE_RATE * FADE_MS / 1000
        val stepSamples = SAMPLE_RATE

        for (step in 0 until DURATION_SECONDS) {
            // Pitch steps up every four seconds, then resets for the next pass.
            val base = BASE_HZ * (1.0 + 0.25 * ((step / 4) % 3))
            val level = MIN_LEVEL + (MAX_LEVEL - MIN_LEVEL) * step / (DURATION_SECONDS - 1)
            val start = step * stepSamples

            for (i in 0 until stepSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = fadeEnvelope(i, stepSamples, fadeSamples)
                val wave = sin(2.0 * PI * base * t) +
                    FIFTH_LEVEL * sin(2.0 * PI * base * FIFTH_RATIO * t)
                // The two partials can add up to 1.6, so normalise by that peak.
                val value = level * envelope * wave / (1.0 + FIFTH_LEVEL)
                out[start + i] = (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    /** Raised-cosine fade in and out, so a step's edges don't click. */
    private fun fadeEnvelope(index: Int, length: Int, fade: Int): Double {
        if (fade <= 0) return 1.0
        return when {
            index < fade -> 0.5 - 0.5 * kotlin.math.cos(PI * index / fade)
            index >= length - fade -> 0.5 - 0.5 * kotlin.math.cos(PI * (length - 1 - index) / fade)
            else -> 1.0
        }
    }

    private fun writeWav(out: OutputStream, pcm: ShortArray) {
        val dataBytes = pcm.size * (BITS_PER_SAMPLE / 8)
        val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ascii("RIFF")).putInt(36 + dataBytes).put(ascii("WAVE"))
        header.put(ascii("fmt ")).putInt(16)
        header.putShort(1)                     // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put(ascii("data")).putInt(dataBytes)
        out.write(header.array())

        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) body.putShort(sample)
        out.write(body.array())
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
}
