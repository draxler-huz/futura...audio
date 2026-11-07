package com.futura.sources

import android.util.Log
import com.futura.sources.data.SourcePreferences
import com.futura.sources.net.CircuitBreaker
import com.futura.sources.net.SourceKeys
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** The merged result of one search. */
data class SearchResult(
    val items: List<SourceMediaItem>,
    val failedSources: List<String>,
    val skippedSources: List<String>
)

/**
 * Runs every enabled source at once and merges what comes back.
 *
 * A slow source is cut off by [perSourceTimeoutMs]; a broken source is
 * skipped by the circuit breaker. One failure never hides the others, and
 * the filter is applied per item so the licence policy is enforced in one
 * place.
 */
@Singleton
class SearchRepository(
    private val registry: SourceRegistry,
    private val preferences: SourcePreferences,
    private val keys: SourceKeys,
    private val breaker: CircuitBreaker,
    private val adapters: SourceAdapters,
    private val perSourceTimeoutMs: Long
) {

    /** Production constructor; the timeout is not a Kotlin default (see [CircuitBreaker]). */
    @Inject
    constructor(
        registry: SourceRegistry,
        preferences: SourcePreferences,
        keys: SourceKeys,
        breaker: CircuitBreaker,
        adapters: SourceAdapters
    ) : this(registry, preferences, keys, breaker, adapters, perSourceTimeoutMs = 8_000L)

    /** In-flight count, so the UI can show progress while results arrive. */
    private val inFlight = MutableStateFlow(0)
    val inFlightCount: Flow<Int> = inFlight

    /** How many sources are switched on and usable right now. */
    val enabledSourceCount: Flow<Int> = combine(
        preferences.overrides,
        MutableStateFlow(keys.configured)
    ) { overrides, configured ->
        registry.enabledSources(overrides, configured).size
    }

    /** The total number of sources in the catalogue. */
    val totalSourceCount: Int get() = registry.all.size

    suspend fun search(query: String, filter: SearchFilter): SearchResult {
        val overrides = preferences.overrides.first()
        val active = registry.enabledSources(overrides, keys.configured)

        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val runnable = active.filter { config ->
            if (breaker.isAvailable(config.id)) {
                true
            } else {
                skipped += config.id
                false
            }
        }

        inFlight.value = runnable.size
        try {
            val perSource = coroutineScope {
                runnable.map { config ->
                    async { querySource(config, query, filter, failed) }
                }.awaitAll()
            }
            val merged = perSource.flatten()
                .filter { filter.allows(it) }
                .distinctBy { "${it.sourceId}:${it.title.lowercase()}:${it.creator?.lowercase()}" }
                .sortedWith(compareBy({ it.sourceName }, { it.title }))
            return SearchResult(merged, failed, skipped)
        } finally {
            inFlight.value = 0
        }
    }

    private suspend fun querySource(
        config: SourceConfig,
        query: String,
        filter: SearchFilter,
        failed: MutableList<String>
    ): List<SourceMediaItem> {
        val adapter = adapters.forId(config.id)
        // A source can be declared in the registry before its adapter exists.
        // Treat that as "not queried", not as a failure, so it never shows up
        // in the failure list or trips the breaker.
        if (adapter == null) return emptyList()
        return try {
            val result = withTimeout(perSourceTimeoutMs) { adapter.search(query, filter) }
            breaker.recordSuccess(config.id)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Source ${config.id} failed", e)
            breaker.recordFailure(config.id)
            failed += config.id
            emptyList()
        }
    }

    private companion object {
        const val TAG = "SearchRepository"
    }
}
