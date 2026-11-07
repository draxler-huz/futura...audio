package com.futura.lyrics

/**
 * One source of lyrics. Implementations are tried in order by
 * [LyricsRepository] so a provider that has no match never hides one that
 * does.
 */
interface LyricsProvider {

    /**
     * Looks up lyrics for one track. Returns [LyricsState.Empty] when the
     * provider has no entry, [LyricsState.Error] when the lookup itself
     * failed.
     */
    suspend fun find(title: String, artist: String?, durationMs: Long): LyricsState
}
