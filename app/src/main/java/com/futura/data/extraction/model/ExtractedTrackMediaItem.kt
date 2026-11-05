// app/src/main/java/com/futura/data/extraction/model/ExtractedTrackMediaItem.kt
package com.futura.data.extraction.model

import com.futura.audio.domain.MediaId
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.domain.Subcategory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adapters between the extraction model and the core [MediaItem].
 *
 * [ExtractedTrack] is the source of truth. It keeps the file path, size,
 * album, and all telemetry, which [MediaItem] does not carry. The core model
 * is only a playback projection: [toMediaItem] builds one for the player or
 * the hub, and [applyTo] writes back the fields a [MediaItem] can change.
 * Nothing that [MediaItem] lacks is ever dropped from the track.
 */

/** Projects a track into the core model for playback. Lossy by design; see the file header. */
fun ExtractedTrack.toMediaItem(): MediaItem = MediaItem(
    id = MediaId(id),
    title = title ?: fileName,
    artist = artist,
    streamUrl = File(filePath).toURI().toString(),
    artworkUrl = albumArtPath?.let { File(it).toURI().toString() },
    sourceName = "Local library",
    subcategory = Subcategory("extraction.${category.name.lowercase()}"),
    duration = durationMs.milliseconds,
    category = PipelineCategory.COMPLIANT_STANDARD,
    route = PlaybackRoute.EXOPLAYER_DIRECT,
    isVideo = isVideo
)

/**
 * Writes back the fields a [MediaItem] can change. Path, size, album, telemetry,
 * and flags are taken from [base] unchanged.
 */
fun MediaItem.applyTo(base: ExtractedTrack): ExtractedTrack = base.copy(
    title = title,
    artist = artist,
    durationMs = duration?.inWholeMilliseconds ?: base.durationMs
)
