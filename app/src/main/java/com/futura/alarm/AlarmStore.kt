// app/src/main/java/com/futura/alarm/AlarmStore.kt
package com.futura.alarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive persistence for [AlarmSpec]s.
 *
 * Two layers, both under this class:
 *   1. In-memory [MutableStateFlow] — the canonical observable state.
 *   2. Encrypted Protobuf-style cache in [EncryptedSharedPreferences] —
 *      the boot-time fast path. Room will be added as the long-term
 *      authoritative store when `FuturaDatabase.kt` lands; the current
 *      schema-free cache is a deliberate first-pass so alarm restoration
 *      works before Room is wired up.
 *
 * Every mutation goes through [writeMutex] so concurrent coroutines
 * cannot interleave read-modify-write cycles. The mutex is short-lived
 * (only held during the synchronous persist step); callers never block
 * on disk I/O inside the mutex.
 */
@Singleton
class AlarmStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "AlarmStore"
    private val writeMutex = Mutex()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "alarm_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _specs = MutableStateFlow<List<AlarmSpec>>(emptyList())
    val specs: StateFlow<List<AlarmSpec>> = _specs.asStateFlow()

    /** Load the persisted set into memory. Must be called once at startup
     *  (FuturaApp.onCreate) before any consumer observes [specs]. */
    suspend fun load() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val raw = prefs.getString(KEY_SPECS, null)
                if (raw == null) {
                    _specs.value = emptyList()
                    return@withLock
                }
                val arr = JSONArray(raw)
                val loaded = ArrayList<AlarmSpec>(arr.length())
                for (i in 0 until arr.length()) {
                    runCatching { jsonToSpec(arr.getJSONObject(i)) }
                        .getOrNull()
                        ?.let { loaded.add(it) }
                }
                _specs.value = loaded
            } catch (e: Exception) {
                Log.e(tag, "Failed to load specs; keeping empty state", e)
                _specs.value = emptyList()
            }
        }
    }

    suspend fun upsert(spec: AlarmSpec) {
        writeMutex.withLock {
            val current = _specs.value.toMutableList()
            val idx = current.indexOfFirst { it.id == spec.id }
            if (idx >= 0) current[idx] = spec else current.add(spec)
            _specs.value = current
            persistLocked(current)
        }
    }

    suspend fun remove(specId: String) {
        writeMutex.withLock {
            val filtered = _specs.value.filterNot { it.id == specId }
            _specs.value = filtered
            persistLocked(filtered)
        }
    }

    suspend fun setEnabled(specId: String, enabled: Boolean) {
        writeMutex.withLock {
            val current = _specs.value.map {
                if (it.id == specId) it.copy(enabled = enabled) else it
            }
            _specs.value = current
            persistLocked(current)
        }
    }

    fun get(specId: String): AlarmSpec? = _specs.value.firstOrNull { it.id == specId }

    // ── Active session (survives process death) ───────────────────

    /** The alarm session as stored: phase is the enum name. */
    data class PersistedSession(
        val specId: String,
        val phase: String,
        val snoozeCount: Int,
        val fireTimeMs: Long
    )

    /** Persist the active session, or clear it when [session] is null. */
    fun saveSession(session: PersistedSession?) {
        try {
            val editor = prefs.edit()
            if (session == null) {
                editor.remove(KEY_SESSION)
            } else {
                editor.putString(KEY_SESSION, JSONObject().apply {
                    put("specId", session.specId)
                    put("phase", session.phase)
                    put("snoozeCount", session.snoozeCount)
                    put("fireTimeMs", session.fireTimeMs)
                }.toString())
            }
            editor.apply()
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist session", e)
        }
    }

    /** Read back the persisted session, or null if none or unreadable. */
    fun loadSession(): PersistedSession? = try {
        val raw = prefs.getString(KEY_SESSION, null)
        if (raw == null) {
            null
        } else {
            val j = JSONObject(raw)
            PersistedSession(
                specId = j.getString("specId"),
                phase = j.getString("phase"),
                snoozeCount = j.optInt("snoozeCount", 0),
                fireTimeMs = j.optLong("fireTimeMs", 0L)
            )
        }
    } catch (e: Exception) {
        Log.e(tag, "Failed to read persisted session", e)
        null
    }

    // ── JSON encoding ─────────────────────────────────────────────

    private fun persistLocked(all: List<AlarmSpec>) {
        try {
            val arr = JSONArray()
            for (spec in all) arr.put(specToJson(spec))
            prefs.edit().putString(KEY_SPECS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist specs", e)
        }
    }

    private fun specToJson(s: AlarmSpec): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("hour", s.hour)
        put("minute", s.minute)
        put("label", s.label)
        put("recurrenceMask", s.recurrenceMask)
        put("soundUri", s.soundUri)
        put("targetVolume", s.targetVolume.toDouble())
        put("rampDurationSeconds", s.rampDurationSeconds)
        put("rampProfile", s.rampProfile.toDouble())
        put("biometricLocked", s.biometricLocked)
        put("enabled", s.enabled)
        put("snoozeBaseMinutes", s.snoozeBaseMinutes)
        put("maxSnoozeCycles", s.maxSnoozeCycles)
        put("createdAt", s.createdAt)
        put("challengeMode", s.challengeMode.name)
        put("failSafe", s.failSafe)
        put("visualSync", JSONObject().apply {
            put("screenFlash", s.visualSync.screenFlash)
            put("smartBulbSync", s.visualSync.smartBulbSync)
            put("flashlight", s.visualSync.flashlight)
        })
        put("calendarExceptions", JSONArray(s.calendarExceptions))
    }

    private fun jsonToSpec(j: JSONObject): AlarmSpec = AlarmSpec(
        id = j.getString("id"),
        hour = j.getInt("hour"),
        minute = j.getInt("minute"),
        label = j.optString("label", ""),
        recurrenceMask = j.optInt("recurrenceMask", 0),
        soundUri = j.getString("soundUri"),
        targetVolume = j.optDouble("targetVolume", 1.0).toFloat(),
        rampDurationSeconds = j.optInt("rampDurationSeconds", 30),
        rampProfile = j.optDouble("rampProfile", 2.0).toFloat(),
        biometricLocked = j.optBoolean("biometricLocked", false),
        enabled = j.optBoolean("enabled", true),
        snoozeBaseMinutes = j.optInt("snoozeBaseMinutes", 5),
        maxSnoozeCycles = j.optInt("maxSnoozeCycles", 3),
        createdAt = j.optLong("createdAt", System.currentTimeMillis()),
        challengeMode = runCatching {
            ChallengeMode.valueOf(j.optString("challengeMode", ChallengeMode.NONE.name))
        }.getOrDefault(ChallengeMode.NONE),
        failSafe = j.optBoolean("failSafe", false),
        visualSync = j.optJSONObject("visualSync")?.let { v ->
            VisualSyncToggles(
                screenFlash = v.optBoolean("screenFlash", false),
                smartBulbSync = v.optBoolean("smartBulbSync", false),
                flashlight = v.optBoolean("flashlight", false)
            )
        } ?: VisualSyncToggles(),
        calendarExceptions = j.optJSONArray("calendarExceptions")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
    )

    private companion object {
        const val KEY_SPECS = "specs_v1"
        const val KEY_SESSION = "active_session_v1"
    }
}