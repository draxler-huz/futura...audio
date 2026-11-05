package com.futura.data.extraction.workflow

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.data.extraction.AlbumArtExtractor
import com.futura.data.extraction.AudioTelemetryHelper
import com.futura.data.extraction.AudioTranscoder
import com.futura.data.extraction.CorruptFileQuarantine
import com.futura.data.extraction.DuplicateFinder
import com.futura.data.extraction.ExtractionConfig
import com.futura.data.extraction.ExtractionEvents
import com.futura.data.extraction.ExtractionFileWatcher
import com.futura.data.extraction.LoopMarkerEditor
import com.futura.data.extraction.MediaExtractorService
import com.futura.data.extraction.StemSeparationHook
import com.futura.data.extraction.UrlExtractor
import com.futura.data.extraction.VoiceEnhancerFilter
import com.futura.data.extraction.model.ExtractedTrack
import com.futura.data.extraction.model.ExtractionFilter
import com.futura.data.extraction.model.ExtractionState
import com.futura.data.extraction.model.TrackCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
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
 * F21 — Media extraction ViewModel (unified).
 *
 * One class supporting every extraction feature across all five groups:
 * Scan, URL extraction, Filtering, Enrichment (art/telemetry), Deduplication, 
 * Quarantine, File watching, Loop markers, Voice enhancer, Transcoding, 
 * Alarm assignment, Batch operations, Export, and Cloud sync.
 *
 * Implements the sketch API surface:
 *   • `mediaCatalog`
 *   • `currentFilter`
 *   • `filteredCatalog`
 *   • `eventFlow`
 *   • `updateFilter(filter)`
 *   • `extractFromTarget(url)`
 *   • `assignToAlarm(trackId, alarmId: Long)`
 */
