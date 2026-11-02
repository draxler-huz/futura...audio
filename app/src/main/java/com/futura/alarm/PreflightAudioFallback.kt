// app/src/main/java/com/futura/alarm/PreflightAudioFallback.kt
package com.futura.alarm

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage integrity validator that guards the alarm sound pipeline.
 *
 * For each alarm firing, [resolvePlayableUri] attempts to open the
 * configured [AlarmSpec.soundUri]. If the URI is inaccessible (file
 * missing, permission revoked, storage unmounted, corrupt container)
 * the validator falls back to [SystemRingtoneEnumerator.bestFallback],
 * guaranteeing the alarm produces sound even when the user's chosen
 * track is unavailable.
 *
 * The failed URI is remembered (see [markUnplayable]) so subsequent
 * fires short-circuit the expensive open path and go straight to the
 * fallback. The cache is cleared when the user explicitly re-selects
 * the sound (i.e. by [AlarmStore.upsert] on a spec with a changed
 * soundUri — the caller can call [clear] in that flow).
 */
@Singleton
class PreflightAudioFallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ringtoneEnumerator: SystemRingtoneEnumerator
) {
    private val tag = "PreflightAudioFallback"
    private val knownUnplayable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Resolve a playable URI for the given [spec]. Returns the original
     * configured URI when it is readable; otherwise returns the system
     * fallback (which is always readable); otherwise returns null.
     */
    fun resolvePlayableUri(spec: AlarmSpec): Uri? {
        val configured = parseUri(spec.soundUri)
        if (configured != null && !knownUnplayable.contains(spec.soundUri)) {
            if (isReadable(configured)) {
                return configured
            }
            Log.w(tag, "Configured URI unplayable, marking: ${spec.soundUri}")
            knownUnplayable.add(spec.soundUri)
        }

        val fallback = ringtoneEnumerator.bestFallback()
        if (fallback != null && isReadable(fallback)) {
            return fallback
        }

        Log.e(tag, "No playable URI for ${spec.id}; alarm will be silent")
        return null
    }

    /** Explicitly mark a URI as unplayable without opening it. */
    fun markUnplayable(rawUri: String) {
        knownUnplayable.add(rawUri)
    }

    /** Clear the unplayable cache — call when the user changes the sound. */
    fun clear() {
        knownUnplayable.clear()
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun parseUri(raw: String): Uri? = try {
        Uri.parse(raw)
    } catch (e: Exception) {
        Log.w(tag, "Malformed URI: $raw", e)
        null
    }

    /**
     * Opening a file descriptor proves the OS can read the bytes now.
     * Building a Ringtone does not: it can succeed for a missing file,
     * which would leave the alarm silent.
     */
    private fun isReadable(uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: SecurityException) {
            Log.w(tag, "URI access denied: $uri", e)
            false
        } catch (e: Exception) {
            Log.w(tag, "URI not readable: $uri", e)
            false
        }
    }
}
