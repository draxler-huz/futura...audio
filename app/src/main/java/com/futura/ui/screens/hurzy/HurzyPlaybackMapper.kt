package com.futura.ui.screens.hurzy

import com.futura.audio.domain.MediaId
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.domain.Subcategory
import com.futura.sources.SourceMediaItem
import kotlin.time.Duration.Companion.seconds

/**
 * Turns a search hit into something the player can queue.
 *
 * Only items with a real [SourceMediaItem.streamUrl] are playable straight
 * away. Internet Archive and similar sources resolve their file lazily, so
 * they are skipped here rather than failing at playback time.
 */
object HurzyPlaybackMapper {

    /** True when [item] can be handed to ExoPlayer without another lookup. */
    fun isPlayable(item: SourceMediaItem): Boolean =
        !item.streamUrl.isNullOrBlank() && !item.isLive

    fun toMediaItem(item: SourceMediaItem): MediaItem = MediaItem(
        id = MediaId(item.id),
        title = item.title,
        artist = item.creator,
        streamUrl = item.streamUrl.orEmpty(),
        artworkUrl = item.thumbnailUrl,
        sourceName = item.sourceName,
        subcategory = Subcategory(item.sourceName),
        duration = item.durationSec?.takeIf { it > 0 }?.seconds,
        category = PipelineCategory.COMPLIANT_STANDARD,
        route = PlaybackRoute.EXOPLAYER_DIRECT,
        isVideo = false
    )
}
