package com.futura.alarm

import android.util.Base64
import android.util.Log
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Constant-time verifier for decoy PINs.
 *
 * Computes PBKDF2-HMAC-SHA256 over the entered PIN using the stored
 * salt + iteration count, then compares the resulting bytes against the
 * stored hash in **constant time** — no early exit, no length-dependent
 * timing, no branch that would let an attacker learn anything about the
 * correct hash beyond "match" vs "no match".
 *
 * The entered PIN is zeroed from the char array immediately after use.
 * [PBEKeySpec] keeps its own copy of the password, so [PBEKeySpec.clearPassword]
 * is called as well. The intermediate derived bytes are zeroed too. All of
 * this is best-effort: the JVM and the crypto provider may copy the data
 * on the way through, but the discipline is correct.
 */
@Singleton
class DecoyPinVerifier @Inject constructor(
    private val store: DecoyPinStore
) {
    private val tag = "DecoyPinVerifier"

    /**
     * Verify [enteredPin] against the stored decoy PIN.
     *
     * Returns:
     *   • `true`  — the entered PIN matches.
     *   • `false` — the entered PIN does not match.
     *   • `null`  — no decoy PIN is set, or verification could not run
     *               (crypto unavailable, corrupted record). Callers
     *               MUST distinguish this from `false`: an unverifiable
     *               PIN attempt must not be treated as a legitimate
     *               failure that increments the access auditor's
     *               lockout counter.
     */
    suspend fun verify(enteredPin: String): Boolean? = withContext(Dispatchers.Default) {
        try {
            val record = store.getPin() ?: return@withContext null
            val saltBytes = Base64.decode(
                record.salt,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            val expectedHash = Base64.decode(
                record.hash,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )

            val pinChars = enteredPin.toCharArray()
            var spec: PBEKeySpec? = null
            var derived: ByteArray? = null
            try {
                spec = PBEKeySpec(pinChars, saltBytes, record.iterations, HASH_BITS)
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                derived = factory.generateSecret(spec).encoded
                constantTimeEquals(derived, expectedHash)
            } finally {
                spec?.clearPassword()
                pinChars.fill('\u0000')
                derived?.fill(0)
            }
        } catch (e: Exception) {
            Log.e(tag, "verify failed", e)
            null
        }
    }

    /** Derive the hash for a new PIN using the given salt + iterations.
     *  Used by the PIN-setup flow (not delivered yet). */
    suspend fun deriveHash(pin: String, salt: String, iterations: Int): String? =
        withContext(Dispatchers.Default) {
            try {
                val saltBytes = Base64.decode(
                    salt, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
                val pinChars = pin.toCharArray()
                var spec: PBEKeySpec? = null
                var derived: ByteArray? = null
                try {
                    spec = PBEKeySpec(pinChars, saltBytes, iterations, HASH_BITS)
                    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    derived = factory.generateSecret(spec).encoded
                    Base64.encodeToString(
                        derived,
                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                    )
                } finally {
                    spec?.clearPassword()
                    pinChars.fill('\u0000')
                    derived?.fill(0)
                }
            } catch (e: Exception) {
                Log.e(tag, "deriveHash failed", e)
                null
            }
        }

    // ── Constant-time compare ─────────────────────────────────────
    //
    // Evaluates every byte of both arrays, accumulating the XOR result
    // into a single accumulator. Any early-exit optimisation here would
    // reintroduce a timing side-channel; the diff-or-accumulate pattern
    // is the canonical constant-time compare. The length check exits
    // early, but the length of a SHA-256 digest is public, so that leaks
    // nothing.
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private companion object {
        const val HASH_BITS = 256
    }
}
