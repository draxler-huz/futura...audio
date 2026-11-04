package com.futura.audio.domain

enum class PlaybackRoute {
    /** Direct audio URL, played by ExoPlayer. */
    EXOPLAYER_DIRECT,

    /** Web page or embed, shown in the WebView container. Never sent to ExoPlayer. */
    WEB_EMBED_CONTAINER
}

data class MediaItem(
    val id: String,
    val title: String,
    val artist: String?,
    val streamUrl: String,       // Direct audio enclosure or web fallback URL
    val artworkUrl: String?,
    val sourceName: String,      // e.g., "Podcast RSS", "Official Web Embed", "Direct Stream"
    val subcategory: String,     // e.g., "syndication.rss", "metadata.opengraph"
    val durationMs: Long = 0L,
    val route: PlaybackRoute = PlaybackRoute.EXOPLAYER_DIRECT,
    val category: PipelineCategory = PipelineCategory.COMPLIANT_STANDARD,  // UI filter and accent color
    val isVideo: Boolean = false                                            // played with a picture
)
