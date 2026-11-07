package com.futura.aurora.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.futura.aurora.AuroraKind
import com.futura.aurora.AuroraMediaItem
import com.futura.aurora.AuroraRepository
import com.futura.aurora.LicensePolicy
import com.futura.aurora.download.AuroraDownloadScheduler
import com.futura.aurora.download.AuroraDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 300L
private val ACTIVE_STATES = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
private val FAILED_STATES = setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)

internal fun WorkInfo.State?.isActive(): Boolean = this != null && this in ACTIVE_STATES
internal fun WorkInfo.State?.isFailed(): Boolean = this != null && this in FAILED_STATES

/** Latest state of one download, mirrored from WorkManager. */
data class DownloadJob(
    val state: WorkInfo.State,
    val percent: Int,
    val error: String?,
    val attribution: String?
)

data class AuroraUiState(
    val kind: AuroraKind = AuroraKind.AUDIO,
    val query: String = "",
    val policy: LicensePolicy = LicensePolicy.COMMERCIAL,
    val loading: Boolean = false,
    val searched: Boolean = false,
    /** Every result the sources returned, before the license policy. */
    val all: List<AuroraMediaItem> = emptyList(),
    val failedSources: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val jobs: Map<String, DownloadJob> = emptyMap(),
    /** Item ids of the latest batch, so the bottom bar can summarize it. */
    val batch: List<String> = emptyList(),
    val attributionText: String? = null,
    val sheetItemId: String? = null
) {
    /** Results allowed by the current license policy. Switching policy changes this instantly. */
    val visible: List<AuroraMediaItem>
        get() = all.filter { policy.allows(it.license) }
}

@HiltViewModel
class AuroraViewModel @Inject constructor(
    private val repository: AuroraRepository,
    private val scheduler: AuroraDownloadScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(AuroraUiState())
    val state: StateFlow<AuroraUiState> = _state.asStateFlow()

    private val searchInput = MutableStateFlow("" to AuroraKind.AUDIO)
    private val observers = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            searchInput
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { (query, kind) ->
                    if (query.isBlank()) {
                        _state.update {
                            it.copy(loading = false, searched = false, all = emptyList(), failedSources = emptyList())
                        }
                        return@collectLatest
                    }
                    _state.update { it.copy(loading = true) }
                    val outcome = repository.search(query, kind)
                    _state.update {
                        it.copy(
                            loading = false,
                            searched = true,
                            all = outcome.items,
                            failedSources = outcome.failedSources,
                            selected = emptySet()
                        )
                    }
                }
        }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        searchInput.value = text to searchInput.value.second
    }

    fun onKindChange(kind: AuroraKind) {
        _state.update { it.copy(kind = kind, selected = emptySet()) }
        searchInput.value = searchInput.value.first to kind
    }

    fun togglePolicy() {
        _state.update {
            it.copy(policy = if (it.policy == LicensePolicy.STRICT) LicensePolicy.COMMERCIAL else LicensePolicy.STRICT)
        }
    }

    fun clearFilters() {
        _state.update { it.copy(policy = LicensePolicy.COMMERCIAL) }
    }

    fun toggleSelected(id: String) {
        _state.update { s ->
            s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun openSheet(id: String?) {
        _state.update { it.copy(sheetItemId = id) }
    }

    fun dismissAttribution() {
        _state.update { it.copy(attributionText = null) }
    }

    fun downloadSelected() = enqueue(_state.value.selected.toList())

    fun download(id: String) = enqueue(listOf(id))

    fun cancelBatch() {
        val s = _state.value
        s.batch.filter { s.jobs[it]?.state.isActive() }.forEach { scheduler.cancel(it) }
    }

    fun retryFailed() {
        val s = _state.value
        enqueue(s.batch.filter { s.jobs[it]?.state.isFailed() })
    }

    private fun enqueue(ids: List<String>) {
        val items = _state.value.all.filter { it.id in ids }
        if (items.isEmpty()) return
        val queued = items.map { it.id }
        _state.update { s ->
            val stillActive = s.batch.filter { s.jobs[it]?.state.isActive() }
            s.copy(
                selected = s.selected - queued.toSet(),
                batch = (stillActive + queued).distinct()
            )
        }
        viewModelScope.launch {
            items.forEach { item ->
                scheduler.enqueue(item)
                    .onSuccess { observe(item.id) }
                    .onFailure { e ->
                        _state.update { s ->
                            s.copy(jobs = s.jobs + (item.id to DownloadJob(WorkInfo.State.FAILED, 0, e.message, null)))
                        }
                    }
            }
        }
    }

    private fun observe(itemId: String) {
        if (observers[itemId]?.isActive == true) return
        observers[itemId] = viewModelScope.launch {
            scheduler.observe(itemId).collect { info ->
                if (info == null) return@collect
                val job = DownloadJob(
                    state = info.state,
                    percent = if (info.state == WorkInfo.State.SUCCEEDED) {
                        100
                    } else {
                        info.progress.getInt(AuroraDownloadWorker.KEY_PROGRESS, 0)
                    },
                    error = info.outputData.getString(AuroraDownloadWorker.KEY_ERROR),
                    attribution = info.outputData.getString(AuroraDownloadWorker.KEY_ATTRIBUTION)
                        ?.takeIf { it.isNotBlank() }
                )
                _state.update { s ->
                    val justFinished = info.state == WorkInfo.State.SUCCEEDED &&
                        s.jobs[itemId]?.state != WorkInfo.State.SUCCEEDED
                    s.copy(
                        jobs = s.jobs + (itemId to job),
                        // Only CC-BY style items carry attribution text; the popup shows once per finish.
                        attributionText = if (justFinished) job.attribution ?: s.attributionText else s.attributionText
                    )
                }
            }
        }
    }
}
