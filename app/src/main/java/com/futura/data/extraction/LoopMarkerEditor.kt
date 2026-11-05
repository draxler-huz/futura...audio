// app/src/main/java/com/futura/data/extraction/LoopMarkerEditor.kt
package com.futura.data.extraction

import com.futura.data.extraction.model.ExtractedTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 13 — Custom loop / fade marker editor.
 *
 * Records per-track loop start/end and fade curve duration in the
 * [ExtractedTrack]. The editor is a pure data layer: it validates
 * constraints (loop start < end, both within duration, fade < loop
 * length) and returns a modified track. It does not touch playback — a
 * downstream consumer (AlarmAssignmentBinder in group 4, or the alarm
 * audio pipeline directly) reads these fields when the track is
 * assigned to an alarm.
 *
 * The fade curve is stored as a duration in ms, not a curve formula.
 * Reason: the delivery pipeline renders the fade itself from whatever
 * curve it chooses at playback time. Storing a duration keeps the
 * contract simple and lets the audio engine pick the shape.
 *
 * All operations are pure functions of the input — no state, no logging,
 * no Android calls — so they run in plain JVM unit tests.
 */
@Singleton
class LoopMarkerEditor @Inject constructor() {

    /**
     * Set loop markers on [track].
     *
     * @param startMs loop start in ms (≥ 0).
     * @param endMs loop end in ms (≤ track.durationMs).
     * @return modified track, or null if [startMs] ≥ [endMs] or either
     *         falls outside `[0, durationMs]`.
     */
    fun setLoop(track: ExtractedTrack, startMs: Long, endMs: Long): ExtractedTrack? {
        if (startMs < 0L || endMs <= startMs || endMs > track.durationMs) return null
        return track.copy(loopStartMs = startMs, loopEndMs = endMs)
    }

    /** Clear any existing loop markers. */
    fun clearLoop(track: ExtractedTrack): ExtractedTrack =
        track.copy(loopStartMs = null, loopEndMs = null)

    /**
     * Set fade duration. Fade must be shorter than the loop length
     * (or the track's own duration when no loop is set) to avoid fading
     * over the entire playable range.
     *
     * @param fadeMs fade duration in ms. 0 disables fade.
     * @return modified track, or null if fade exceeds the playable range.
     */
    fun setFade(track: ExtractedTrack, fadeMs: Long): ExtractedTrack? {
        if (fadeMs < 0L) return null
        val playableMs = loopLengthMs(track) ?: track.durationMs
        if (fadeMs >= playableMs) return null
        return track.copy(fadeCurveMs = if (fadeMs == 0L) null else fadeMs)
    }

    /** Clear the fade. */
    fun clearFade(track: ExtractedTrack): ExtractedTrack =
        track.copy(fadeCurveMs = null)

    /** Loop duration in ms, or null if no loop is set. */
    fun loopLengthMs(track: ExtractedTrack): Long? {
        val start = track.loopStartMs ?: return null
        val end = track.loopEndMs ?: return null
        return (end - start).coerceAtLeast(0L)
    }

    /** Active playback duration in ms — the loop if set, else the full track. */
    fun activeDurationMs(track: ExtractedTrack): Long =
        loopLengthMs(track) ?: track.durationMs

    /**
     * Compute the effective range the alarm renderer should play:
     * returns `(startMs, endMs, fadeMs)` where fadeMs is 0 if no fade.
     */
    fun effectiveRange(track: ExtractedTrack): EffectiveRange {
        val start = track.loopStartMs ?: 0L
        val end = track.loopEndMs ?: track.durationMs
        val fade = track.fadeCurveMs ?: 0L
        return EffectiveRange(start, end, fade)
    }

    data class EffectiveRange(
        val startMs: Long,
        val endMs: Long,
        val fadeMs: Long
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }
}