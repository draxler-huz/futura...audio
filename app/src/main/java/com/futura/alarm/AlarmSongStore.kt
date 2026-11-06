package com.futura.alarm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Songs the user picks as an alarm sound. A picked song is copied into app
 * storage, so the alarm can play it after the picker's access has ended and
 * without relying on the original file still being there.
 */
object AlarmSongStore {

    const val SCHEME = "file://"
    private const val DIR = "alarm_songs"
    private const val MAX_BYTES = 100L * 1024 * 1024
    private const val BUFFER_BYTES = 8 * 1024

    /** Copies [uri] into app storage and returns its file URI. Throws if it cannot be read or is too large. */
    fun importSong(context: Context, uri: Uri): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.${extensionOf(context, uri)}")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open that song.")
            input.use { src -> target.outputStream().use { out -> copyLimited(src, out) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return Uri.fromFile(target).toString()
    }

    /** The stored song for [soundUri], or null when it is not a song in app storage. */
    fun fileFor(context: Context, soundUri: String): File? {
        if (!soundUri.startsWith(SCHEME)) return null
        val path = Uri.parse(soundUri).path ?: return null
        val dir = File(context.filesDir, DIR).canonicalFile
        val file = try {
            File(path).canonicalFile
        } catch (e: IOException) {
            return null
        }
        return file.takeIf { it.isFile && it.path.startsWith(dir.path + File.separator) }
    }

    private fun copyLimited(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw IOException("That song is larger than 100 MB.")
            out.write(buffer, 0, read)
        }
    }

    /** Extension from the picked file's name, or "audio" when it is missing or not a plain word. */
    private fun extensionOf(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: SecurityException) {
            null
        }
        val ext = name?.substringAfterLast('.', "").orEmpty().lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else "audio"
    }
}
