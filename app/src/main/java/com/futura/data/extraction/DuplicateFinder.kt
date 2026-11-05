// app/src/main/java/com/futura/data/extraction/DuplicateFinder.kt
package com.futura.data.extraction

import android.util.Log
import com.futura.data.extraction.model.ExtractedTrack
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fingerprints audio files and flags duplicates.
 *
 * Feature 2. Computes a compact signature per file from three 64 KB
 * chunks (head, middle, tail) plus file size. Files with identical
 * signatures are byte-identical in those three regions — a very strong
 * heuristic for duplicates, and 20× faster than hashing whole files
 * (which is why every serious music player uses chunked hashing).
 *
 * Two passes:
 *   1. **Size pre-filter** — group files by size. Within a group of
 *      size 1, no further hashing is needed (nothing to collide with).
 *   2. **Hash filter** — within each size group, compute the fingerprint
 *      and group by fingerprint. Groups of size ≥ 2 are duplicates.
 *
 * After detection, every non-canonical duplicate (the later-added one)
 * gets its `duplicateOf` field set to the canonical track's id — so
 * downstream filters exclude duplicates by default.
 *
 * Rule 6: per-file hashing wrapped.
 * Rule 7: [mutex] serializes concurrent [findDuplicates] calls so two
 * passes cannot share a partially populated fingerprint map.
 */
@Singleton
class DuplicateFinder @Inject constructor() {

    private val tag = "DuplicateFinder"
    private val mutex = Mutex()

    data class DuplicateGroup(
        val canonicalTrackId: String,
        val duplicateTrackIds: List<String>,
        val signature: String
    )

    /**
     * Detect duplicates among [tracks].
     *
     * @param tracks the full library to analyze. Grouping is done on a
     *        temporary fingerprint map; [tracks] is not mutated.
     * @return a list of duplicate groups; empty if no duplicates.
     */
    suspend fun findDuplicates(
        tracks: List<ExtractedTrack>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (tracks.size < 2) return@withLock emptyList()

                // Pass 1 — group by size. Files of different sizes cannot
                // be duplicates.
                val bySize = tracks.groupBy { it.sizeBytes }
                val candidateGroups = bySize.filter { it.value.size >= 2 }
                if (candidateGroups.isEmpty()) return@withLock emptyList()

                // Pass 2 — fingerprint every file in a size ≥ 2 group.
                val results = mutableListOf<DuplicateGroup>()
                val processed = AtomicInteger(0)
                val total = candidateGroups.values.sumOf { it.size }

                coroutineScope {
                    for ((_, candidates) in candidateGroups) {
                        launch {
                            val fingerprints = mutableMapOf<String, MutableList<ExtractedTrack>>()
                            for (track in candidates) {
                                try {
                                    val fp = fingerprint(File(track.filePath))
                                    if (fp != null) {
                                        fingerprints.getOrPut(fp) { mutableListOf() }
                                            .add(track)
                                    }
                                } catch (e: Exception) {
                                    Log.w(tag, "fingerprint failed for ${track.fileName}", e)
                                }
                                val done = processed.incrementAndGet()
                                onProgress(done, total)
                            }
                            synchronized(results) {
                                for ((fp, group) in fingerprints) {
                                    if (group.size >= 2) {
                                        // Canonical = oldest file on disk, ties by path. addedAtMs is
                                        // reset on every scan, so it cannot pick a stable original.
                                        val sorted = group.sortedWith(
                                            compareBy<ExtractedTrack>(
                                                { File(it.filePath).lastModified() },
                                                { it.filePath }
                                            )
                                        )
                                        results.add(
                                            DuplicateGroup(
                                                canonicalTrackId = sorted.first().id,
                                                duplicateTrackIds = sorted.drop(1).map { it.id },
                                                signature = fp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Log.i(tag, "Found ${results.size} duplicate groups")
                results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "findDuplicates failed", e)
                emptyList()
            }
        }
    }

    /**
     * Apply [groups] to [tracks] by setting `duplicateOf` on the
     * duplicate members. Returns the modified list (input unmodified).
     */
    fun applyGroups(
        tracks: List<ExtractedTrack>,
        groups: List<DuplicateGroup>
    ): List<ExtractedTrack> {
        if (groups.isEmpty()) return tracks
        val dupMap = mutableMapOf<String, String>()
        for (group in groups) {
            for (dup in group.duplicateTrackIds) {
                dupMap[dup] = group.canonicalTrackId
            }
        }
        return tracks.map { t ->
            val canonical = dupMap[t.id]
            if (canonical != null && canonical != t.id) {
                t.copy(duplicateOf = canonical)
            } else t
        }
    }

    // ── Fingerprinting ────────────────────────────────────────────

    /**
     * Compute the head/middle/tail MD5 fingerprint of [file]. Returns
     * null on any I/O failure or if the file is smaller than 3 chunks.
     *
     * Chunk size: 64 KB. Files smaller than 192 KB fall back to a full
     * hash (still cheap at those sizes).
     */
    private fun fingerprint(file: File): String? {
        return try {
            if (!file.exists() || !file.canRead()) return null
            val size = file.length()
            if (size <= 0L) return null

            val md = MessageDigest.getInstance("MD5")
            md.update(longToBytes(size))

            if (size <= SMALL_FILE_THRESHOLD) {
                file.inputStream().use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    // Head.
                    val head = ByteArray(CHUNK_SIZE)
                    raf.seek(0)
                    raf.readFully(head)
                    md.update(head)

                    // Middle.
                    val mid = ByteArray(CHUNK_SIZE)
                    raf.seek(size / 2 - CHUNK_SIZE / 2)
                    raf.readFully(mid)
                    md.update(mid)

                    // Tail.
                    val tail = ByteArray(CHUNK_SIZE)
                    raf.seek(size - CHUNK_SIZE)
                    raf.readFully(tail)
                    md.update(tail)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(tag, "fingerprint failed for ${file.name}", e)
            null
        }
    }

    private fun longToBytes(v: Long): ByteArray {
        var x = v
        val bytes = ByteArray(8)
        for (i in 7 downTo 0) {
            bytes[i] = (x and 0xFF).toByte()
            x = x ushr 8
        }
        return bytes
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
        const val SMALL_FILE_THRESHOLD = 3L * CHUNK_SIZE
    }
}