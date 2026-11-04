// core/security/src/main/java/com/futura/alarm/LockoutPolicyStore.kt
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
import kotlinx.coroutines.withContext

/**
 * The PIN lockout rule the user has chosen.
 *
 * Only the values in [ALLOWED_FAILURES] and [ALLOWED_DURATIONS_MS] can be
 * represented, so a stored or edited value outside them cannot take effect.
 */
data class LockoutPolicy(val maxFailures: Int, val lockoutMs: Long) {
    init {
        require(maxFailures in ALLOWED_FAILURES) { "maxFailures out of range: $maxFailures" }
        require(lockoutMs in ALLOWED_DURATIONS_MS) { "lockoutMs not allowed: $lockoutMs" }
    }

    companion object {
        val ALLOWED_FAILURES: IntRange = 3..10
        val ALLOWED_DURATIONS_MS: List<Long> = listOf(15_000L, 30_000L, 60_000L, 300_000L)
        val DEFAULT = LockoutPolicy(maxFailures = 3, lockoutMs = 30_000L)
    }
}

/**
 * Encrypted storage for [LockoutPolicy]. Same storage scheme as
 * [DecoyPinStore]: `EncryptedSharedPreferences`, backed by the Keystore.
 *
 * Reads fall back to [LockoutPolicy.DEFAULT] for missing or invalid values,
 * so corruption can never produce a looser lockout than the default.
 */
@Singleton
class LockoutPolicyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "LockoutPolicyStore"

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "lockout_policy_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** The stored policy, or [LockoutPolicy.DEFAULT] if none is valid. */
    suspend fun load(): LockoutPolicy = withContext(Dispatchers.IO) {
        try {
            val failures = prefs.getInt(KEY_FAILURES, LockoutPolicy.DEFAULT.maxFailures)
            val duration = prefs.getLong(KEY_DURATION_MS, LockoutPolicy.DEFAULT.lockoutMs)
            if (failures in LockoutPolicy.ALLOWED_FAILURES &&
                duration in LockoutPolicy.ALLOWED_DURATIONS_MS
            ) {
                LockoutPolicy(failures, duration)
            } else {
                Log.w(tag, "stored lockout policy invalid; using default")
                LockoutPolicy.DEFAULT
            }
        } catch (e: Exception) {
            Log.e(tag, "load failed; using default", e)
            LockoutPolicy.DEFAULT
        }
    }

    /**
     * Save [policy] only if [proof] is a fresh, unspent unlock proof.
     * Changing the lockout is a security decision, so it needs the same
     * fresh biometric or PIN proof as changing a shielded alarm.
     *
     * Returns `true` only once the write is committed to disk.
     */
    suspend fun saveWithProof(policy: LockoutPolicy, proof: VaultUnlockProof): Boolean =
        withContext(Dispatchers.IO) {
            if (!VaultUnlockLedger.spend(proof)) {
                Log.w(tag, "saveWithProof: proof rejected")
                return@withContext false
            }
            try {
                prefs.edit()
                    .putInt(KEY_FAILURES, policy.maxFailures)
                    .putLong(KEY_DURATION_MS, policy.lockoutMs)
                    .commit()
            } catch (e: Exception) {
                Log.e(tag, "save failed", e)
                false
            }
        }

    private companion object {
        const val KEY_FAILURES = "max_failures_v1"
        const val KEY_DURATION_MS = "lockout_ms_v1"
    }
}
