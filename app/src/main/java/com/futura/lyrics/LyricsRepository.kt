package com.futura.lyrics

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chains lyric providers so a miss on one falls through to the next.
 *
 * Providers are tried in order and the first one with a match wins. A
 * provider that *fails* (network, bad reply) does not stop the chain — the
 * next one is still tried — but its error is remembered so the caller can
 * tell "nothing found" apart from "could not look".
 *
 * Order matters: LRCLIB goes first because it is the only provider here
 * that returns time-synced lyrics, which drive the karaoke highlighting.
 * Lyrics.ovh is the plain-text fallback for tracks LRCLIB does not have.
 * The order is fixed by [LyricsModule].
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val providers: List<@JvmSuppressWildcards LyricsProvider>
) {

    suspend fun find(title: String, artist: String?, durationMs: Long): LyricsState {
        var firstError: LyricsState.Error? = null
        for (provider in providers) {
            when (val result = provider.find(title, artist, durationMs)) {
                is LyricsState.Success -> return result
                is LyricsState.Error -> if (firstError == null) firstError = result
                LyricsState.Empty, LyricsState.Loading -> Unit
            }
        }
        return firstError ?: LyricsState.Empty
    }
}