@HiltViewModel
class F21MediaExtractionViewModel @Inject constructor(
    private val extractor: MediaExtractorService,
    private val urlExtractor: UrlExtractor,
    private val telemetry: AudioTelemetryHelper,
    private val artwork: AlbumArtExtractor,
    private val watcher: ExtractionFileWatcher,
    private val duplicateFinder: DuplicateFinder,
    private val quarantine: CorruptFileQuarantine,
    private val transcoder: AudioTranscoder,
    private val loopEditor: LoopMarkerEditor,
    private val voiceFilter: VoiceEnhancerFilter,
    private val stemHook: StemSeparationHook,
    private val batch: F17BatchActionManager,
    private val alarmBinder: F16AlarmAssignmentBinder,
    private val exporter: F19TelemetryReportExporter,
    private val cloudSync: F20CloudSyncHook
) : ViewModel() {

    private val tag = "F21MediaExtractionVM"
    private val commandMutex = Mutex()

    // ═══════════════════════════════════════════════════════════════
    // Public state
    // ═══════════════════════════════════════════════════════════════

    private val _mediaCatalog = MutableStateFlow<List<ExtractedTrack>>(emptyList())
    val mediaCatalog: StateFlow<List<ExtractedTrack>> = _mediaCatalog.asStateFlow()
    
    // Alias
    val tracks: StateFlow<List<ExtractedTrack>> get() = mediaCatalog

    private val _currentFilter = MutableStateFlow<ExtractionFilter>(ExtractionFilter.ALL)
    val currentFilter: StateFlow<ExtractionFilter> = _currentFilter.asStateFlow()
    
    // Alias
    val filter: StateFlow<ExtractionFilter> get() = currentFilter

    val filteredCatalog: StateFlow<List<ExtractedTrack>> =
        combine(_mediaCatalog, _currentFilter) { catalog, f ->
            if (f === ExtractionFilter.ALL) catalog
            else catalog.filter { f.matches(it) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )
        
    // Alias
    val filteredTracks: StateFlow<List<ExtractedTrack>> get() = filteredCatalog

    private val _extractionState = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val extractionState: StateFlow<ExtractionState> = _extractionState.asStateFlow()
    
    // Alias
    val state: StateFlow<ExtractionState> get() = extractionState

    val batchProgress: StateFlow<F17BatchActionManager.BatchProgress?> = batch.progress
    val fileEvents: SharedFlow<ExtractionFileWatcher.FileChangeEvent> = watcher.events

    private val _eventFlow = MutableSharedFlow<ExtractionEvents>(
        replay = 0,
        extraBufferCapacity = 32
    )
    val eventFlow: SharedFlow<ExtractionEvents> = _eventFlow.asSharedFlow()
    
    // Alias
    val events: SharedFlow<ExtractionEvents> get() = eventFlow

    // ═══════════════════════════════════════════════════════════════
    // Internal bookkeeping
    // ═══════════════════════════════════════════════════════════════

    private var lastConfig: ExtractionConfig? = null

    @Volatile
    private var autoRescan: Boolean = false

    init {
        viewModelScope.launch {
            try {
                watcher.events.collect { event ->
                    if (autoRescan) handleFileEvent(event)
                }
            } catch (e: Exception) {
                Log.w(tag, "Watcher collector failed", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try { watcher.stopAll() } catch (_: Exception) {}
            try { voiceFilter.clear() } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Filtering
    // ═══════════════════════════════════════════════════════════════

    fun updateFilter(newFilter: ExtractionFilter) {
        _currentFilter.value = newFilter
    }

    fun setFilter(f: ExtractionFilter) = updateFilter(f)

    fun setTextFilter(query: String) {
        _currentFilter.update { it.copy(textQuery = query) }
    }

    fun setRegexFilter(pattern: String, caseInsensitive: Boolean = true) {
        _currentFilter.update {
            it.copy(regexPattern = pattern, regexCaseInsensitive = caseInsensitive)
        }
    }

    fun setCategoryFilter(category: TrackCategory?) {
        _currentFilter.update { it.copy(category = category) }
    }

    fun clearFilter() {
        _currentFilter.value = ExtractionFilter.ALL
    }

    // ═══════════════════════════════════════════════════════════════
    // Scanning
    // ═══════════════════════════════════════════════════════════════

    fun scan(paths: List<String>, config: ExtractionConfig? = null) {
        viewModelScope.launch {
            commandMutex.withLock {
                try {
                    val effectiveConfig = config ?: ExtractionConfig(customPaths = paths)
                    lastConfig = effectiveConfig

                    _eventFlow.tryEmit(ExtractionEvents.ScanStarted(effectiveConfig.customPaths.size))

                    val result = extractor.scan(effectiveConfig) { progress ->
                        _extractionState.value = ExtractionState.Scanning(progress)
                        _eventFlow.tryEmit(
                            ExtractionEvents.ScanProgressed(
                                processed = progress.filesProcessed,
                                total = progress.totalFiles
                            )
                        )
                    }

                    _mediaCatalog.value = result.tracks
                    _extractionState.value = ExtractionState.Ready(result)
                    _eventFlow.tryEmit(
                        ExtractionEvents.ScanCompleted(
                            trackCount = result.stats.totalTracks,
                            errorCount = result.stats.errorCount,
                            durationMs = result.stats.scanDurationMs
                        )
                    )

                    if (effectiveConfig.watchDirectories) {
                        startWatching()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "scan failed", e)
                    _extractionState.value = ExtractionState.Failed(e.message ?: "unknown", e)
                    _eventFlow.tryEmit(ExtractionEvents.ScanFailed(e.message ?: "unknown"))
                }
            }
        }
    }

    fun refresh() {
        lastConfig?.let { scan(it.customPaths, it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // URL Extraction
    // ═══════════════════════════════════════════════════════════════

    fun extractFromTarget(targetUrl: String) {
        viewModelScope.launch {
            commandMutex.withLock {
                val url = targetUrl.trim()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    _eventFlow.tryEmit(ExtractionEvents.ScanFailed("URL must be http:// or https:// — got: $url"))
                    return@withLock
                }

                _eventFlow.tryEmit(ExtractionEvents.ScanStarted(1))
                try {
                    val downloaded = urlExtractor.download(url)
                    if (downloaded == null) {
                        _eventFlow.tryEmit(ExtractionEvents.ScanFailed("Download failed: $url"))
                        return@withLock
                    }

                    val parent = downloaded.file.parentFile
                    if (parent == null) {
                        _eventFlow.tryEmit(ExtractionEvents.ScanFailed("No parent directory"))
                        return@withLock
                    }

                    val result = extractor.scan(
                        ExtractionConfig(
                            customPaths = listOf(parent.absolutePath),
                            quarantineEnabled = false,
                            fingerprintingEnabled = false,
                            extractAlbumArt = false,
                            computeTelemetry = false,
                            watchDirectories = false
                        )
                    )

                    val added = result.tracks.filter { it.filePath == downloaded.file.absolutePath }
                    if (added.isEmpty()) {
                        _eventFlow.tryEmit(ExtractionEvents.ScanFailed("Downloaded file not recognised as audio"))
                        return@withLock
                    }

                    _mediaCatalog.update { existing ->
                        val existingPaths = existing.map { it.filePath }.toSet()
                        existing + added.filter { it.filePath !in existingPaths }
                    }
                    
                    _eventFlow.tryEmit(ExtractionEvents.ScanCompleted(added.size, 0, 0L))

                    added.forEach { track ->
                        enrichTelemetryFor(track.id)
                        extractArtworkFor(track.id)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "extractFromTarget failed", e)
                    _eventFlow.tryEmit(ExtractionEvents.ScanFailed(e.message ?: "unknown"))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // File watcher
    // ═══════════════════════════════════════════════════════════════

    fun startWatching() {
        val config = lastConfig ?: return
        viewModelScope.launch {
            try { watcher.start(config.customPaths, config.supportedExtensions) } 
            catch (e: Exception) { Log.w(tag, "startWatching failed", e) }
        }
    }

    fun stopWatching() {
        viewModelScope.launch {
            try { watcher.stopAll() } catch (_: Exception) {}
        }
    }

    fun setAutoRescan(enabled: Boolean) {
        autoRescan = enabled
    }

    // ═══════════════════════════════════════════════════════════════
    // Enrichment
    // ═══════════════════════════════════════════════════════════════

    fun extractArtworkFor(trackId: String) {
        viewModelScope.launch {
            try {
                val track = findTrack(trackId) ?: return@launch
                val paths = artwork.extract(File(track.filePath), track.id)
                val medium = paths?.mediumPath ?: return@launch
                updateTrack(trackId) { it.copy(albumArtPath = medium) }
                _eventFlow.tryEmit(ExtractionEvents.TrackEnriched(trackId, ExtractionEvents.EnrichmentKind.ARTWORK))
            } catch (e: Exception) {
                Log.w(tag, "extractArtworkFor($trackId) failed", e)
                _eventFlow.tryEmit(ExtractionEvents.TrackEnrichmentFailed(trackId, ExtractionEvents.EnrichmentKind.ARTWORK, e.message ?: "unknown"))
            }
        }
    }

    fun enrichTelemetryFor(trackId: String) {
        viewModelScope.launch {
            try {
                val track = findTrack(trackId) ?: return@launch
                val result = telemetry.compute(File(track.filePath), track.bitrateKbps, track.durationMs) ?: return@launch

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
                _eventFlow.tryEmit(ExtractionEvents.TrackEnriched(trackId, ExtractionEvents.EnrichmentKind.TELEMETRY))
            } catch (e: Exception) {
                Log.w(tag, "enrichTelemetryFor($trackId) failed", e)
                _eventFlow.tryEmit(ExtractionEvents.TrackEnrichmentFailed(trackId, ExtractionEvents.EnrichmentKind.TELEMETRY, e.message ?: "unknown"))
            }
        }
    }

    fun enrichAllMissing() {
        _mediaCatalog.value.forEach { track ->
            if (track.bpm == null) enrichTelemetryFor(track.id)
            if (track.albumArtPath == null) extractArtworkFor(track.id)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Loop markers
    // ═══════════════════════════════════════════════════════════════

    fun setLoopMarkers(trackId: String, startMs: Long, endMs: Long) {
        val track = findTrack(trackId) ?: return
        loopEditor.setLoop(track, startMs, endMs)?.let { replaceTrack(it) }
    }

    fun clearLoopMarkers(trackId: String) {
        val track = findTrack(trackId) ?: return
        replaceTrack(loopEditor.clearLoop(track))
    }

    fun setFadeDuration(trackId: String, fadeMs: Long) {
        val track = findTrack(trackId) ?: return
        loopEditor.setFade(track, fadeMs)?.let { replaceTrack(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // Voice Enhancer
    // ═══════════════════════════════════════════════════════════════

    fun applyVoiceEnhancer() {
        viewModelScope.launch {
            try { voiceFilter.apply() } catch (e: Exception) { Log.w(tag, "applyVoiceEnhancer failed", e) }
        }
    }

    fun clearVoiceEnhancer() {
        viewModelScope.launch {
            try { voiceFilter.clear() } catch (e: Exception) { Log.w(tag, "clearVoiceEnhancer failed", e) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Alarm assignment
    // ═══════════════════════════════════════════════════════════════

    fun assignToAlarm(trackId: String, specId: String) {
        viewModelScope.launch {
            try {
                val track = findTrack(trackId) ?: run {
                    _eventFlow.tryEmit(ExtractionEvents.AlarmAssignmentFailed(trackId, "Track not in catalog"))
                    return@launch
                }
                val ok = alarmBinder.bindToAlarm(track, specId)
                if (ok) {
                    updateTrack(trackId) { it.copy(assignedToAlarm = true) }
                    _eventFlow.tryEmit(ExtractionEvents.AlarmAssigned(trackId, specId))
                } else {
                    _eventFlow.tryEmit(ExtractionEvents.AlarmAssignmentFailed(trackId, "AlarmStore refused (spec not found or bio-locked)"))
                }
            } catch (e: Exception) {
                Log.e(tag, "assignToAlarm failed", e)
                _eventFlow.tryEmit(ExtractionEvents.AlarmAssignmentFailed(trackId, e.message ?: "unknown"))
            }
        }
    }

    fun assignToAlarm(trackId: String, alarmId: Long) {
        assignToAlarm(trackId, alarmId.toString())
    }

    fun createAndBindTrack(trackId: String, hour: Int, minute: Int, label: String) {
        viewModelScope.launch {
            try {
                val track = findTrack(trackId) ?: return@launch
                val newId = alarmBinder.createAndBind(track, hour, minute, label) ?: return@launch
                updateTrack(trackId) { it.copy(assignedToAlarm = true) }
                _eventFlow.tryEmit(ExtractionEvents.AlarmAssigned(trackId, newId))
            } catch (e: Exception) {
                Log.e(tag, "createAndBindTrack failed", e)
            }
        }
    }

    fun unbindFromAlarm(specId: String) {
        viewModelScope.launch {
            try {
                if (alarmBinder.unbindFromAlarm(specId)) {
                    _eventFlow.tryEmit(ExtractionEvents.AlarmUnbound(specId))
                }
            } catch (e: Exception) {
                Log.w(tag, "unbindFromAlarm failed", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Batch Operations
    // ═══════════════════════════════════════════════════════════════

    fun dedupe() {
        viewModelScope.launch {
            try {
                _eventFlow.tryEmit(ExtractionEvents.BatchStarted("dedupe", _mediaCatalog.value.size))
                val groups = batch.batchDedupe(_mediaCatalog.value)
                _mediaCatalog.value = duplicateFinder.applyGroups(_mediaCatalog.value, groups)
                _eventFlow.tryEmit(ExtractionEvents.BatchCompleted("dedupe", groups.size))
            } catch (e: Exception) {
                Log.e(tag, "dedupe failed", e)
                _eventFlow.tryEmit(ExtractionEvents.BatchFailed("dedupe", e.message ?: "unknown"))
            }
        }
    }

    fun normalize(ids: List<String>) {
        viewModelScope.launch {
            try {
                val subset = _mediaCatalog.value.filter { it.id in ids }
                _eventFlow.tryEmit(ExtractionEvents.BatchStarted("normalize", subset.size))
                val results = batch.batchNormalize(subset)
                results.forEach { (id, r) -> updateTrack(id) { it.copy(replayGainDb = r.adjustmentDb) } }
                _eventFlow.tryEmit(ExtractionEvents.BatchCompleted("normalize", results.size))
            } catch (e: Exception) {
                Log.e(tag, "normalize failed", e)
                _eventFlow.tryEmit(ExtractionEvents.BatchFailed("normalize", e.message ?: "unknown"))
            }
        }
    }

    fun transcode(ids: List<String>, target: AudioTranscoder.TargetFormat, outputDir: File) {
        viewModelScope.launch {
            try {
                val subset = _mediaCatalog.value.filter { it.id in ids }
                _eventFlow.tryEmit(ExtractionEvents.BatchStarted("transcode", subset.size))
                val results = batch.batchTranscode(subset, target, outputDir)
                _eventFlow.tryEmit(ExtractionEvents.BatchCompleted("transcode", results.size))
            } catch (e: Exception) {
                Log.e(tag, "transcode failed", e)
                _eventFlow.tryEmit(ExtractionEvents.BatchFailed("transcode", e.message ?: "unknown"))
            }
        }
    }

    fun quarantine(ids: List<String>) {
        viewModelScope.launch {
            try {
                val files = _mediaCatalog.value.filter { it.id in ids }.map { File(it.filePath) }
                _eventFlow.tryEmit(ExtractionEvents.BatchStarted("quarantine", files.size))
                val count = batch.batchQuarantine(files)
                _eventFlow.tryEmit(ExtractionEvents.BatchCompleted("quarantine", count))
            } catch (e: Exception) {
                Log.e(tag, "quarantine failed", e)
                _eventFlow.tryEmit(ExtractionEvents.BatchFailed("quarantine", e.message ?: "unknown"))
            }
        }
    }

    fun delete(ids: List<String>) {
        viewModelScope.launch {
            try {
                val subset = _mediaCatalog.value.filter { it.id in ids }
                _eventFlow.tryEmit(ExtractionEvents.BatchStarted("delete", subset.size))
                val count = batch.batchDelete(subset)
                _mediaCatalog.update { list -> list.filterNot { it.id in ids } }
                _eventFlow.tryEmit(ExtractionEvents.BatchCompleted("delete", count))
            } catch (e: Exception) {
                Log.e(tag, "delete failed", e)
                _eventFlow.tryEmit(ExtractionEvents.BatchFailed("delete", e.message ?: "unknown"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Export / Sync
    // ═══════════════════════════════════════════════════════════════

    fun exportJson(file: File) {
        viewModelScope.launch {
            try {
                if (exporter.exportJson(_mediaCatalog.value, file)) {
                    _eventFlow.tryEmit(ExtractionEvents.ExportCompleted(file.absolutePath, ExtractionEvents.ExportFormat.JSON))
                } else {
                    _eventFlow.tryEmit(ExtractionEvents.ExportFailed("write failed"))
                }
            } catch (e: Exception) {
                _eventFlow.tryEmit(ExtractionEvents.ExportFailed(e.message ?: "unknown"))
            }
        }
    }

    fun exportCsv(file: File) {
        viewModelScope.launch {
            try {
                if (exporter.exportCsv(_mediaCatalog.value, file)) {
                    _eventFlow.tryEmit(ExtractionEvents.ExportCompleted(file.absolutePath, ExtractionEvents.ExportFormat.CSV))
                } else {
                    _eventFlow.tryEmit(ExtractionEvents.ExportFailed("write failed"))
                }
            } catch (e: Exception) {
                _eventFlow.tryEmit(ExtractionEvents.ExportFailed(e.message ?: "unknown"))
            }
        }
    }

    fun syncPush() {
        viewModelScope.launch {
            try {
                val key = cloudSync.pushManifest(_mediaCatalog.value)
                if (key != null) _eventFlow.tryEmit(ExtractionEvents.SyncPushed(key))
                else _eventFlow.tryEmit(ExtractionEvents.SyncFailed("provider unavailable"))
            } catch (e: Exception) {
                _eventFlow.tryEmit(ExtractionEvents.SyncFailed(e.message ?: "unknown"))
            }
        }
    }

    fun syncPull() {
        viewModelScope.launch {
            try {
                cloudSync.pullManifest()
            } catch (e: Exception) {
                _eventFlow.tryEmit(ExtractionEvents.SyncFailed(e.message ?: "unknown"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    fun isStemSeparationAvailable(): Boolean = stemHook.isAvailable()
    fun providerName(): String = cloudSync.providerName
    fun quarantineDirectory(): File = quarantine.quarantineDir()

    private fun findTrack(id: String): ExtractedTrack? = _mediaCatalog.value.firstOrNull { it.id == id }

    private fun updateTrack(id: String, transform: (ExtractedTrack) -> ExtractedTrack) {
        _mediaCatalog.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun replaceTrack(updated: ExtractedTrack) {
        _mediaCatalog.update { list -> list.map { if (it.id == updated.id) updated else it } }
    }

    private fun handleFileEvent(event: ExtractionFileWatcher.FileChangeEvent) {
        val parent = File(event.path).parent ?: return
        val config = lastConfig ?: return
        scan(listOf(parent), config.copy(customPaths = listOf(parent)))
    }
}
