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
 * Plays audio opened or shared from another app (Xender, a file manager, a
 * browser). Only audio content is accepted; anything else is ignored.
 * The file is added to the Library so it can be played again.
 */
@Singleton
class IncomingMediaHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicSourceRepository,
    private val store: SavedSourcesStore
) {

    /** The audio URI carried by a VIEW or SEND intent, or null if there is none. */
    fun uriFrom(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { isAudio(intent.type, it) }
            Intent.ACTION_SEND -> {
                val type = intent.type
                if (type != null && isAudioType(type)) streamExtra(intent) else null
            }
            else -> null
        }
    }

    /** Saves the file to the Library, then plays it through ExoPlayer on the main thread. */
    suspend fun play(uri: Uri) {
        val item = MediaItem(
            id = uri.toString(),
            title = displayName(uri)?.substringBeforeLast('.') ?: "Shared audio",
            artist = null,
            streamUrl = uri.toString(),
            artworkUrl = null,
            sourceName = "Shared file",
            subcategory = "shared.local",
            route = PlaybackRoute.EXOPLAYER_DIRECT,
            category = PipelineCategory.COMPLIANT_STANDARD
        )
        persistReadAccess(uri)
        val existing = store.load().filterNot { it.id == item.id }
        store.save(listOf(item) + existing)
        repository.playDirect(listOf(item))
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun isAudio(type: String?, uri: Uri): Boolean {
        if (type != null && isAudioType(type)) return true
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in AUDIO_EXTENSIONS
    }

    private fun isAudioType(type: String): Boolean =
        type.startsWith("audio/") || type == "application/ogg"

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: SecurityException) {
            null
        }
    }

    /** Keeps read access across restarts where the provider allows it; otherwise playback ends with the share. */
    private fun persistReadAccess(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // The sender did not grant a persistable permission; read access lasts for this session only.
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "3gp", "mka"
        )
    }
}
