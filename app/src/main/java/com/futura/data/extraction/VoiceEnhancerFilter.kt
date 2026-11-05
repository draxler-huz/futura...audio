// app/src/main/java/com/futura/data/extraction/VoiceEnhancerFilter.kt
package com.futura.data.extraction

import com.futura.data.extraction.model.ExtractedTrack
import com.futura.engine.DspBridge
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feature 15 — Voice / speech enhancer filter.
 *
 * A preset "high-pass + presence boost" EQ that makes recorded memos,
 * voice extractions, or spoken-word tracks sound clearer on small
 * speakers (like a phone's built-in speaker, which is what an alarm
 * usually plays through).
 *
 * Implementation: the delivered DSP chain exposes a 10-band graphic EQ
 * via `DspBridge.setEqBandGain()`. This class maps the enhancer's
 * intent (cut lows, preserve presence, gentle air) onto that EQ:
 *
 *   Bands 0–3  (31 Hz … 250 Hz)   → −12 dB   (removes rumble, plosives)
 *   Band  4    (500 Hz)            → −6 dB    (reduces boxiness)
 *   Band  5    (1 kHz)             →  0 dB    (reference)
 *   Bands 6–7  (2 kHz … 4 kHz)    → +3 dB    (presence / intelligibility)
 *   Bands 8–9  (8 kHz … 16 kHz)   → +1 dB    (air, gentle)
 *
 * Applying the preset calls `DspBridge.setEqBandGain(i, gainDb)` for
 * all 10 bands. It also implicitly enables the DSP chain (a call to
 * `DspBridge.setDspEnabled(true)`) so the enhancement takes effect
 * immediately.
 *
 * GROWTH: once `dsp/HighPassDcBlockingFilter.h` and a dedicated
 * voice-enhancer biquad are delivered, this class will call the more
 * precise native path. The 10-band approximation is audible today.
 *
 * Rule 6: every DspBridge call wrapped.
 */
@Singleton
class VoiceEnhancerFilter @Inject constructor() {

    /** Band gains saved by the first [apply], or null while the preset is not applied. */
    @Volatile
    private var savedGains: FloatArray? = null

    /**
     * The band gains for the "voice enhancer" preset, in dB. Index 0 =
     * 31 Hz, index 9 = 16 kHz. This is the canonical definition of the
     * preset; the [apply] method pushes exactly these values to the
     * engine.
     */
    val preset: FloatArray = floatArrayOf(
        -12f, -12f, -12f, -12f,
        -6f,
        0f,
        3f, 3f,
        1f, 1f
    )

    /**
     * Apply the voice enhancer preset to the DSP chain.
     *
     * @return true if all 10 band settings were accepted (or the
     *         engine is not yet running, in which case the settings
     *         are stored for the next engine init). false on any
     *         exception.
     */
    suspend fun apply(): Boolean = withContext(Dispatchers.Default) {
        try {
            // Remember the gains from before the first apply, so clear() can restore them.
            if (savedGains == null) {
                savedGains = FloatArray(preset.size) { DspBridge.getEqBandGain(it) }
            }
            DspBridge.setDspEnabled(true)
            preset.forEachIndexed { i, gain ->
                DspBridge.setEqBandGain(i, gain)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "apply failed", e)
            false
        }
    }

    /**
     * Put back the band gains that were set before [apply]. Bands that were
     * 0 dB before are reset to 0 dB. The DSP chain stays enabled.
     */
    suspend fun clear(): Boolean = withContext(Dispatchers.Default) {
        try {
            val saved = savedGains ?: FloatArray(preset.size)
            for (i in preset.indices) DspBridge.setEqBandGain(i, saved[i])
            savedGains = null
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clear failed", e)
            false
        }
    }

    /**
     * Report whether [track] would benefit from the voice enhancer.
     *
     * Heuristic: mono tracks, or low-bitrate (at most 96 kbps) tracks under
     * about 5 minutes, are usually memos or spoken word, where the presence
     * boost is noticeably helpful. Music tracks rarely need it.
     */
    fun isRecommendedFor(track: ExtractedTrack): Boolean {
        return track.channelCount == 1 ||
            (track.bitrateKbps in 1..96 && track.durationMs < 5 * 60_000L)
    }

    private companion object {
        const val TAG = "VoiceEnhancerFilter"
    }
}
