package com.futura.sources

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The catalogue of every media source the app knows about.
 *
 * This object holds only *static* metadata. Whether a source is switched on
 * for this user lives in DataStore and is merged on top by
 * [com.futura.sources.data.SourcePreferences]; [withOverrides] applies that
 * overlay.
 *
 * Tier 1 sources ship enabled. Tier 2 sources are declared here with
 * `enabled = false` so the UI can list them, but nothing calls them until
 * the user (or a later change) turns them on.
 */
@Singleton
class SourceRegistry @Inject constructor() {

    /** Every known source, in display order. */
    val all: List<SourceConfig> = TIER_1 + TIER_2

    fun byId(id: String): SourceConfig? = all.firstOrNull { it.id == id }

    /** Tier 1 sources only. */
    val tier1: List<SourceConfig> get() = TIER_1

    /** Tier 2 sources only. */
    val tier2: List<SourceConfig> get() = TIER_2

    /**
     * Returns [all] with each source's `enabled` flag replaced by the user's
     * stored choice where one exists.
     */
    fun withOverrides(overrides: Map<String, Boolean>): List<SourceConfig> =
        all.map { config ->
            val override = overrides[config.id]
            if (override == null) config else config.copy(enabled = override)
        }

    /**
     * The sources that should actually be queried: wanted (registry default
     * or the user's stored choice) and usable given the configured keys.
     */
    fun enabledSources(
        overrides: Map<String, Boolean>,
        configuredKeys: Set<String>
    ): List<SourceConfig> =
        all.filter { it.isUsable(configuredKeys, overrides[it.id]) }

    /**
     * Sources that are wanted and have no key requirement blocking them,
     * plus the ones waiting on a key. Used by the sources sheet so the UI
     * can explain why a source is inactive.
     */
    fun statusFor(
        overrides: Map<String, Boolean>,
        configuredKeys: Set<String>
    ): Map<String, SourceStatus> =
        all.associate { config ->
            val wanted = overrides[config.id] ?: config.enabled
            val status = when {
                !wanted -> SourceStatus.OFF
                config.isBlockedOnMissingKey(configuredKeys) -> SourceStatus.NEEDS_KEY
                else -> SourceStatus.ON
            }
            config.id to status
        }

