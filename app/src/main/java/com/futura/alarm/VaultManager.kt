package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle controller for the encrypted vault.
 *
 * Two responsibilities, exactly:
 *
 *   1. **Gatekeep the vault after biometric or PIN verification.**
 *      The vault is LOCKED until [unlock] is called from a caller that
 *      has already verified the user (`BiometricWakeUpLock.prompt`
 *      succeeded, or `DecoyPinVerifier.verify` returned `true`). While
 *      LOCKED, [encryptPayload] and [decryptPayload] return null; only
 *      an unlocked vault can read or write secrets.
 *
 *   2. **Coordinate key destruction.** When the security lockout
 *      threshold is reached, the vault's master key AND the decoy PIN
 *      record must be destroyed together. Leaving either behind
 *      produces an inconsistent state — a live key with no PIN means
 *      an un-gatekeepable vault; a live PIN with no key means a decoy
 *      that unlocks nothing. [destroy] wipes both under one lock.
 *
 * The vault *content* (encrypted audio, redacted metadata) is not
 * managed here. That belongs to `system/DecoyVaultCleanupEngine.kt`
 * (Phase 6, not delivered). This class is the authorization layer that
 * gatekeeps it.
 *
 * Rule 7: every public method holds [mutex] for its whole duration —
 * the vault state and the two underlying stores must never be observed
 * mid-transition.
 */
@Singleton
class VaultManager @Inject constructor(
    private val crypto: VaultCrypto,
    private val pinStore: DecoyPinStore,
    private val auditor: SecurityAccessAuditor
) {
    private val tag = "VaultManager"
    private val mutex = Mutex()

    enum class State { LOCKED, UNLOCKED, DESTROYED }

    private val _state = MutableStateFlow(State.LOCKED)
    val state: StateFlow<State> = _state.asStateFlow()

    // ── Gate ────────────────────────────────────────────────────────

    /**
     * Unlock the vault. Must be called only after the caller has
     * verified the user (biometric or PIN). On success, subsequent
     * [encryptPayload] / [decryptPayload] calls succeed until [lock] or
     * [destroy] is called.
     *
     * First-run behaviour: if the master key does not yet exist, this
     * method generates it by encrypting an empty payload as a sentinel.
     * This means the very first `unlock()` doubles as key initialisation
     * — no separate provisioning step is needed.
     *
     * @return `true` on successful unlock; `false` on any failure (key
     *         generation failed, key was destroyed, or the vault is
     *         already DESTROYED and cannot be re-opened without a full
     *         reset flow).
     */
    suspend fun unlock(): Boolean = mutex.withLock {
        if (_state.value == State.DESTROYED) {
            Log.w(tag, "unlock: vault is DESTROYED; refusing")
            return@withLock false
        }
        try {
            if (!crypto.keyExists()) {
                val sentinel = crypto.encrypt(ByteArray(0))
                if (sentinel == null) {
                    Log.e(tag, "unlock: master key initialisation failed")
                    return@withLock false
                }
            }
            _state.value = State.UNLOCKED
            Log.i(tag, "Vault unlocked")
            true
        } catch (e: Exception) {
            Log.e(tag, "unlock failed", e)
            false
        }
    }

    /**
     * Return to LOCKED. Called when the app backgrounds, when the
     * privacy vault screen is dismissed, or when the alarm session ends
     * and the vault was only unlocked to service the alarm flow.
     *
     * No-op if already LOCKED or DESTROYED.
     */
    suspend fun lock() = mutex.withLock {
        if (_state.value == State.UNLOCKED) {
            _state.value = State.LOCKED
            Log.i(tag, "Vault locked")
        }
    }

    // ── Destruction ─────────────────────────────────────────────────

    /**
     * Destroy the vault: delete the master key AND the decoy PIN record
     * together.
     *
     * Called by:
     *   • The lockout enforcer (future — will live in
     *     `system/DecoyVaultCleanupEngine.kt`) when
     *     [SecurityAccessAuditor.failures] exceeds the destroy threshold.
     *   • The user, from the privacy settings panel, to wipe the vault
     *     deliberately.
     *
     * Once DESTROYED, the vault cannot be re-opened without a fresh
     * PIN-setup flow, which will regenerate the master key via
     * [unlock]'s first-run path.
     *
     * @return `true` if both the key and the PIN record were destroyed
     *         cleanly; `false` if either operation failed. The vault
     *         state is set to DESTROYED regardless — a partial failure
     *         should not leave the vault appearing usable.
     */
    suspend fun destroy(): Boolean = mutex.withLock {
        try {
            val keyOk = crypto.destroyKey()
            val pinOk = pinStore.destroy()
            _state.value = State.DESTROYED
            Log.w(tag, "Vault destroyed (keyOk=$keyOk, pinOk=$pinOk)")
            keyOk && pinOk
        } catch (e: Exception) {
            Log.e(tag, "destroy failed", e)
            _state.value = State.DESTROYED
            false
        }
    }

    // ── Payload access (gatekept) ───────────────────────────────────

    /**
     * Encrypt [plaintext] under the vault master key.
     *
     * @return ciphertext, or `null` if the vault is not UNLOCKED, or if
     *         the underlying [VaultCrypto.encrypt] failed.
     */
    suspend fun encryptPayload(plaintext: ByteArray): ByteArray? = mutex.withLock {
        if (_state.value != State.UNLOCKED) {
            Log.w(tag, "encryptPayload: vault not unlocked")
            return@withLock null
        }
        try {
            crypto.encrypt(plaintext)
        } catch (e: Exception) {
            Log.e(tag, "encryptPayload failed", e)
            null
        }
    }

    /**
     * Decrypt [payload] under the vault master key.
     *
     * @return plaintext, or `null` if the vault is not UNLOCKED, or if
     *         decryption failed (tag mismatch, key rotation, corrupted
     *         payload).
     */
    suspend fun decryptPayload(payload: ByteArray): ByteArray? = mutex.withLock {
        if (_state.value != State.UNLOCKED) {
            Log.w(tag, "decryptPayload: vault not unlocked")
            return@withLock null
        }
        try {
            crypto.decrypt(payload)
        } catch (e: Exception) {
            Log.e(tag, "decryptPayload failed", e)
            null
        }
    }

    // ── Introspection ───────────────────────────────────────────────

    /** Convenience — true only when the vault is UNLOCKED. */
    fun isUnlocked(): Boolean = _state.value == State.UNLOCKED

    /** Convenience — true only when the vault has been DESTROYED. */
    fun isDestroyed(): Boolean = _state.value == State.DESTROYED

    /**
     * Reset after destruction. Allows the user to re-provision the
     * vault (via PIN setup → biometric enrol → [unlock]) following a
     * deliberate destroy. Only callable from DESTROYED; a no-op in any
     * other state.
     */
    suspend fun resetAfterDestroy(): Boolean = mutex.withLock {
        if (_state.value != State.DESTROYED) return@withLock false
        try {
            auditor.reset()
            _state.value = State.LOCKED
            Log.i(tag, "Vault reset from DESTROYED to LOCKED")
            true
        } catch (e: Exception) {
            Log.e(tag, "resetAfterDestroy failed", e)
            false
        }
    }
}
