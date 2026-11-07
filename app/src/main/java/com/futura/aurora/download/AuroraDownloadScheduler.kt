package com.futura.aurora.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.futura.aurora.AuroraMediaItem
import com.futura.aurora.InternetArchiveSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AuroraDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val archive: InternetArchiveSource
) {

    /**
     * Queues one download. A repeat request for an item that is already queued or
     * running is ignored, so a double tap does not start a second copy.
     */
    suspend fun enqueue(item: AuroraMediaItem): Result<UUID> = runCatching {
        val url = item.downloadUrl
            ?: (if (item.id.startsWith("archive:")) archive.resolveDownloadUrl(item) else null)
            ?: error("No download link for this item")
        val attribution = if (item.license.requiresAttribution) item.attributionText() else ""
        val request = OneTimeWorkRequestBuilder<AuroraDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    AuroraDownloadWorker.KEY_URL to url,
                    AuroraDownloadWorker.KEY_FILE_NAME to DownloadRules.fileNameFor(item.title, url),
                    AuroraDownloadWorker.KEY_ATTRIBUTION to attribution
                )
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(item.id), ExistingWorkPolicy.KEEP, request)
        request.id
    }

    /** Live state of the download for [itemId], or null if none was queued. */
    fun observe(itemId: String): Flow<WorkInfo?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workName(itemId))
            .map { it.firstOrNull() }

    fun cancel(itemId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(itemId))
    }

    private fun workName(itemId: String) = "aurora-download-$itemId"
}
