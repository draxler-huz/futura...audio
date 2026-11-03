// app/src/main/java/com/futura/soundscape/SoundscapePlan.kt
package com.futura.soundscape

/**
 * Immutable collection of active soundscape layers plus global plan
 * settings.
 *
 * One layer per [SoundscapeKind]. Toggling a kind off removes its layer;
 * toggling it on re-adds it with [SoundscapeLayer.defaultFor].
 */
data class SoundscapePlan(
    val layers: Map<SoundscapeKind, SoundscapeLayer> = emptyMap(),
    val overallVolume: Float = 1f,
    val muted: Boolean = false
) {
    init {
        require(overallVolume in 0f..1f) { "overallVolume must be in [0, 1]" }
    }

    val isEmpty: Boolean get() = layers.isEmpty()
    val activeKinds: Set<SoundscapeKind> get() = layers.keys

    fun isEnabled(kind: SoundscapeKind): Boolean = layers.containsKey(kind)

    fun enable(kind: SoundscapeKind): SoundscapePlan {
        if (layers.containsKey(kind)) return this
        return copy(layers = layers + (kind to SoundscapeLayer.defaultFor(kind)))
    }

    fun disable(kind: SoundscapeKind): SoundscapePlan {
        if (!layers.containsKey(kind)) return this
        return copy(layers = layers - kind)
    }

    fun toggle(kind: SoundscapeKind): SoundscapePlan =
        if (isEnabled(kind)) disable(kind) else enable(kind)

    fun withLayer(layer: SoundscapeLayer): SoundscapePlan =
        copy(layers = layers + (layer.kind to layer))

    fun withVolume(kind: SoundscapeKind, volume: Float): SoundscapePlan {
        val existing = layers[kind] ?: SoundscapeLayer.defaultFor(kind)
        return withLayer(existing.copy(volume = volume.coerceIn(0f, 1f)))
    }

    fun withAzimuth(kind: SoundscapeKind, azimuth: Float): SoundscapePlan {
        val existing = layers[kind] ?: SoundscapeLayer.defaultFor(kind)
        return withLayer(existing.copy(azimuth = azimuth.coerceIn(-1f, 1f)))
    }

    fun clear(): SoundscapePlan = copy(layers = emptyMap())
}
