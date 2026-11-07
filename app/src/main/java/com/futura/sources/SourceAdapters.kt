package com.futura.sources

import com.futura.sources.adapters.ArchiveAudioSource
import com.futura.sources.adapters.ArchiveVideoSource
import com.futura.sources.adapters.AudiusSource
import com.futura.sources.adapters.CcMixterSource
import com.futura.sources.adapters.FreeToUseSource
import com.futura.sources.adapters.IncompetechSource
import com.futura.sources.adapters.JamendoSource
import com.futura.sources.adapters.OpenGameArtSource
import com.futura.sources.adapters.PexelsSource
import com.futura.sources.adapters.PixabaySource
import com.futura.sources.adapters.RadioBrowserSource
import com.futura.sources.adapters.WikimediaAudioSource
import com.futura.sources.adapters.WikimediaVideoSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up the adapter for a source id. Adapters are injected once and
 * indexed by their own [MediaSource.config] id, so adding an adapter only
 * means adding it to this constructor.
 */
@Singleton
class SourceAdapters @Inject constructor(
    audius: AudiusSource,
    jamendo: JamendoSource,
    archiveAudio: ArchiveAudioSource,
    wikimediaAudio: WikimediaAudioSource,
    radioBrowser: RadioBrowserSource,
    ccMixter: CcMixterSource,
    incompetech: IncompetechSource,
    openGameArt: OpenGameArtSource,
    freeToUse: FreeToUseSource,
    pexels: PexelsSource,
    pixabay: PixabaySource,
    archiveVideo: ArchiveVideoSource,
    wikimediaVideo: WikimediaVideoSource
) {

    private val byId: Map<String, MediaSource> = listOf<MediaSource>(
        audius, jamendo, archiveAudio, wikimediaAudio, radioBrowser,
        ccMixter, incompetech, openGameArt, freeToUse, pexels, pixabay,
        archiveVideo, wikimediaVideo
    ).associateBy { it.config.id }

    fun forId(id: String): MediaSource? = byId[id]

    val all: Collection<MediaSource> get() = byId.values
}
