package com.futura.lyrics

sealed interface LyricsState {
    data object Loading : LyricsState
    data object Empty : LyricsState // No lyrics found
    data class Success(val plainLyrics: String, val syncedLyrics: String?) : LyricsState
    data class Error(val message: String) : LyricsState
}
