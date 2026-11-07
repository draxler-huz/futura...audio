package com.futura.sources

/**
 * Tier separates the sources that ship first from the ones that are added
 * later. Tier 1 sources are stable, documented, and have clear licenses.
 * Tier 2 sources are disabled by default and often need a key, scraping,
 * or a partner agreement.
 */
enum class Tier { TIER_1, TIER_2 }

/** The media kind a source returns. Null means the source is metadata-only. */
enum class SourceFileType { AUDIO, VIDEO }

/**
 * Why a source is, or is not, being queried. Drives the sources sheet so a
 * key-based source that is waiting for its key can say so.
 */
enum class SourceStatus {
    /** Wanted and ready to query. */
    ON,

    /** Wanted, but its API key is not configured in this build. */
    NEEDS_KEY,

    /** Turned off, either by default or by the user. */
    OFF
}

/**
 * Static description of one media source. The registry holds these; the
 * per-user on/off state lives in DataStore and is merged on top at read
 * time (see [com.futura.sources.data.SourcePreferences]).
 */
data class SourceConfig(
    val id: String,
    val displayName: String,
    val fileType: SourceFileType?,
    val tier: Tier,
    val enabled: Boolean,
    val requiresKey: Boolean,
    val keyName: String? = null,
    val requiresScraping: Boolean = false,
    val commercialUseAllowed: Boolean,
    val licenseName: String,
    val rateLimitPerMinute: Int,
    val notes: String,
    /** True when the source returns live streams that cannot be downloaded. */
    val liveStreamOnly: Boolean = false,
    /** True when results are links to another app, never playable in Futura. */
    val linkOutOnly: Boolean = false
) {
    /**
     * True when the source should be queried.
     *
     * [enabled] (or an explicit [userOverride] from
     * [com.futura.sources.data.SourcePreferences]) says whether the source
     * is wanted; a key-requiring source additionally needs its key present
     * in [configuredKeys]. So pasting a key into `local.properties` switches
     * a key-based source on, and turning one off in settings keeps it off.
     */
    fun isUsable(configuredKeys: Set<String>, userOverride: Boolean? = null): Boolean {
        if (!(userOverride ?: enabled)) return false
        if (!requiresKey) return true
        return keyName != null && keyName in configuredKeys
    }

    /** True when this source needs a key that is not configured yet. */
    fun isBlockedOnMissingKey(configuredKeys: Set<String>): Boolean =
        requiresKey && keyName != null && keyName !in configuredKeys
}

/**
 * One search result from any source. Kept separate from the playback
 * [com.futura.audio.domain.MediaItem] so a search hit can be shown before
 * (or without) becoming a playable/downloadable item.
 */
data class SourceMediaItem(
    val id: String,
    val title: String,
    val creator: String?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
    val streamUrl: String?,
    val downloadUrl: String?,
    val license: SourceLicense,
    val sourceId: String,
    val sourceName: String,
    val fileType: SourceFileType,
    val sourcePageUrl: String,
    val downloadable: Boolean = true,
    val watchUrl: String? = null,
    val isLive: Boolean = false,
    val bpm: Int? = null,
    val musicalKey: String? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
    }

    /** Text to show when the license needs attribution. */
    fun attributionString(): String = buildString {
        append('"').append(title).append('"')
        if (!creator.isNullOrBlank()) append(" by ").append(creator)
        append(" is licensed under ").append(license.label)
        append(". Source: ").append(sourceName)
        append(" (").append(sourcePageUrl).append(").")
    }
}