    companion object {

        /** Static lookup for adapters that only need their own config. */
        fun byIdStatic(id: String): SourceConfig? =
            (TIER_1 + TIER_2).firstOrNull { it.id == id }

        // ── Tier 1 — audio ───────────────────────────────────────────────
        private val TIER_1: List<SourceConfig> = listOf(
            SourceConfig(
                id = "audius",
                displayName = "Audius",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                commercialUseAllowed = true,
                licenseName = "Artist-defined",
                rateLimitPerMinute = 30,
                notes = "No key. Discover a live host from api.audius.co."
            ),
            SourceConfig(
                id = "jamendo",
                displayName = "Jamendo",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = true,
                keyName = "JAMENDO_CLIENT_ID",
                commercialUseAllowed = true,
                licenseName = "CC / Jamendo",
                rateLimitPerMinute = 30,
                notes = "client_id required. License comes from include=licenses."
            ),
            SourceConfig(
                id = "archive_audio",
                displayName = "Internet Archive",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                commercialUseAllowed = true,
                licenseName = "CC / Public Domain",
                rateLimitPerMinute = 20,
                notes = "mediatype:audio, netlabels and librivox collections."
            ),
            SourceConfig(
                id = "wikimedia_audio",
                displayName = "Wikimedia Commons",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                commercialUseAllowed = true,
                licenseName = "CC / Public Domain",
                rateLimitPerMinute = 20,
                notes = "MediaWiki search API. License read from file page."
            ),
            SourceConfig(
                id = "radiobrowser",
                displayName = "Radio Browser",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                commercialUseAllowed = false,
                licenseName = "Radio (streaming)",
                rateLimitPerMinute = 20,
                notes = "Live streams only. Playable, never downloadable.",
                liveStreamOnly = true
            ),
            SourceConfig(
                id = "ccmixter",
                displayName = "ccMixter",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                commercialUseAllowed = true,
                licenseName = "CC",
                rateLimitPerMinute = 20,
                notes = "JSON query API. License from license_name."
            ),
            SourceConfig(
                id = "incompetech",
                displayName = "Incompetech",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                requiresScraping = true,
                commercialUseAllowed = true,
                licenseName = "CC-BY 4.0",
                rateLimitPerMinute = 10,
                notes = "RSS feed + genre pages. Kevin MacLeod, attribution required."
            ),
            SourceConfig(
                id = "opengameart",
                displayName = "OpenGameArt",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                requiresScraping = true,
                commercialUseAllowed = true,
                licenseName = "CC0 / CC-BY / CC-BY-SA",
                rateLimitPerMinute = 10,
                notes = "Scrape music search results; license per submission."
            ),
            SourceConfig(
                id = "freetouse",
                displayName = "FreeToUse",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = false,
                requiresKey = false,
                requiresScraping = true,
                commercialUseAllowed = true,
                licenseName = "FreeToUse License",
                rateLimitPerMinute = 10,
                notes = "Scrape the site's embedded JSON catalogue. Off until the endpoint is verified."
            ),
            SourceConfig(
                id = "fma",
                displayName = "Free Music Archive",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = false,
                requiresKey = true,
                keyName = "FMA_API_KEY",
                commercialUseAllowed = true,
                licenseName = "CC",
                rateLimitPerMinute = 20,
                notes = "Needs FMA_API_KEY and an adapter. Off until both exist."
            ),
            SourceConfig(
                id = "freesound",
                displayName = "Freesound",
                fileType = SourceFileType.AUDIO,
                tier = Tier.TIER_1,
                enabled = false,
                requiresKey = true,
                keyName = "FREESOUND_TOKEN",
                commercialUseAllowed = true,
                licenseName = "CC",
                rateLimitPerMinute = 20,
                notes = "Needs FREESOUND_TOKEN and an adapter. Off until both exist."
            ),

            // ── Tier 1 — video ───────────────────────────────────────────
            SourceConfig(
                id = "pexels",
                displayName = "Pexels",
                fileType = SourceFileType.VIDEO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = true,
                keyName = "PEXELS_API_KEY",
                commercialUseAllowed = true,
                licenseName = "Pexels License",
                rateLimitPerMinute = 20,
                notes = "Needs PEXELS_API_KEY. Free to use, no attribution needed."
            ),
            SourceConfig(
                id = "pixabay",
                displayName = "Pixabay",
                fileType = SourceFileType.VIDEO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = true,
                keyName = "PIXABAY_API_KEY",
                commercialUseAllowed = true,
                licenseName = "Pixabay License",
                rateLimitPerMinute = 20,
                notes = "Needs PIXABAY_API_KEY. Free to use, no attribution needed."
            ),
            SourceConfig(
                id = "archive_video",
                displayName = "Internet Archive Video",
                fileType = SourceFileType.VIDEO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                commercialUseAllowed = true,
                licenseName = "CC / Public Domain",
                rateLimitPerMinute = 20,
                notes = "Prelinger and opensource_movies collections."
            ),
            SourceConfig(
                id = "wikimedia_video",
                displayName = "Wikimedia Commons Video",
                fileType = SourceFileType.VIDEO,
                tier = Tier.TIER_1,
                enabled = true,
                requiresKey = false,
                commercialUseAllowed = true,
                licenseName = "CC / Public Domain",
                rateLimitPerMinute = 20,
                notes = "MediaWiki search API for video files."
            )
        )

        // ── Tier 2 — declared but off by default ─────────────────────────
        // These are listed so the Sources sheet can show them. Adapters are
        // added incrementally; each entry documents why it is off.
        private val TIER_2: List<SourceConfig> = listOf(
            // Audio — usable APIs
            tier2("bandcamp", "Bandcamp", SourceFileType.AUDIO, "No public API. Link-out only.", linkOut = true),
            tier2("soundcloud", "SoundCloud", SourceFileType.AUDIO, "API closed to new apps. Link-out only.", linkOut = true),
            tier2("mixcloud", "Mixcloud", SourceFileType.AUDIO, "Previews only; downloads unavailable."),
            tier2("openverse", "Openverse", SourceFileType.AUDIO, "Keyless CC aggregator. High-value; add first."),
            tier2("librivox", "LibriVox", SourceFileType.AUDIO, "Public-domain audiobooks, chapters as MP3."),
            tier2("podcastindex", "Podcast Index", SourceFileType.AUDIO, "Key required. RSS enclosures downloadable."),
            tier2("bbcsfx", "BBC Sound Effects", SourceFileType.AUDIO, "RemArc license: personal/educational only.", commercial = false),

            // Audio — metadata only
            meta("spotify_meta", "Spotify (metadata)", "Client-credentials OAuth. No audio playback.", keyName = "SPOTIFY_CLIENT_ID"),
            meta("deezer_meta", "Deezer (metadata)", "30s previews allowed; no download.", keyName = "DEEZER_APP_ID"),
            meta("itunes_meta", "iTunes Search (metadata)", "Keyless. 30s previews; no download."),
            meta("youtube_meta", "YouTube (metadata)", "Search metadata only; open in YouTube. Never download.", keyName = "YOUTUBE_API_KEY"),
            meta("lastfm_meta", "Last.fm (metadata)", "Key required. Discovery only.", keyName = "LASTFM_API_KEY"),
            meta("discogs_meta", "Discogs (metadata)", "Key required. Metadata only.", keyName = "DISCOGS_TOKEN"),
            meta("musicbrainz_meta", "MusicBrainz (metadata)", "Keyless. Enrichment; 1 req/sec with UA."),
            meta("theaudiodb_meta", "TheAudioDB (metadata)", "Free-tier key. Artwork enrichment.", keyName = "THEAUDIODB_API_KEY"),
            meta("coverart_meta", "Cover Art Archive (metadata)", "Keyless. Artwork by MusicBrainz release id."),
            meta("lrclib_meta", "LRCLIB (metadata)", "Keyless. Time-synced lyrics. Primary provider."),
            meta("lyricsovh_meta", "Lyrics.ovh (metadata)", "Keyless. Plain lyrics fallback."),
            meta("genius_meta", "Genius (metadata)", "Key required. Metadata and annotations only; the API does not serve lyrics.", keyName = "GENIUS_ACCESS_TOKEN"),

            // Video — scraped
            video2("mixkit", "Mixkit", "Mixkit License (commercial allowed)."),
            video2("coverr", "Coverr", "Coverr License (commercial allowed)."),
            video2("videvo", "Videvo", "Videvo license varies per asset."),
            video2("dareful", "Dareful", "CC0."),
            video2("mazwai", "Mazwai", "CC-BY 3.0 (attribution required)."),
            video2("nasa", "NASA Image & Video", "Mostly public domain."),
            video2("loc", "Library of Congress", "Public domain depending on item."),
            video2("flickr", "Flickr CC", "Key required. CC-licensed photos/video.")
        )

        private fun tier2(
            id: String,
            name: String,
            type: SourceFileType,
            notes: String,
            commercial: Boolean = true,
            linkOut: Boolean = false
        ) = SourceConfig(
            id = id,
            displayName = name,
            fileType = type,
            tier = Tier.TIER_2,
            enabled = false,
            requiresKey = false,
            requiresScraping = true,
            commercialUseAllowed = commercial,
            licenseName = "See source",
            rateLimitPerMinute = 10,
            notes = notes,
            linkOutOnly = linkOut
        )

        private fun meta(
            id: String,
            name: String,
            notes: String,
            keyName: String? = null
        ) = SourceConfig(
            id = id,
            displayName = name,
            fileType = null,
            tier = Tier.TIER_2,
            enabled = false,
            requiresKey = keyName != null,
            keyName = keyName,
            commercialUseAllowed = false,
            licenseName = "Metadata only",
            rateLimitPerMinute = 30,
            notes = notes,
            linkOutOnly = true
        )

        private fun video2(id: String, name: String, notes: String) = SourceConfig(
            id = id,
            displayName = name,
            fileType = SourceFileType.VIDEO,
            tier = Tier.TIER_2,
            enabled = false,
            requiresKey = false,
            requiresScraping = true,
            commercialUseAllowed = true,
            licenseName = "See source",
            rateLimitPerMinute = 10,
            notes = notes
        )
    }
}
