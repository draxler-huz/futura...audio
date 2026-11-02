package com.futura.alarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Monotonic hardware-backed storage for decoy PIN hashes.
 *
 * Stores a **single** active decoy PIN plus its salt and iteration
 * count. The plaintext PIN is never persisted, never logged, and never
 * held in memory longer than the verification call.
 *
 * Hash algorithm: PBKDF2-HMAC-SHA256.
 * Storage: `EncryptedSharedPreferences` (AES-256-SIV keys, AES-256-GCM
 * values, backed by the Android Keystore).
 *
 * The "monotonic" aspect: once a decoy PIN has been set, it can be
 * replaced but not removed — the user cannot accidentally disable the
 * decoy by clearing its record. Only [destroy] (called from
 * [VaultManager] on max lockout) removes it.
 *
 * Writes use `commit()` rather than `apply()`, so a `true` result means
 * the record is on disk. Callers rely on that to know the decoy exists.
 */
@Singleton
class DecoyPinStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "DecoyPinStore"
    private val mutex = Mutex()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "decoy_pin_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class PinRecord(
        val salt: String,
        val iterations: Int,
        val hash: String
    )

    /** True if a decoy PIN has been set. */
    suspend fun hasPin(): Boolean = withContext(Dispatchers.IO) {
        try {
            prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)
        } catch (e: Exception) {
            Log.e(tag, "hasPin failed", e)
            false
        }
    }

    /** Persist the hashed decoy PIN. Overwrites any existing record.
     *  Returns `true` only once the write is committed to disk. */
    suspend fun putPin(record: PinRecord): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                prefs.edit()
                    .putString(KEY_HASH, record.hash)
                    .putString(KEY_SALT, record.salt)
                    .putInt(KEY_ITER, record.iterations)
                    .commit()
            } catch (e: Exception) {
                Log.e(tag, "putPin failed", e)
                false
            }
        }
    }

    /** Read the stored record, or `null` if unset or incomplete.
     *  A hash with no iteration count is treated as corrupt, not
     *  defaulted, because verifying with a guessed count can never match. */
    suspend fun getPin(): PinRecord? = withContext(Dispatchers.IO) {
        try {
            val hash = prefs.getString(KEY_HASH, null) ?: return@withContext null
            val salt = prefs.getString(KEY_SALT, null) ?: return@withContext null
            if (!prefs.contains(KEY_ITER)) {
                Log.e(tag, "decoy PIN record is missing its iteration count")
                return@withContext null
            }
            val iter = prefs.getInt(KEY_ITER, 0)
            PinRecord(salt = salt, iterations = iter, hash = hash)
        } catch (e: Exception) {
            Log.e(tag, "getPin failed", e)
            null
        }
    }

    /** Wipe the decoy PIN. Only [VaultManager] should call this.
     *  Returns `true` only once the removal is committed to disk. */
    suspend fun destroy(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                prefs.edit()
                    .remove(KEY_HASH)
                    .remove(KEY_SALT)
                    .remove(KEY_ITER)
                    .commit()
            } catch (e: Exception) {
                Log.e(tag, "destroy failed", e)
                false
            }
        }
    }

    private companion object {
        const val KEY_HASH = "pin_hash_v1"
        const val KEY_SALT = "pin_salt_v1"
        const val KEY_ITER = "pin_iter_v1"
    }
}
