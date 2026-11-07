package com.futura.lyrics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The provider chain decides which lyrics the user sees, so its ordering
 * and fall-through rules are pinned here with small fakes.
 */
class LyricsRepositoryTest {

    /** Returns [result] from [find]. Records each lookup so order is visible. */
    private class FakeProvider(
        private val result: LyricsState,
        private val calls: MutableList<String>
    ) : LyricsProvider {
        override suspend fun find(title: String, artist: String?, durationMs: Long): LyricsState {
            calls += title
            return result
        }
    }

    private val success = LyricsState.Success(plainLyrics = "la la", syncedLyrics = "[00:01.00]la la")
    private val missed = LyricsState.Empty
    private val failed = LyricsState.Error("boom")

    private fun repo(first: LyricsState, second: LyricsState, calls: MutableList<String>) =
        LyricsRepository(listOf(FakeProvider(first, calls), FakeProvider(second, calls)))

    @Test
    fun `first provider's success wins and the second is not called`() = runTest {
        val calls = mutableListOf<String>()
        val result = repo(success, success, calls).find("Song", "Artist", 1000L)
        assertThat(result).isEqualTo(success)
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `a miss falls through to the next provider`() = runTest {
        val calls = mutableListOf<String>()
        val result = repo(missed, success, calls).find("Song", "Artist", 1000L)
        assertThat(result).isEqualTo(success)
        assertThat(calls).hasSize(2)
    }

    @Test
    fun `a failure does not stop the chain`() = runTest {
        val calls = mutableListOf<String>()
        val result = repo(failed, success, calls).find("Song", "Artist", 1000L)
        assertThat(result).isEqualTo(success)
        assertThat(calls).hasSize(2)
    }

    @Test
    fun `when nothing matches the result is empty`() = runTest {
        val calls = mutableListOf<String>()
        val result = repo(missed, missed, calls).find("Song", "Artist", 1000L)
        assertThat(result).isEqualTo(LyricsState.Empty)
    }

    @Test
    fun `when every provider fails the first error is surfaced`() = runTest {
        val calls = mutableListOf<String>()
        val result = repo(failed, LyricsState.Error("second"), calls).find("Song", "Artist", 1000L)
        assertThat(result).isEqualTo(LyricsState.Error("boom"))
    }

    @Test
    fun `a miss after a failure still reports the failure, not empty`() = runTest {
        val calls = mutableListOf<String>()
        val result = repo(failed, missed, calls).find("Song", "Artist", 1000L)
        assertThat(result).isEqualTo(LyricsState.Error("boom"))
    }
}
