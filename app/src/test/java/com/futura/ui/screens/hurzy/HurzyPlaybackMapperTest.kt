package com.futura.ui.screens.hurzy

import com.futura.audio.domain.PlaybackRoute
import com.futura.sources.SourceFileType
import com.futura.sources.SourceLicense
import com.futura.sources.SourceMediaItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The mapper decides what the Hurzy feed can show and play, so the two rules
 * that matter are pinned here: only items with a resolved stream URL are
 * playable, and a playable item maps onto a direct ExoPlayer route.
 */
class HurzyPlaybackMapperTest {

    private fun item(
        id: String = "audius:1",
        streamUrl: String? = "https://example.com/a.mp3",
        isLive: Boolean = false
    ) = SourceMediaItem(
        id = id,
        title = "Night Drive",
        creator = "Someone",
        durationSec = 200,
        thumbnailUrl = "https://example.com/art.jpg",
        streamUrl = streamUrl,
        downloadUrl = null,
        license = SourceLicense.SOURCE_LICENSE,
        sourceId = "audius",
        sourceName = "Audius",
        fileType = SourceFileType.AUDIO,
        sourcePageUrl = "https://audius.co/track/1",
        isLive = isLive
    )

    @Test
    fun `item with a stream url is playable`() {
        assertThat(HurzyPlaybackMapper.isPlayable(item())).isTrue()
    }

    @Test
    fun `item without a stream url is not playable`() {
        assertThat(HurzyPlaybackMapper.isPlayable(item(streamUrl = null))).isFalse()
        assertThat(HurzyPlaybackMapper.isPlayable(item(streamUrl = "   "))).isFalse()
    }

    @Test
    fun `live stream is not treated as a track`() {
        assertThat(HurzyPlaybackMapper.isPlayable(item(isLive = true))).isFalse()
    }

    @Test
    fun `playable item maps onto a direct ExoPlayer route`() {
        val mapped = HurzyPlaybackMapper.toMediaItem(item())
        assertThat(mapped.title).isEqualTo("Night Drive")
        assertThat(mapped.artist).isEqualTo("Someone")
        assertThat(mapped.streamUrl).isEqualTo("https://example.com/a.mp3")
        assertThat(mapped.artworkUrl).isEqualTo("https://example.com/art.jpg")
        assertThat(mapped.route).isEqualTo(PlaybackRoute.EXOPLAYER_DIRECT)
        assertThat(mapped.durationMs).isEqualTo(200_000L)
    }
}
