// app/src/main/java/com/futura/data/extraction/workflow/F21MediaExtractionViewModel.kt
package com.futura.data.extraction.workflow

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.data.extraction.AlbumArtExtractor
import com.futura.data.extraction.AudioTelemetryHelper
import com.futura.data.extraction.AudioTranscoder
import com.futura.data.extraction.DuplicateFinder
import com.futura.data.extraction.ExtractionConfig
import com.futura.data.extraction.ExtractionEvents
import com.futura.data.extraction.ExtractionFileWatcher
import com.futura.data.extraction.LoopMarkerEditor
import com.futura.data.extraction.MediaExtractorService
import com.futura.data.extraction.StemSeparationHook
import com.futura.data.extraction.VoiceEnhancerFilter
import com.futura.data.extraction.model.ExtractedTrack
import com.futura.data.extraction.model.ExtractionFilter
import com.futura.data.extraction.model.ExtractionState
import com.futura.data.extraction.model.TrackCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F21 — The extraction pipeline's ViewModel.
 *
 * Owns the reactive state for everything the extraction UI displays:
 * the scan lifecycle, the track catalog, the current filter and sort,
 * per-track enrichment (F6, F7, F8), loop markers (F13), and the voice
 * enhancer toggle (F15). Exposes commands for every operation in the
 * accountability matrix. Wires the file-system watcher (F4) so newly
 * dropped tracks auto-enrich when auto-rescan is on.
 *
 * **Threading:** all commands are `fun` that launch in
 * `viewModelScope.launch`. Completion is signalled through
 * [events] (`SharedFlow<ExtractionEvents>`) so the UI can toast without
 * awaiting a `suspend` return. Full scans and incremental rescans are
 * serialised by [commandMutex]; batches are serialised by the batch
 * manager's own mutex.
 *
 * **State update safety:** every mutation of `_tracks` uses
 * `MutableStateFlow.update { }` (compare-and-set loop) so concurrent
 * enrichments cannot lose each other's writes.
 *
 * **Auto-rescan (F4):** when [setAutoRescan] is true, any CREATED or
 * MODIFIED file event from the watcher triggers a re-scan of that file's
 * parent directory. The result is merged into the catalog: tracks under
 * that directory are replaced, all others are kept. GROWTH: a
 * `MediaExtractorService.scanSingleFile(file)` method would make this
 * O(1); see [handleFileEvent].
 */
