// app/src/main/java/com/futura/data/extraction/StemSeparationHook.kt
package com.futura.data.extraction

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feature 11 — Stem-separation pipeline hook.
 *
 * Defines the contract any on-device stem-separation model plugs into.
 * The extraction pipeline never calls a stem separator directly — it
 * holds a [StemSeparationHook] injected by Hilt and calls
 * [separateStems] when the user requests it. Swapping in the actual
 * model (once `engine/ml/StemsSeparator.h` and `MlBridge.kt` are
 * delivered and wired) is a one-line Hilt binding change.
 *
 * Two implementations exist today:
 *   • [NoOpStemSeparator]  — the default; returns `NotAvailable`.
 *   • Future `MlBridgeStemSeparator` — will call `MlBridge.separateStems`
 *     via JNI and write the four `FloatArray` outputs to disk as WAV.
 *
 * The result interface deliberately includes only file paths, not raw
 * `FloatArray`s — stems can be many megabytes each, and the UI / DJ
 * Studio / mixer all consume them as files. A `FloatArray` variant can
 * be added later for real-time use; today's API is file-based.
 *
 * Rule 6: implementations must be wrapped; the interface itself is
 * pure.
 */
interface StemSeparationHook {

    /**
     * Separate [source] into four stems and write them to [outputDir].
     * Each written file is a `.wav` (16-bit PCM stereo, source rate).
     *
     * @return [StemResult] with the four paths; on any failure returns
     *         [StemResult.NotAvailable] or [StemResult.Failed] — never
     *         throws.
     */
    suspend fun separateStems(
        source: File,
        outputDir: File
    ): StemResult

    /** True if a real model is currently wired in. */
    fun isAvailable(): Boolean
}

/**
 * Discriminated result of a stem-separation attempt.
 */
sealed interface StemResult {
    /** Four stems were written successfully. */
    data class Success(
        val vocalPath: String,
        val drumPath: String,
        val bassPath: String,
        val otherPath: String
    ) : StemResult

    /** No model is wired in yet (the default today). */
    data object NotAvailable : StemResult

    /** The model was present but failed on this file. */
    data class Failed(val reason: String) : StemResult
}

/**
 * Default [StemSeparationHook] — reports "not available".
 *
 * The extraction ViewModel calls this by default. The user sees "Stems
 * not available — model not installed" in the UI; nothing crashes.
 *
 * When the ML pipeline lands, this class stays in the codebase as a
 * test double; production is wired to a new implementation via the
 * `ExtractionModule` Hilt binding.
 */
@Singleton
class NoOpStemSeparator @Inject constructor() : StemSeparationHook {
    override suspend fun separateStems(
        source: File,
        outputDir: File
    ): StemResult = withContext(Dispatchers.Default) {
        Log.i(TAG, "Stem separation not available for ${source.name}")
        StemResult.NotAvailable
    }

    override fun isAvailable(): Boolean = false

    private companion object {
        const val TAG = "NoOpStemSeparator"
    }
}
