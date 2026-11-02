package com.futura.alarm

import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cryptographic salt generator for PIN derivation.
 *
 * Generates a fresh 16-byte salt on request from [SecureRandom] (the
 * platform's CSPRNG, seeded by the OS entropy pool). The salt is what
 * makes a small PIN space (a few thousand possible PINs) computationally
 * infeasible to rainbow-table even if the encrypted store's ciphertext
 * were extracted.
 *
 * The class exposes two useful operations:
 *   • [newSalt] — generate and return the encoded salt.
 *   • [deriveIterationCount] — derive a per-install iteration count
 *     (work factor) that both increases resistance and lets the caller
 *     bound verification latency to a constant budget on any device.
 */
@Singleton
class KeypadSaltEngine @Inject constructor() {
    private val tag = "KeypadSaltEngine"
    private val secureRandom = SecureRandom()

    /**
     * Generate a fresh 16-byte salt, Base64-encoded (URL-safe, no
     * padding). Returns `null` only on catastrophic RNG failure — the
     * Java `SecureRandom` never realistically fails on Android, but the
     * null-return contract makes the failure explicit at the callsite.
     */
    fun newSalt(): String? {
        return try {
            val bytes = ByteArray(SALT_BYTES)
            secureRandom.nextBytes(bytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } catch (e: Exception) {
            Log.e(tag, "newSalt failed", e)
            null
        }
    }

    /**
     * Derive a PBKDF2 iteration count for the current install.
     *
     * The value is deterministic per [installSeed] (typically
     * `Build.FINGERPRINT.hashCode()`), so the same device always derives
     * the same iteration count. Range: 60_000–120_000 — high enough for
     * meaningful resistance, low enough that a single PIN verification
     * on a mid-range ARM phone takes ~60–200 ms.
     *
     * The count is stored alongside each PIN record ([DecoyPinStore]),
     * so a later OS update that changes the fingerprint does not break
     * verification of an existing PIN.
     */
    fun deriveIterationCount(installSeed: Int): Int {
        val clamped = (installSeed.toLong() and 0xFFFFFFFFL)
        val base = (clamped % (MAX_ITERATIONS - MIN_ITERATIONS + 1)).toInt()
        return MIN_ITERATIONS + base
    }

    private companion object {
        const val SALT_BYTES = 16
        const val MIN_ITERATIONS = 60_000
        const val MAX_ITERATIONS = 120_000
    }
}
