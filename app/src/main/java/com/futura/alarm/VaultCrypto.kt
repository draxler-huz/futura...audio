package com.futura.alarm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android Keystore-backed AES-256-GCM cipher provider.
 *
 * The master key is generated once under alias [MASTER_KEY_ALIAS] with
 * `setUserAuthenticationRequired(false)` — vault data must be accessible
 * without the user re-authenticating every time the app reads a decoy
 * PIN hash. The biometric prompt controls *authorisation for the vault
 * to unlock its session*, not each individual decrypt. The key is held
 * in the Android Keystore and is not exportable; on devices with a
 * hardware-backed Keystore it never leaves secure hardware.
 *
 * Cipher output layout: `[12-byte IV] || [GCM ciphertext + 16-byte tag]`.
 * Every call to [encrypt] generates a fresh IV from a `SecureRandom`
 * (delegated to the KeyStore-managed `Cipher`), guaranteeing nonce
 * uniqueness — GCM nonce reuse with the same key would be catastrophic.
 *
 * Rule 6: every cryptographic operation is wrapped in `try/catch` —
 * `GeneralSecurityException`, `InvalidKeyException`, and
 * `AEADBadTagException` all surface as `null` / `false` with a logged
 * reason.
 *
 * Rule 7: `mutex` serialises [encrypt] / [decrypt] so the shared
 * `KeyStore` instance (a singleton in the Android framework) is never
 * concurrently accessed across threads. This is not strictly required
 * by the Android SDK, but it eliminates a class of flaky
 * `AEADBadTagException` failures under aggressive concurrency.
 */
@Singleton
class VaultCrypto @Inject constructor() {
    private val tag = "VaultCrypto"
    private val mutex = Mutex()

    /**
     * Encrypt [plaintext] under the vault master key. Returns
     * `IV || ciphertext` bytes, or `null` on failure.
     */
    suspend fun encrypt(plaintext: ByteArray): ByteArray? = mutex.withLock {
        try {
            val key = ensureKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            ByteArray(iv.size + ciphertext.size).also {
                System.arraycopy(iv, 0, it, 0, iv.size)
                System.arraycopy(ciphertext, 0, it, iv.size, ciphertext.size)
            }
        } catch (e: Exception) {
            Log.e(tag, "encrypt failed", e)
            null
        }
    }

    /**
     * Decrypt [payload] (as produced by [encrypt]). Returns the original
     * plaintext, or `null` on any failure — including tag mismatch
     * (which signals tampering or key rotation) and a missing master key.
     *
     * Unlike [encrypt], this never creates a key. Decrypting with no key
     * present means the vault was destroyed, so generating a fresh key
     * here would silently replace it.
     */
    suspend fun decrypt(payload: ByteArray): ByteArray? = mutex.withLock {
        try {
            if (payload.size < IV_SIZE + TAG_SIZE) {
                Log.w(tag, "Payload too short (${payload.size} bytes)")
                return@withLock null
            }
            val key = existingKey()
            if (key == null) {
                Log.w(tag, "decrypt called with no master key")
                return@withLock null
            }
            val iv = payload.copyOfRange(0, IV_SIZE)
            val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_SIZE_BITS, iv)
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(tag, "decrypt failed", e)
            null
        }
    }

    /** Destroy the master key. Used by [VaultManager] on max lockout. */
    suspend fun destroyKey(): Boolean = mutex.withLock {
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            if (ks.containsAlias(MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MASTER_KEY_ALIAS)
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "destroyKey failed", e)
            false
        }
    }

    /** True if the master key exists in the Keystore. */
    suspend fun keyExists(): Boolean = mutex.withLock {
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            ks.containsAlias(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    // ── Internals ──────────────────────────────────────────────────

    /** The master key if it exists, otherwise `null`. Never creates one. */
    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        return ks.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
    }

    /** The master key, generating it on first use. */
    private fun ensureKey(): SecretKey {
        existingKey()?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // No biometric gating on the key itself; the vault
                // session is gated by BiometricWakeUpLock / PIN instead.
                // See class KDoc.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "futura_vault_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val TAG_SIZE_BITS = TAG_SIZE * 8
    }
}
