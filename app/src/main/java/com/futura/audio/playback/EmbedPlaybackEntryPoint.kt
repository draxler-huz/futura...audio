package com.futura.audio.playback

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets composables that are not Hilt view models reach the embed session. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EmbedPlaybackEntryPoint {
    fun embedPlaybackSession(): EmbedPlaybackSession
}
