package com.futura.lyrics

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fixes the order providers are tried in. LRCLIB is first because it is the
 * only one that returns time-synced lyrics; Lyrics.ovh is the plain-text
 * fallback. Add a provider to this list to put it in the chain.
 */
@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {

    @Provides
    @Singleton
    fun provideLyricsProviders(
        lrcLib: LrcLibLyrics,
        lyricsOvh: LyricsOvhProvider
    ): List<@JvmSuppressWildcards LyricsProvider> = listOf(lrcLib, lyricsOvh)
}
