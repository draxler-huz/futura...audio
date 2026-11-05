// app/src/main/java/com/futura/data/extraction/workflow/F20CloudSyncHook.kt
package com.futura.data.extraction.workflow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.futura.data.extraction.model.ExtractedTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * F20 — Cloud sync integration hook.
 *
 * Also covers the F1-extension side of the accountability matrix: URI
 * access permissions for custom directories. Both responsibilities share
 * this file because a cloud-synced directory is, from the extraction
 * pipeline's perspective, just another custom directory whose content
 * arrives via a provider.
 *
 * ── Two concerns ────────────────────────────────────────────────
 *
 * **1. SAF URI permission persistence (F1-extension)**
 * Android's Storage Access Framework gives apps a `content://` URI with
 * *temporary* read access. For a persistent scan directory, the app must
 * call `ContentResolver.takePersistableUriPermission(...)`. This class
 * does that and reports success as a Boolean. It does not resolve the
 * URI to a filesystem path; see [resolveFilesystemPath].
 *
 * **2. Cloud sync interface (F20)**
 * A [CloudSyncProvider] is anything that can upload/download a
 * [SyncManifest] — WebDAV, S3, Mega, Google Drive, Nextcloud. This file
 * defines the interface and ships a [NoOpCloudSyncProvider] default.
 * The binding lives in [CloudSyncModule]; a production provider replaces
 * that binding.
 *
 * Rule 6: every ContentResolver call wrapped.
 * Rule 7: no shared mutable state in this file — both methods are
 * side-effect-free from the caller's perspective.
 */

/** What a cloud provider must implement to be usable. */
interface CloudSyncProvider {
    /** Human-readable name shown in the sync UI. */
    val name: String

    /** True if the provider is configured and reachable. */
    suspend fun isAvailable(): Boolean

    /**
     * Upload [manifest] to the provider. Returns a remote identifier
     * (path, key, or URL) on success, or null on failure.
     */
    suspend fun upload(manifest: SyncManifest): String?

    /**
     * Download the most recent manifest. Returns null if nothing has
     * been uploaded yet or on failure.
     */
    suspend fun download(): SyncManifest?
}

/** The manifest that crosses the cloud boundary. */
data class SyncManifest(
    val version: Int = 1,
    val generatedAtMs: Long,
    val trackCount: Int,
    /** Compact identity map — id → (sizeBytes, fingerprint). */
    val tracks: List<ManifestEntry>
) {
    data class ManifestEntry(
        val id: String,
        val fileName: String,
        val sizeBytes: Long,
        val fingerprint: String?,
        val format: String
    )

    companion object {
        /**
         * Build a manifest from a track list. Carries the fingerprint when
         * present, so duplicate detection survives cloud round-trips.
         */
        fun from(tracks: List<ExtractedTrack>): SyncManifest = SyncManifest(
            generatedAtMs = System.currentTimeMillis(),
            trackCount = tracks.size,
            tracks = tracks.map { t ->
                ManifestEntry(
                    id = t.id,
                    fileName = t.fileName,
                    sizeBytes = t.sizeBytes,
                    fingerprint = t.fingerprint,
                    format = t.format
                )
            }
        )
    }
}

/**
 * Default no-op provider. Every method returns null/false; the sync UI
 * shows "no provider configured" and nothing happens.
 */
@Singleton
class NoOpCloudSyncProvider @Inject constructor() : CloudSyncProvider {
    override val name: String = "Not configured"
    override suspend fun isAvailable(): Boolean = false
    override suspend fun upload(manifest: SyncManifest): String? = null
    override suspend fun download(): SyncManifest? = null
}

/**
 * The hook the extraction ViewModel calls. Wraps a
 * [CloudSyncProvider] plus the SAF-permission manager for custom
 * directory URIs.
 */
@Singleton
class F20CloudSyncHook @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: CloudSyncProvider
) {
    private val tag = "F20CloudSyncHook"

    // ── F1-extension — SAF URI permissions ─────────────────────

    /**
     * Persist the read permission for a SAF-selected directory.
     * After this returns true, the app can access [uri] across process
     * restarts and reboots without re-prompting the user.
     *
     * @return true if the permission was taken; false otherwise. No
     *         filesystem path is resolved here — see [resolveFilesystemPath].
     */
    suspend fun takePersistablePermission(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Log.i(tag, "Persisted read permission for $uri")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(tag, "takePersistablePermission failed for $uri", e)
                false
            } catch (e: Exception) {
                Log.e(tag, "takePersistablePermission failed for $uri", e)
                false
            }
        }

    /** Release a previously taken permission. */
    suspend fun releasePersistablePermission(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "releasePersistablePermission failed for $uri", e)
                false
            }
        }

    /**
     * Try to resolve a SAF URI to a raw filesystem path so
     * `MediaExtractorService.collectFiles` (group 1) can walk it
     * directly.
     *
     * Only `file://` URIs resolve here. Not all providers expose a raw
     * path — Mega, Google Drive, and S3 deliver content only via the URI
     * stream. Returns null in that case; the caller should then rely on a
     * `CloudSyncProvider`'s stream-based pipeline (a future extension).
     */
    fun resolveFilesystemPath(uri: Uri): String? {
        return try {
            if (uri.scheme == "file") uri.path else null
            // A SAF `tree` URI may or may not expose a raw path via
            // DocumentFile internals; the reliable answer is "no" for
            // most providers. Callers that need this use
            // `DocumentFile.fromTreeUri(...)` and iterate children
            // through ContentResolver — not through File.
        } catch (e: Exception) {
            Log.w(tag, "resolveFilesystemPath failed for $uri", e)
            null
        }
    }

    // ── F20 — Cloud sync ───────────────────────────────────────

    /** True if the injected provider is available and configured. */
    suspend fun isSyncAvailable(): Boolean = try {
        provider.isAvailable()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "isSyncAvailable failed", e)
        false
    }

    /** Upload the current track manifest. Returns the remote key on success. */
    suspend fun pushManifest(tracks: List<ExtractedTrack>): String? {
        return try {
            if (!provider.isAvailable()) return null
            provider.upload(SyncManifest.from(tracks))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "pushManifest failed", e)
            null
        }
    }

    /** Fetch the last uploaded manifest. */
    suspend fun pullManifest(): SyncManifest? = try {
        if (!provider.isAvailable()) null else provider.download()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(tag, "pullManifest failed", e)
        null
    }

    /** Human-readable provider name for the UI. */
    val providerName: String get() = provider.name
}
