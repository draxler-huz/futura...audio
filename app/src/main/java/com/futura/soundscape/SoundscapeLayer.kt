// app/src/main/java/com/futura/soundscape/SoundscapeLayer.kt
package com.futura.soundscape

/**
 * Immutable configuration for one active soundscape layer.
 *
 * @param kind Which [SoundscapeKind] this layer plays.
 * @param volume Layer gain, `[0, 1]`. Combines multiplicatively with
 *        [SoundscapePlan.overallVolume].
 * @param azimuth Stereo position: `-1` = hard left, `0` = centre,
 *        `+1` = hard right. Applied via equal-power panning.
 * @param elevation Height cue, `[0, 1]`: `0` = ear level, `1` = above.
 *        Ignored today; reserved for the future HRTF stage.
 * @param distance Distance cue, `[0, 1]`: `0` = close, `1` = far. Applied
 *        as inverse-square-ish attenuation.
 */
data class SoundscapeLayer(
    val kind: SoundscapeKind,
    val volume: Float,
    val azimuth: Float = 0f,
    val elevation: Float = 0f,
    val distance: Float = 0f
) {
    init {
        require(volume in 0f..1f) { "volume must be in [0, 1]" }
        require(azimuth in -1f..1f) { "azimuth must be in [-1, 1]" }
        require(elevation in 0f..1f) { "elevation must be in [0, 1]" }
        require(distance in 0f..1f) { "distance must be in [0, 1]" }
    }

    /** Left-ear gain from azimuth. Equal-power: cos((az+1)·π/4). */
    fun leftGain(): Float {
        val theta = (azimuth + 1f) * (Math.PI.toFloat() / 4f)
        return kotlin.math.cos(theta)
    }

    /** Right-ear gain from azimuth. Equal-power: sin((az+1)·π/4). */
    fun rightGain(): Float {
        val theta = (azimuth + 1f) * (Math.PI.toFloat() / 4f)
        return kotlin.math.sin(theta)
    }

    /** Distance attenuation: 1.0 at dist=0, about 0.33 at dist=1. */
    fun distanceGain(): Float = 1f / (1f + distance * 2f)

    companion object {
        /** Default layer for [kind], centred at its default volume. */
        fun defaultFor(kind: SoundscapeKind): SoundscapeLayer = SoundscapeLayer(
            kind = kind,
            volume = kind.defaultVolume,
            azimuth = 0f
        )
    }
}
