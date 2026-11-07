package com.futura.sources.net

import com.futura.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the source API keys baked into BuildConfig. A key that is still the
 * placeholder "YOUR_KEY" counts as not configured, so its source stays off
 * rather than failing at request time.
 */
@Singleton
class SourceKeys @Inject constructor() {

    /** All keys that hold a real value in this build. */
    val configured: Set<String> = buildSet {
        addIfReal("JAMENDO_CLIENT_ID", BuildConfig.JAMENDO_CLIENT_ID)
        addIfReal("FREESOUND_TOKEN", BuildConfig.FREESOUND_TOKEN)
        addIfReal("FMA_API_KEY", BuildConfig.FMA_API_KEY)
        addIfReal("PEXELS_API_KEY", BuildConfig.PEXELS_API_KEY)
        addIfReal("PIXABAY_API_KEY", BuildConfig.PIXABAY_API_KEY)
        addIfReal("FLICKR_API_KEY", BuildConfig.FLICKR_API_KEY)
        addIfReal("YOUTUBE_API_KEY", BuildConfig.YOUTUBE_API_KEY)
        addIfReal("THEAUDIODB_API_KEY", BuildConfig.THEAUDIODB_API_KEY)
        addIfReal("PODCASTINDEX_API_KEY", BuildConfig.PODCASTINDEX_API_KEY)
        addIfReal("PODCASTINDEX_API_SECRET", BuildConfig.PODCASTINDEX_API_SECRET)
        addIfReal("GENIUS_ACCESS_TOKEN", BuildConfig.GENIUS_ACCESS_TOKEN)
        addIfReal("BANDSINTOWN_APP_ID", BuildConfig.BANDSINTOWN_APP_ID)
        addIfReal("TICKETMASTER_API_KEY", BuildConfig.TICKETMASTER_API_KEY)
    }

    /** The value for [name], or null when it is missing or still a placeholder. */
    fun value(name: String): String? {
        val raw = when (name) {
            "JAMENDO_CLIENT_ID" -> BuildConfig.JAMENDO_CLIENT_ID
            "FREESOUND_TOKEN" -> BuildConfig.FREESOUND_TOKEN
            "FMA_API_KEY" -> BuildConfig.FMA_API_KEY
            "PEXELS_API_KEY" -> BuildConfig.PEXELS_API_KEY
            "PIXABAY_API_KEY" -> BuildConfig.PIXABAY_API_KEY
            "FLICKR_API_KEY" -> BuildConfig.FLICKR_API_KEY
            "YOUTUBE_API_KEY" -> BuildConfig.YOUTUBE_API_KEY
            "THEAUDIODB_API_KEY" -> BuildConfig.THEAUDIODB_API_KEY
            "PODCASTINDEX_API_KEY" -> BuildConfig.PODCASTINDEX_API_KEY
            "PODCASTINDEX_API_SECRET" -> BuildConfig.PODCASTINDEX_API_SECRET
            "GENIUS_ACCESS_TOKEN" -> BuildConfig.GENIUS_ACCESS_TOKEN
            "BANDSINTOWN_APP_ID" -> BuildConfig.BANDSINTOWN_APP_ID
            "TICKETMASTER_API_KEY" -> BuildConfig.TICKETMASTER_API_KEY
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() && it != PLACEHOLDER }
    }

    private fun MutableSet<String>.addIfReal(name: String, value: String) {
        if (value.isNotBlank() && value != PLACEHOLDER) add(name)
    }

    private companion object {
        const val PLACEHOLDER = "YOUR_KEY"
    }
}
