package com.futura.aurora

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Results from every source that answered. [failedSources] names the ones that did not. */
data class AuroraSearchResult(
    val items: List<AuroraMediaItem>,
    val failedSources: List<String>
)

/**
 * Searches every source at once. One failing source does not hide the others.
 * The license policy is applied by the caller, so changing it does not need
 * a new network request.
 */
@Singleton
class AuroraRepository @Inject constructor(
    private val internetArchive: InternetArchiveSource,
    private val wikimedia: WikimediaCommonsSource
) {

    suspend fun search(query: String, kind: AuroraKind, limit: Int = 20): AuroraSearchResult = coroutineScope {
        val sources: List<AuroraSource> = listOf(internetArchive, wikimedia)
        val outcomes = sources.map { source ->
            async {
                try {
                    Result.success(source.search(query, kind, limit))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.awaitAll()

        val items = mutableListOf<AuroraMediaItem>()
        val failed = mutableListOf<String>()
        outcomes.forEachIndexed { index, outcome ->
            outcome.fold(
                onSuccess = { items += it },
                onFailure = { failed += sources[index].name }
            )
        }
        AuroraSearchResult(
            items = items.distinctBy { it.id }.sortedWith(compareBy({ it.sourceName }, { it.title })),
            failedSources = failed
        )
    }
}
