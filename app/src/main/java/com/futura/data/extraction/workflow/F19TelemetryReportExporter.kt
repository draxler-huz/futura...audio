// app/src/main/java/com/futura/data/extraction/workflow/F19TelemetryReportExporter.kt
package com.futura.data.extraction.workflow

import android.util.Log
import com.futura.data.extraction.model.ExtractedTrack
import com.futura.data.extraction.model.ScanResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * F19 — Exportable telemetry reports.
 *
 * Serializes the extracted catalog into JSON or CSV. Every telemetry
 * field on [ExtractedTrack] is included — including the silence-trim
 * fields (F9) and the CBR/VBR flag (F10) which the accountability matrix
 * assigns here for **reporting** purposes (the *computation* of those
 * values happens in group 2's `AudioTelemetryHelper`).
 *
 * Output format follows what most "export your library" tools do:
 *   • **JSON** — one array of track objects, formatted for machine
 *     consumption. Includes every field. Readable by any JSON parser.
 *   • **CSV**  — RFC 4180 quoting (fields with commas or quotes get
 *     wrapped and doubled). One header row, then one row per track.
 *     Easy for Excel / Numbers / Sheets to import. Unknown (null)
 *     telemetry is written as an empty field, never as 0.
 *
 * Rule 6: every I/O operation wrapped; failures return false and delete
 * any partial output. Cancellation is rethrown after cleanup.
 */
@Singleton
class F19TelemetryReportExporter @Inject constructor() {
    private val tag = "F19TelemetryReportExporter"

    /**
     * Export [tracks] as JSON to [outputFile].
     *
     * @return true on success. On failure any partial file is deleted and
     *         false is returned.
     */
    suspend fun exportJson(
        tracks: List<ExtractedTrack>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        var started = false
        try {
            outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val arr = JSONArray()
            tracks.forEach { track -> arr.put(trackToJson(track)) }
            val root = JSONObject().apply {
                put("version", 1)
                put("exportedAtMs", System.currentTimeMillis())
                put("trackCount", tracks.size)
                put("tracks", arr)
            }
            started = true
            outputFile.writeText(root.toString(2))
            Log.i(tag, "exportJson: ${tracks.size} tracks → ${outputFile.absolutePath}")
            true
        } catch (e: CancellationException) {
            if (started) discard(outputFile)
            throw e
        } catch (e: Exception) {
            Log.e(tag, "exportJson failed", e)
            if (started) discard(outputFile)
            false
        }
    }

    /** Convenience — export a whole [ScanResult] with stats. */
    suspend fun exportJsonWithStats(
        result: ScanResult,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        var started = false
        try {
            outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val tracksArr = JSONArray()
            result.tracks.forEach { tracksArr.put(trackToJson(it)) }
            val statsJson = JSONObject().apply {
                put("totalTracks", result.stats.totalTracks)
                put("losslessCount", result.stats.losslessCount)
                put("compressedCount", result.stats.compressedCount)
                put("totalBytes", result.stats.totalBytes)
                put("totalDurationMs", result.stats.totalDurationMs)
                put("errorCount", result.stats.errorCount)
                put("scanDurationMs", result.stats.scanDurationMs)
            }
            val errorsArr = JSONArray().apply { result.errors.forEach { put(it) } }
            val root = JSONObject().apply {
                put("version", 1)
                put("exportedAtMs", System.currentTimeMillis())
                put("stats", statsJson)
                put("tracks", tracksArr)
                put("errors", errorsArr)
            }
            started = true
            outputFile.writeText(root.toString(2))
            true
        } catch (e: CancellationException) {
            if (started) discard(outputFile)
            throw e
        } catch (e: Exception) {
            Log.e(tag, "exportJsonWithStats failed", e)
            if (started) discard(outputFile)
            false
        }
    }

    /**
     * Export [tracks] as CSV to [outputFile].
     *
     * @return true on success. On failure any partial file is deleted and
     *         false is returned.
     */
    suspend fun exportCsv(
        tracks: List<ExtractedTrack>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        var started = false
        try {
            outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            started = true
            outputFile.bufferedWriter().use { w ->
                w.write(CSV_HEADER); w.newLine()
                for (track in tracks) {
                    w.write(trackToCsvRow(track)); w.newLine()
                }
            }
            Log.i(tag, "exportCsv: ${tracks.size} tracks → ${outputFile.absolutePath}")
            true
        } catch (e: CancellationException) {
            if (started) discard(outputFile)
            throw e
        } catch (e: Exception) {
            Log.e(tag, "exportCsv failed", e)
            if (started) discard(outputFile)
            false
        }
    }

    /** Delete a partial export. Best-effort; never throws. */
    private fun discard(file: File) {
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    // ── Serialization ───────────────────────────────────────────

    private fun trackToJson(t: ExtractedTrack): JSONObject = JSONObject().apply {
        // Identity
        put("id", t.id)
        put("filePath", t.filePath)
        put("fileName", t.fileName)
        put("sizeBytes", t.sizeBytes)
        put("format", t.format)
        put("category", t.category.name)

        // Basic metadata
        put("durationMs", t.durationMs)
        put("bitrateKbps", t.bitrateKbps)
        put("title", t.title)
        put("artist", t.artist)
        put("album", t.album)
        put("addedAtMs", t.addedAtMs)
        put("lastScannedAtMs", t.lastScannedAtMs)

        // Group 2 telemetry — F10 bitrate profile
        // Null values are omitted from JSON (JSONObject drops null keys).
        put("sampleRateHz", t.sampleRateHz)
        put("channelCount", t.channelCount)
        put("bitDepth", t.bitDepth)
        put("isVbr", t.isVbr)          // F10
        put("fingerprint", t.fingerprint)
        put("albumArtPath", t.albumArtPath)
        put("bpm", t.bpm)
        // F9 silence-trim telemetry
        put("silentLeadingMs", t.silentLeadingMs)
        put("silentTrailingMs", t.silentTrailingMs)
        // Waveform preview — summarised, not the raw array
        val wp = t.waveformPreview
        put("waveformBuckets", wp?.size ?: 0)
        if (wp != null) {
            val peaks = JSONArray()
            wp.forEach { peaks.put(it.toDouble()) }
            put("waveformPeaks", peaks)
        }

        // Group 3 telemetry
        put("replayGainDb", t.replayGainDb)
        put("loopStartMs", t.loopStartMs)
        put("loopEndMs", t.loopEndMs)
        put("fadeCurveMs", t.fadeCurveMs)
        put("stemsAvailable", t.stemsAvailable)

        // Group 4 flags
        put("duplicateOf", t.duplicateOf)
        put("quarantined", t.quarantined)
        put("quarantineReason", t.quarantineReason)
        put("assignedToAlarm", t.assignedToAlarm)
        put("isVideo", t.isVideo)
    }

    private fun trackToCsvRow(t: ExtractedTrack): String = buildString {
        append(csvField(t.id)); append(',')
        append(csvField(t.filePath)); append(',')
        append(csvField(t.fileName)); append(',')
        append(t.sizeBytes); append(',')
        append(csvField(t.format)); append(',')
        append(csvField(t.category.name)); append(',')
        append(t.durationMs); append(',')
        append(t.bitrateKbps); append(',')
        append(csvField(t.title.orEmpty())); append(',')
        append(csvField(t.artist.orEmpty())); append(',')
        append(csvField(t.album.orEmpty())); append(',')
        append(csvOptional(t.sampleRateHz)); append(',')
        append(csvOptional(t.channelCount)); append(',')
        append(csvOptional(t.bitDepth)); append(',')
        append(csvOptional(t.isVbr)); append(',')            // F10
        append(csvOptional(t.silentLeadingMs)); append(',')  // F9
        append(csvOptional(t.silentTrailingMs)); append(',') // F9
        append(csvOptional(t.bpm)); append(',')
        append(csvOptional(t.replayGainDb)); append(',')
        append(csvOptional(t.loopStartMs)); append(',')
        append(csvOptional(t.loopEndMs)); append(',')
        append(csvOptional(t.fadeCurveMs)); append(',')
        append(t.stemsAvailable); append(',')
        append(csvField(t.duplicateOf.orEmpty())); append(',')
        append(t.quarantined); append(',')
        append(t.assignedToAlarm); append(',')
        append(t.isVideo)
    }

    /** Unknown (null) telemetry becomes an empty field, not 0. */
    private fun csvOptional(value: Any?): String = value?.toString().orEmpty()

    /** RFC 4180 field quoting. */
    private fun csvField(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return value
        }
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private companion object {
        const val CSV_HEADER =
            "id,filePath,fileName,sizeBytes,format,category,durationMs,bitrateKbps," +
                "title,artist,album,sampleRateHz,channelCount,bitDepth,isVbr," +
                "silentLeadingMs,silentTrailingMs,bpm,replayGainDb,loopStartMs," +
                "loopEndMs,fadeCurveMs,stemsAvailable,duplicateOf,quarantined,assignedToAlarm,isVideo"
    }
}
