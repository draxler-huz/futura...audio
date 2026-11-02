package com.futura.alarm

import android.util.Base64
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proof that a verification engine accepted the user, for one unlock.
 *
 * The constructor is `internal`, not `private`. A private constructor
 * would stop the issuing engines from building the token at all. Kotlin
 * cannot restrict an `internal` constructor further inside one module,
 * so the real control is this: the only sanctioned path is
 * [VaultUnlockTokens.issue], called by BiometricWakeUpLock on a
 * successful prompt and by DecoyPinVerifier on a matching hash.
 *
 * A token is worth something only while [VaultUnlockTokens] still holds
 * its nonce. It is single-use (consumed on first check) and expires
 * after [VaultUnlockTokens.TTL_MS].
 */
class VaultUnlockToken internal constructor(
    internal val nonce: String
)

/**
 * Issues and consumes [VaultUnlockToken]s. Holds the set of live nonces.
 */
@Singleton
class VaultUnlockTokens @Inject constructor() {
    private val lock = Any()
    private val random = SecureRandom()

    /** nonce → expiry (epoch ms). Only entries still inside their TTL remain. */
    private val live = HashMap<String, Long>()

    /** Mint a fresh token. Call only after a successful verification. */
    internal fun issue(now: Long = System.currentTimeMillis()): VaultUnlockToken {
        val bytes = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val nonce = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        synchronized(lock) {
            live.entries.removeAll { it.value < now }
            live[nonce] = now + TTL_MS
        }
        return VaultUnlockToken(nonce)
    }

    /**
     * Spend [token]. Returns `true` only if it was issued by this
     * instance, has not been spent, and has not expired. Spending it
     * removes it, so a second call with the same token returns `false`.
     */
    fun consume(token: VaultUnlockToken, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val expiresAt = live.remove(token.nonce) ?: return false
            expiresAt >= now
        }

    companion object {
        /** How long a verified token remains usable. */
        const val TTL_MS = 30_000L
        private const val NONCE_BYTES = 16
    }
}
