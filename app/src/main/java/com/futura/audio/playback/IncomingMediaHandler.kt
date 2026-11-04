package com.futura.audio.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.futura.audio.data.SavedSourcesStore
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import com.futura.audio.pipeline.compliant.MusicSourceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns audio and video files into library entries and plays them. Used for
 * files shared from other apps (Xender, file managers) and for files the user
 * picks in the Library. Anything that is not audio or video is ignored.
 */
@Singleton
class IncomingMediaHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicSourceRepository,
    private val store: SavedSourcesStore
) {

    /** The media URI carried by a VIEW or SEND intent, or null if there is none. */
    fun uriFrom(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { isMedia(intent.type, it) }
            Intent.ACTION_SEND -> {
                val type = intent.type
                if (type != null && isMediaType(type)) streamExtra(intent) else null
            }
            else -> null
        }
    }

    /** A library entry for [uri]. Video is detected from the provider's type or the file extension. */
    fun itemFor(uri: Uri): MediaItem {
        val video = isVideo(contentType(uri), uri)
        return MediaItem(
            id = uri.toString(),
            title = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Local media",
            artist = null,
            streamUrl = uri.toString(),
            artworkUrl = null,
            sourceName = "Local file",
            subcategory = "local.file",
            route = PlaybackRoute.EXOPLAYER_DIRECT,
            category = PipelineCategory.COMPLIANT_STANDARD,
            isVideo = video
        )
    }

    /**
     * Saves [uri] to the Library and starts playback through ExoPlayer.
     * Returns true when the file is video, so the caller can show the picture.
     */
    suspend fun play(uri: Uri): Boolean {
        val item = itemFor(uri)
        persistReadAccess(uri)
        addToLibrary(listOf(item))
        repository.playDirect(listOf(item))
        return item.isVideo
    }

    /** Adds [items] to the Library, newest first, replacing any entry with the same id. */
    suspend fun addToLibrary(items: List<MediaItem>) {
        val ids = items.map { it.id }.toSet()
        val existing = store.load().filterNot { it.id in ids }
        store.save(items + existing)
    }

    /** Keeps read access across restarts where the provider allows it. */
    fun persistReadAccess(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // The sender gave no persistable permission; access lasts for this session only.
        }
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun isMedia(type: String?, uri: Uri): Boolean {
        if (type != null && isMediaType(type)) return true
        return extension(uri) in MEDIA_EXTENSIONS
    }

    private fun isMediaType(type: String): Boolean =
        type.startsWith("audio/") || type.startsWith("video/") || type == "application/ogg"

    private fun isVideo(type: String?, uri: Uri): Boolean {
        if (type != null) return type.startsWith("video/")
        return extension(uri) in VIDEO_EXTENSIONS
    }

    private fun extension(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()

    private fun contentType(uri: Uri): String? =
        try {
            context.contentResolver.getType(uri)
        } catch (e: SecurityException) {
            null
        }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: SecurityException) {
            null
        }
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "m2ts")
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "mka"
        )
        val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS
    }
}
