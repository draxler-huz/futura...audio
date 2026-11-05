// app/src/main/java/com/futura/data/extraction/AlbumArtExtractor.kt
package com.futura.data.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts embedded album art from audio files into a disk cache.
 *
 * Feature 6. Reads the embedded picture from the ID3/FLAC/MP4 tag via
 * [MediaMetadataRetriever], scales it down, and writes it as WebP under
 * `context.cacheDir/album_art/<trackId>.webp`.
 *
 * Three target sizes:
 *   • 128 px  — list thumbnail (song rows, album grid)
 *   • 512 px  — Now Playing hero
 *   • 1024 px — lockscreen / high-res display
 *
 * The extractor writes all three sizes for every track. Disk cost is
 * ~40 KB per track (all three sizes), acceptable for libraries up to
 * ~10,000 tracks before users notice cache pressure — and Coil / the
 * system evicts cache under pressure.
 *
 * GROWTH: the tree's intended `data/cover/CoverArtCache.kt` will
 * eventually own this cache and add an LRU. This extractor is a
 * standalone first-pass that the cache can adopt without changes.
 *
 * Rule 6: every per-file op wrapped; failure returns null and does not
 * propagate.
 */
@Singleton
class AlbumArtExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "AlbumArtExtractor"

    data class ArtworkPaths(
        val thumbnailPath: String?,
        val mediumPath: String?,
        val highResPath: String?
    )

    /**
     * Extract embedded art for [audioFile], save three WebP sizes, and
     * return their paths.
     *
     * @return [ArtworkPaths] with any missing sizes as null, or null if
     *         the file has no embedded art or cannot be read.
     */
    suspend fun extract(
        audioFile: File,
        trackId: String
    ): ArtworkPaths? = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists() || !audioFile.canRead()) return@withContext null

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(audioFile.absolutePath)
                val raw: ByteArray? = retriever.embeddedPicture
                if (raw == null || raw.isEmpty()) return@withContext null

                // Read the size first, then decode no larger than the biggest target (1024 px).
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                if (maxSide <= 0) return@withContext null
                var sample = 1
                while (maxSide / (sample * 2) >= 1024) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val original = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts)
                    ?: return@withContext null

                val dir = cacheDir()
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(tag, "Cannot create album art cache dir")
                    return@withContext null
                }

                val thumb = writeScaled(original, dir, trackId, "thumb", 128)
                val medium = writeScaled(original, dir, trackId, "medium", 512)
                val highRes = writeScaled(original, dir, trackId, "high", 1024)
                // scaleToTarget returns the source itself when it already fits, so recycle each bitmap once.
                listOfNotNull(original, thumb, medium, highRes).distinct().forEach { it.recycle() }

                ArtworkPaths(
                    thumbnailPath = thumb?.let { pathFor(dir, trackId, "thumb") },
                    mediumPath = medium?.let { pathFor(dir, trackId, "medium") },
                    highResPath = highRes?.let { pathFor(dir, trackId, "high") }
                )
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "extract failed for ${audioFile.name}", e)
            null
        }
    }

    /** Check whether cached art already exists for [trackId]. */
    fun isCached(trackId: String): Boolean {
        val dir = cacheDir()
        return File(dir, "${trackId}_thumb.webp").exists() ||
               File(dir, "${trackId}_medium.webp").exists()
    }

    fun cacheDir(): File = File(context.cacheDir, "album_art")

    // ── Internals ─────────────────────────────────────────────────

    private fun writeScaled(
        source: Bitmap,
        dir: File,
        trackId: String,
        suffix: String,
        targetPx: Int
    ): Bitmap? {
        return try {
            val scaled = scaleToTarget(source, targetPx)
            val file = File(dir, "${trackId}_$suffix.webp")
            file.outputStream().use { out ->
                val format = if (android.os.Build.VERSION.SDK_INT >= 30)
                    Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                scaled.compress(format, 85, out)
            }
            scaled
        } catch (e: Exception) {
            Log.w(tag, "writeScaled failed for $suffix", e)
            null
        }
    }

    private fun scaleToTarget(source: Bitmap, targetPx: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= targetPx && h <= targetPx) return source
        val ratio = minOf(targetPx.toFloat() / w, targetPx.toFloat() / h)
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }

    private fun pathFor(dir: File, trackId: String, suffix: String): String =
        File(dir, "${trackId}_$suffix.webp").absolutePath
}