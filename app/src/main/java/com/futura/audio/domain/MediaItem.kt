package com.futura.audio.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import java.util.UUID

// PipelineCategory is declared once, in PipelineCategory.kt.

enum class PlaybackRoute { 
    EXOPLAYER_DIRECT, 
    WEB_EMBED_CONTAINER 
}

@JvmInline
value class MediaId(val value: String) {
    init { require(value.isNotBlank()) }
    companion object { fun random() = MediaId(UUID.randomUUID().toString()) }
}

@JvmInline
value class Subcategory(val value: String)

data class MediaItem(
    val id: MediaId,
    val title: String,
    val artist: String? = null,
    val streamUrl: String,
    val artworkUrl: String? = null,
    val sourceName: String,
    val subcategory: Subcategory,
    val duration: Duration? = null,
    val category: PipelineCategory,
    val route: PlaybackRoute,
    val isVideo: Boolean = false
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(streamUrl.isNotBlank()) { "streamUrl must not be blank" }
        require(sourceName.isNotBlank()) { "sourceName must not be blank" }
        require(duration == null || duration >= ZERO) { "duration must be >= 0" }
    }

    val durationMs: Long get() = duration?.inWholeMilliseconds ?: 0L
}
