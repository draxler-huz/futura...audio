package com.futura.audio.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.domain.PlaybackRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists the music and video that the phone has indexed in MediaStore. The
 * caller must hold READ_MEDIA_AUDIO / READ_MEDIA_VIDEO (or READ_EXTERNAL_STORAGE
 * below Android 13) before calling [scan].
 */
@Singleton
class DeviceMediaScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun scan(): List<MediaItem> = withContext(Dispatchers.IO) {
        query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, video = false) +
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true)
    }

    private fun query(collection: Uri, video: Boolean): List<MediaItem> {
        val projection = if (video) {
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE)
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.TITLE,
                MediaStore.Audio.AudioColumns.ARTIST
            )
        }
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val artistIndex = if (video) -1 else cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(nameIndex)?.substringBeforeLast('.')
                    ?: "Device media"
                items += MediaItem(
                    id = uri.toString(),
                    title = title,
                    artist = if (artistIndex >= 0) cursor.getString(artistIndex)?.takeIf { it.isNotBlank() } else null,
                    streamUrl = uri.toString(),
                    artworkUrl = null,
                    sourceName = "Device media",
                    subcategory = if (video) "device.video" else "device.audio",
                    route = PlaybackRoute.EXOPLAYER_DIRECT,
                    category = PipelineCategory.COMPLIANT_STANDARD,
                    isVideo = video
                )
            }
        }
        return items
    }
}
