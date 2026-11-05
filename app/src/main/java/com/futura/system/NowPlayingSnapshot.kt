// app/src/main/java/com/futura/system/NowPlayingSnapshot.kt
package com.futura.system

/**
 * Latest playback state, read by the Quick Settings tile. Written by
 * [FuturaPlaybackService] on the main thread whenever the player changes.
 * Held in memory only; a fresh process has nothing playing until the
 * service publishes again.
 */
object NowPlayingSnapshot {
    @Volatile
    var title: String? = null

    @Volatile
    var isPlaying: Boolean = false
}
