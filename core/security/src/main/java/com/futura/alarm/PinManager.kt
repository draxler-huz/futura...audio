// core/security/src/main/java/com/futura/alarm/PinManager.kt
package com.futura.alarm

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets and changes the user's vault PIN.
 *
 * Rules:
 *   • A PIN is exactly [PIN_LENGTH] digits.
 *   • [createPin] works only when no PIN exists (first sign-in).
 *   • [changePin] needs a fresh [VaultUnlockProof], so the PIN cannot be
 *     replaced by someone who only knows the current screen state.
 *
 * Stored through [DecoyPinStore] with a fresh salt and PBKDF2 hash.
 */
@Singleton
class PinManager @Inject internal constructor(
    private val store: DecoyPinStore,
    private val verifier: DecoyPinVerifier,
    private val salts: KeypadSaltEngine
) {

    /** True once the user has set a PIN. */
    suspend fun hasPin(): Boolean = store.hasPin()

    /** First sign-in only. Refuses if a PIN already exists. */
    suspend fun createPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        if (store.hasPin()) return false
        return writePin(pin)
    }

    /** Replaces the PIN. Requires a fresh, unspent unlock proof. */
    suspend fun changePin(newPin: String, proof: VaultUnlockProof): Boolean {
        if (!isValidPin(newPin)) return false
        if (!VaultUnlockLedger.spend(proof)) return false
        return writePin(newPin)
    }

    private suspend fun writePin(pin: String): Boolean {
        val salt = salts.newSalt() ?: return false
        val iterations = salts.deriveIterationCount(Build.FINGERPRINT.hashCode())
        val hash = verifier.deriveHash(pin, salt, iterations) ?: return false
        return store.putPin(DecoyPinStore.PinRecord(salt = salt, iterations = iterations, hash = hash))
    }

    companion object {
        const val PIN_LENGTH = 6

        fun isValidPin(pin: String): Boolean =
            pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
    }
}