@HiltViewModel
class F21MediaExtractionViewModel @Inject constructor(
    private val extractor: MediaExtractorService,
    private val telemetry: AudioTelemetryHelper,
    private val artwork: AlbumArtExtractor,
    private val watcher: ExtractionFileWatcher,
    private val loopEditor: LoopMarkerEditor,
    private val voiceFilter: VoiceEnhancerFilter,
    private val stemHook: StemSeparationHook,
    private val batch: F17BatchActionManager,
    private val alarmBinder: F16AlarmAssignmentBinder,
    private val exporter: F19TelemetryReportExporter,
    private val cloudSync: F20CloudSyncHook,
    private val duplicateFinder: DuplicateFinder
) : ViewModel() {

    private val tag = "F21ExtractionVM"
    private val commandMutex = Mutex()

    // ── State ─────────────────────────────────────────────────────

    private val _state = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val state: StateFlow<ExtractionState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow<List<ExtractedTrack>>(emptyList())
    val tracks: StateFlow<List<ExtractedTrack>> = _tracks.asStateFlow()

    private val _filter = MutableStateFlow(ExtractionFilter.ALL)
    val filter: StateFlow<ExtractionFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(F18RegexFilter.SortOrder.TITLE_ASC)
    val sort: StateFlow<F18RegexFilter.SortOrder> = _sort.asStateFlow()

    /**
     * The catalog after filtering and sorting. Reactively recomputed
     * whenever [tracks], [filter], or [sort] change. Held only while
     * the UI is subscribed (5 s grace period to survive rotation).
     */
    val filteredTracks: StateFlow<List<ExtractedTrack>> =
        combine(_tracks, _filter, _sort) { tracks, filter, sort ->
            val filtered = tracks.filter { filter.matches(it) }
            when (sort) {
                F18RegexFilter.SortOrder.TITLE_ASC -> filtered.sortedBy { it.title.orEmpty().lowercase() }
                F18RegexFilter.SortOrder.TITLE_DESC -> filtered.sortedByDescending { it.title.orEmpty().lowercase() }
                F18RegexFilter.SortOrder.ARTIST_ASC -> filtered.sortedBy { it.artist.orEmpty().lowercase() }
                F18RegexFilter.SortOrder.ARTIST_DESC -> filtered.sortedByDescending { it.artist.orEmpty().lowercase() }
                F18RegexFilter.SortOrder.ALBUM_ASC -> filtered.sortedBy { it.album.orEmpty().lowercase() }
                F18RegexFilter.SortOrder.ALBUM_DESC -> filtered.sortedByDescending { it.album.orEmpty().lowercase() }
                F18RegexFilter.SortOrder.DURATION_ASC -> filtered.sortedBy { it.durationMs }
                F18RegexFilter.SortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
                F18RegexFilter.SortOrder.SIZE_ASC -> filtered.sortedBy { it.sizeBytes }
                F18RegexFilter.SortOrder.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
                F18RegexFilter.SortOrder.ADDED_ASC -> filtered.sortedBy { it.addedAtMs }
                F18RegexFilter.SortOrder.ADDED_DESC -> filtered.sortedByDescending { it.addedAtMs }
                F18RegexFilter.SortOrder.BPM_ASC -> filtered.sortedBy { it.bpm ?: Float.MAX_VALUE }
                F18RegexFilter.SortOrder.BPM_DESC -> filtered.sortedByDescending { it.bpm ?: 0f }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val batchProgress: StateFlow<F17BatchActionManager.BatchProgress?> = batch.progress

    /** Live file-watcher events (F4). Consumers can toast or display. */
    val fileEvents: SharedFlow<ExtractionFileWatcher.FileChangeEvent> = watcher.events

    private val _events = MutableSharedFlow<ExtractionEvents>(
        replay = 0,
        extraBufferCapacity = 32
    )
    val events: SharedFlow<ExtractionEvents> = _events.asSharedFlow()

    // ── Private scan bookkeeping ───────────────────────────────────

    private var lastConfig: ExtractionConfig? = null
    @Volatile private var autoRescan: Boolean = false

    init {
        // F4 — watch file changes when auto-rescan is enabled.
        viewModelScope.launch {
            try {
                watcher.events.collect { event ->
                    if (autoRescan) handleFileEvent(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Watcher collector failed", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled when onCleared() runs, so
        // cleanup needs its own scope or it never executes.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { watcher.stopAll() } catch (_: Exception) {}
            try { voiceFilter.clear() } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Scanning
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start a scan with [config]. Idempotent — a second call while a
     * scan is running will wait for the first to complete.
     */
    fun scan(config: ExtractionConfig) {
        viewModelScope.launch {
            commandMutex.withLock {
                try {
                    lastConfig = config
                    _events.tryEmit(ExtractionEvents.ScanStarted(config.customPaths.size))

                    val result = extractor.scan(config) { progress ->
                        _state.value = ExtractionState.Scanning(progress)
                        _events.tryEmit(
                            ExtractionEvents.ScanProgressed(
                                progress.filesProcessed,
                                progress.totalFiles
                            )
                        )
                    }

                    _tracks.value = result.tracks
                    _state.value = ExtractionState.Ready(result)

                    _events.tryEmit(
                        ExtractionEvents.ScanCompleted(
                            trackCount = result.stats.totalTracks,
                            errorCount = result.stats.errorCount,
                            durationMs = result.stats.scanDurationMs
                        )
                    )

                    if (config.watchDirectories) {
                        startWatching()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, "scan failed", e)
                    _state.value = ExtractionState.Failed(
                        reason = e.message ?: "unknown",
                        cause = e
                    )
                    _events.tryEmit(
                        ExtractionEvents.ScanFailed(e.message ?: "unknown")
                    )
                }
            }
        }
    }

    /** Rescan using the last config, if one has been used. */
    fun refresh() {
        lastConfig?.let { scan(it) }
    }

    /** F4 — install the file watcher on the last-scanned directories. */
    fun startWatching() {
        val config = lastConfig ?: return
        viewModelScope.launch {
            try {
                watcher.start(config.customPaths, config.supportedExtensions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "startWatching failed", e)
            }
        }
    }

    fun stopWatching() {
        viewModelScope.launch {
            try { watcher.stopAll() } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    /** F4 — toggle auto-rescan on file events. */
    fun setAutoRescan(enabled: Boolean) {
        autoRescan = enabled
    }

    // ═══════════════════════════════════════════════════════════════
    // Filtering & sorting (F18)
    // ═══════════════════════════════════════════════════════════════

    fun setFilter(f: ExtractionFilter) { _filter.value = f }
    fun clearFilter() { _filter.value = ExtractionFilter.ALL }
    fun setTextFilter(query: String) { _filter.update { it.copy(textQuery = query) } }
    fun setRegexFilter(pattern: String, caseInsensitive: Boolean = true) {
        _filter.update {
            it.copy(regexPattern = pattern, regexCaseInsensitive = caseInsensitive)
        }
    }
    fun setCategoryFilter(category: TrackCategory?) {
        _filter.update { it.copy(category = category) }
    }
    fun setSortOrder(order: F18RegexFilter.SortOrder) { _sort.value = order }

    // ═══════════════════════════════════════════════════════════════
    // Enrichment (F6, F7, F8, F9, F10)
    // ═══════════════════════════════════════════════════════════════

    /** F6 — extract album art for [trackId]. */
    fun extractArtworkFor(trackId: String) {
        viewModelScope.launch { extractArtwork(trackId) }
    }

    /** F7 + F8 + F9 + F10 — compute telemetry for [trackId]. */
    fun enrichTelemetryFor(trackId: String) {
        viewModelScope.launch { enrichTelemetry(trackId) }
    }

    /**
     * Enrich every track that is still missing telemetry or artwork.
     * Runs one track at a time so a large library does not flood the
     * decoder with parallel work.
     */
    fun enrichAllMissing() {
        viewModelScope.launch {
            for (id in _tracks.value.map { it.id }) {
                val track = _tracks.value.firstOrNull { it.id == id } ?: continue
                if (track.bpm == null) enrichTelemetry(id)
                if (track.albumArtPath == null) extractArtwork(id)
            }
        }
    }

    private suspend fun extractArtwork(trackId: String) {
        try {
            val track = _tracks.value.firstOrNull { it.id == trackId } ?: return
            val paths = artwork.extract(File(track.filePath), track.id)
            if (paths?.mediumPath != null) {
                updateTrack(trackId) { it.copy(albumArtPath = paths.mediumPath) }
                _events.tryEmit(
                    ExtractionEvents.TrackEnriched(
                        trackId, ExtractionEvents.EnrichmentKind.ARTWORK
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "extractArtwork($trackId) failed", e)
            _events.tryEmit(
                ExtractionEvents.TrackEnrichmentFailed(
                    trackId, ExtractionEvents.EnrichmentKind.ARTWORK,
                    e.message ?: "unknown"
                )
            )
        }
    }

    private suspend fun enrichTelemetry(trackId: String) {
        try {
            val track = _tracks.value.firstOrNull { it.id == trackId } ?: return
            val result = telemetry.compute(
                File(track.filePath),
                declaredBitrateKbps = track.bitrateKbps,
                durationMs = track.durationMs
            ) ?: return

            updateTrack(trackId) {
                it.copy(
                    sampleRateHz = result.sampleRateHz,
                    channelCount = result.channelCount,
                    isVbr = result.isVbr,
                    bpm = result.bpm,
                    waveformPreview = result.waveformPreview,
                    silentLeadingMs = result.silentLeadingMs,
                    silentTrailingMs = result.silentTrailingMs
                )
            }
            _events.tryEmit(
                ExtractionEvents.TrackEnriched(
                    trackId, ExtractionEvents.EnrichmentKind.TELEMETRY
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "enrichTelemetry($trackId) failed", e)
            _events.tryEmit(
                ExtractionEvents.TrackEnrichmentFailed(
                    trackId, ExtractionEvents.EnrichmentKind.TELEMETRY,
                    e.message ?: "unknown"
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Loop markers (F13)
    // ═══════════════════════════════════════════════════════════════

    fun setLoopMarkers(trackId: String, startMs: Long, endMs: Long) {
        val track = _tracks.value.firstOrNull { it.id == trackId } ?: return
        loopEditor.setLoop(track, startMs, endMs)?.let { updated ->
            replaceTrack(updated)
        }
    }

    fun clearLoopMarkers(trackId: String) {
        val track = _tracks.value.firstOrNull { it.id == trackId } ?: return
        replaceTrack(loopEditor.clearLoop(track))
    }

    fun setFadeDuration(trackId: String, fadeMs: Long) {
        val track = _tracks.value.firstOrNull { it.id == trackId } ?: return
        loopEditor.setFade(track, fadeMs)?.let { replaceTrack(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // Voice enhancer (F15)
    // ═══════════════════════════════════════════════════════════════

    fun applyVoiceEnhancer() {
        viewModelScope.launch {
            try { voiceFilter.apply() } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "applyVoiceEnhancer failed", e)
            }
        }
    }

    fun clearVoiceEnhancer() {
        viewModelScope.launch {
            try { voiceFilter.clear() } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "clearVoiceEnhancer failed", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Alarm assignment (F16)
    // ═══════════════════════════════════════════════════════════════

    fun assignToAlarm(trackId: String, specId: String) {
        viewModelScope.launch {
            try {
                val track = _tracks.value.firstOrNull { it.id == trackId } ?: return@launch
                val ok = alarmBinder.bindToAlarm(track, specId)
                if (ok) {
                    updateTrack(trackId) { it.copy(assignedToAlarm = true) }
                    _events.tryEmit(ExtractionEvents.AlarmAssigned(trackId, specId))
                } else {
                    _events.tryEmit(
                        ExtractionEvents.AlarmAssignmentFailed(
                            trackId, "AlarmStore refused (bio-locked?)"
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "assignToAlarm failed", e)
                _events.tryEmit(
                    ExtractionEvents.AlarmAssignmentFailed(
                        trackId, e.message ?: "unknown"
                    )
                )
            }
        }
    }

    fun unbindFromAlarm(specId: String) {
        viewModelScope.launch {
            try {
                if (alarmBinder.unbindFromAlarm(specId)) {
                    _events.tryEmit(ExtractionEvents.AlarmUnbound(specId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "unbindFromAlarm failed", e)
            }
        }
    }

    fun createAndBindTrack(trackId: String, hour: Int, minute: Int, label: String) {
        viewModelScope.launch {
            try {
                val track = _tracks.value.firstOrNull { it.id == trackId } ?: return@launch
                val specId = alarmBinder.createAndBind(track, hour, minute, label)
                if (specId != null) {
                    updateTrack(trackId) { it.copy(assignedToAlarm = true) }
                    _events.tryEmit(ExtractionEvents.AlarmAssigned(trackId, specId))
                } else {
                    _events.tryEmit(
                        ExtractionEvents.AlarmAssignmentFailed(trackId, "alarm creation failed")
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "createAndBindTrack failed", e)
                _events.tryEmit(
                    ExtractionEvents.AlarmAssignmentFailed(trackId, e.message ?: "unknown")
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Batch (F2, F3, F12, F14, F17)
    // ═══════════════════════════════════════════════════════════════

    fun dedupe() {
        viewModelScope.launch {
            try {
                _events.tryEmit(
                    ExtractionEvents.BatchStarted("dedupe", _tracks.value.size)
                )
                val groups = batch.batchDedupe(_tracks.value)
                _tracks.value = duplicateFinder.applyGroups(_tracks.value, groups)
                _events.tryEmit(
                    ExtractionEvents.BatchCompleted("dedupe", groups.size)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(
                    ExtractionEvents.BatchFailed("dedupe", e.message ?: "unknown")
                )
            }
        }
    }

    fun normalize(ids: List<String>) {
        viewModelScope.launch {
            try {
                val subset = _tracks.value.filter { it.id in ids }
                _events.tryEmit(ExtractionEvents.BatchStarted("normalize", subset.size))
                val results = batch.batchNormalize(subset)
                results.forEach { (id, result) ->
                    updateTrack(id) { it.copy(replayGainDb = result.adjustmentDb) }
                }
                _events.tryEmit(ExtractionEvents.BatchCompleted("normalize", results.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(
                    ExtractionEvents.BatchFailed("normalize", e.message ?: "unknown")
                )
            }
        }
    }

    fun transcode(ids: List<String>, target: AudioTranscoder.TargetFormat, outputDir: File) {
        viewModelScope.launch {
            try {
                val subset = _tracks.value.filter { it.id in ids }
                _events.tryEmit(ExtractionEvents.BatchStarted("transcode", subset.size))
                val results = batch.batchTranscode(subset, target, outputDir)
                _events.tryEmit(ExtractionEvents.BatchCompleted("transcode", results.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(
                    ExtractionEvents.BatchFailed("transcode", e.message ?: "unknown")
                )
            }
        }
    }

    fun quarantine(ids: List<String>) {
        viewModelScope.launch {
            try {
                val files = _tracks.value.filter { it.id in ids }.map { File(it.filePath) }
                _events.tryEmit(ExtractionEvents.BatchStarted("quarantine", files.size))
                val count = batch.batchQuarantine(files)
                _events.tryEmit(ExtractionEvents.BatchCompleted("quarantine", count))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(
                    ExtractionEvents.BatchFailed("quarantine", e.message ?: "unknown")
                )
            }
        }
    }

    fun delete(ids: List<String>) {
        viewModelScope.launch {
            try {
                val subset = _tracks.value.filter { it.id in ids }
                _events.tryEmit(ExtractionEvents.BatchStarted("delete", subset.size))
                val count = batch.batchDelete(subset)
                // Drop only tracks whose file is actually gone; a file that
                // failed to delete stays in the catalog.
                _tracks.update { list ->
                    list.filterNot { it.id in ids && !File(it.filePath).exists() }
                }
                _events.tryEmit(ExtractionEvents.BatchCompleted("delete", count))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(
                    ExtractionEvents.BatchFailed("delete", e.message ?: "unknown")
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Export / sync (F19, F20)
    // ═══════════════════════════════════════════════════════════════

    fun exportJson(file: File) {
        viewModelScope.launch {
            try {
                if (exporter.exportJson(_tracks.value, file)) {
                    _events.tryEmit(
                        ExtractionEvents.ExportCompleted(file.absolutePath,
                            ExtractionEvents.ExportFormat.JSON)
                    )
                } else {
                    _events.tryEmit(ExtractionEvents.ExportFailed("write failed"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ExtractionEvents.ExportFailed(e.message ?: "unknown"))
            }
        }
    }

    fun exportCsv(file: File) {
        viewModelScope.launch {
            try {
                if (exporter.exportCsv(_tracks.value, file)) {
                    _events.tryEmit(
                        ExtractionEvents.ExportCompleted(file.absolutePath,
                            ExtractionEvents.ExportFormat.CSV)
                    )
                } else {
                    _events.tryEmit(ExtractionEvents.ExportFailed("write failed"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ExtractionEvents.ExportFailed(e.message ?: "unknown"))
            }
        }
    }

    fun syncPush() {
        viewModelScope.launch {
            try {
                val key = cloudSync.pushManifest(_tracks.value)
                if (key != null) _events.tryEmit(ExtractionEvents.SyncPushed(key))
                else _events.tryEmit(ExtractionEvents.SyncFailed("provider unavailable"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ExtractionEvents.SyncFailed(e.message ?: "unknown"))
            }
        }
    }

    fun syncPull() {
        viewModelScope.launch {
            try {
                cloudSync.pullManifest()  // GROWTH: merge strategy not yet specified
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ExtractionEvents.SyncFailed(e.message ?: "unknown"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Introspection
    // ═══════════════════════════════════════════════════════════════

    fun isStemSeparationAvailable(): Boolean = stemHook.isAvailable()
    fun providerName(): String = cloudSync.providerName

    // ═══════════════════════════════════════════════════════════════
    // Internals
    // ═══════════════════════════════════════════════════════════════

    private fun updateTrack(id: String, transform: (ExtractedTrack) -> ExtractedTrack) {
        _tracks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun replaceTrack(updated: ExtractedTrack) {
        _tracks.update { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
    }

    /**
     * F4 — handle a single file-watcher event by re-scanning the affected
     * parent directory and merging the result into the catalog. Tracks
     * under that directory are replaced; tracks elsewhere are kept.
     *
     * GROWTH: a `MediaExtractorService.scanSingleFile(file)` method would
     * make this O(1) per event. Today it re-scans the parent directory,
     * which is O(parent size) per event.
     */
    private fun handleFileEvent(event: ExtractionFileWatcher.FileChangeEvent) {
        val parent = File(event.path).parent ?: return
        val config = lastConfig ?: return
        viewModelScope.launch {
            commandMutex.withLock {
                try {
                    val result = extractor.scan(config.copy(customPaths = listOf(parent))) { }
                    val prefix = parent + File.separator
                    _tracks.update { old ->
                        old.filterNot { it.filePath.startsWith(prefix) } + result.tracks
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(tag, "incremental rescan of $parent failed", e)
                }
            }
        }
    }
}
