package com.futura.alarm

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.futura.core.security.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Proof that a verification engine accepted the user, valid for one unlock.
 *
 * Public so that [VaultManager] can name it in its signatures. Nothing
 * outside this file can implement it: the only implementation,
 * [IssuedProof], is file-private, and only the file-private [mintProof]
 * creates one. Other code can hold a proof but cannot manufacture one.
 */
interface VaultUnlockProof

private class IssuedProof(val nonce: String, val expiresAtMs: Long) : VaultUnlockProof

private const val PROOF_TTL_MS = 30_000L
private const val PROOF_NONCE_BYTES = 16
private val proofRandom = SecureRandom()
private val proofLock = Any()

/** nonce → expiry. Holds only proofs that are unspent and inside their TTL. */
private val liveProofs = HashMap<String, Long>()

/** Mint a proof. File-private, so only verification in this file can call it. */
private fun mintProof(now: Long = System.currentTimeMillis()): VaultUnlockProof {
    val bytes = ByteArray(PROOF_NONCE_BYTES).also { proofRandom.nextBytes(it) }
    val nonce = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )
    val expiresAt = now + PROOF_TTL_MS
    synchronized(proofLock) {
        liveProofs.entries.removeAll { it.value < now }
        liveProofs[nonce] = expiresAt
    }
    return IssuedProof(nonce, expiresAt)
}

/** Spends proofs. Accepts only proofs minted in this file. */
object VaultUnlockLedger {
    /**
     * True only for an unspent, unexpired proof minted in this file.
     * Spending removes it, so a second call with the same proof returns false.
     */
    fun spend(proof: VaultUnlockProof, now: Long = System.currentTimeMillis()): Boolean {
        if (proof !is IssuedProof) return false
        return synchronized(proofLock) {
            val expiresAt = liveProofs.remove(proof.nonce) ?: return false
            expiresAt >= now
        }
    }
}

/**
 * Hardware-backed biometric gate for alarm dismissal.
 *
 * Wraps [BiometricPrompt] with:
 *   • Class-3 biometrics only (`BIOMETRIC_STRONG`) — Class-2 (face)
 *     cannot unlock the vault on any device that also supports a
 *     screen lock, so it would be a security downgrade.
 *   • Device-credential fallback (`DEVICE_CREDENTIAL`) on API 30+ — the
 *     user can dismiss with the PIN/pattern/password if biometrics are
 *     unavailable or fail. Below API 30 the prompt is biometric-only.
 *   • Null-safety: if biometrics are genuinely unavailable on the
 *     device (`BiometricManager.canAuthenticate` does not return
 *     `BIOMETRIC_SUCCESS`), [prompt] calls `onFailure` so the caller
 *     can degrade gracefully.
 *
 * Callback contract:
 *   • `onSuccess` — biometric / credential verified. Caller dismisses.
 *   • `onFailure` — biometric / credential not verified, cancelled by
 *     user, or hardware error. Caller typically spring-backs the
 *     dismissal UI (see `AlarmActivity`'s `resetDrag()`).
 *
 * Rule 7: [Mutex] serialises [prompt] calls so two overlapping
 * requests (e.g. the user triggers dismiss twice in rapid succession)
 * do not cause a `BiometricPrompt` lifecycle crash.
 */
@Singleton
class BiometricWakeUpLock @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val pinVerifier: DecoyPinVerifier,
    private val auditor: SecurityAccessAuditor
) {
    private val tag = "BiometricWakeUpLock"
    private val mutex = Mutex()

    // Written from the main thread (prompt callbacks) and read from
    // coroutine threads, so both fields are volatile.
    @Volatile private var activePrompt: BiometricPrompt? = null
    @Volatile private var activeActivity: WeakReference<FragmentActivity>? = null

    /**
     * Launch the biometric prompt on [activity].
     *
     * @param onSuccess invoked exactly once when authentication succeeds,
     *        with a fresh single-use [VaultUnlockProof] for the vault.
     * @param onFailure invoked exactly once when authentication fails,
     *        is cancelled, or hardware is unavailable. Not invoked if a
     *        previous prompt is already active on a live activity.
     */
    suspend fun prompt(
        activity: FragmentActivity,
        onSuccess: (VaultUnlockProof) -> Unit,
        onFailure: () -> Unit
    ) {
        mutex.withLock {
            if (activePrompt != null) {
                val owner = activeActivity?.get()
                if (owner != null && !owner.isFinishing && !owner.isDestroyed) {
                    Log.w(tag, "Prompt already active; ignoring new request")
                    onFailure()
                    return
                }
                // The owning activity went away mid-prompt, so its callbacks
                // will never arrive. Without this reset every later prompt
                // would be refused for the rest of the process lifetime.
                Log.w(tag, "Clearing prompt whose activity was destroyed")
                activePrompt = null
                activeActivity = null
            }

            val authenticators = allowedAuthenticators()
            val available = BiometricManager.from(context).canAuthenticate(authenticators)
            if (available != BiometricManager.BIOMETRIC_SUCCESS) {
                Log.w(tag, "Biometrics unavailable (reason $available)")
                onFailure()
                return
            }

            val executor = ContextCompat.getMainExecutor(context)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    activePrompt = null
                    activeActivity = null
                    val proof = mintProof()
                    try { onSuccess(proof) } catch (e: Exception) {
                        Log.e(tag, "onSuccess threw", e)
                    }
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    activePrompt = null
                    activeActivity = null
                    Log.w(tag, "Auth error $errorCode: $errString")
                    try { onFailure() } catch (e: Exception) {
                        Log.e(tag, "onFailure threw", e)
                    }
                }

                override fun onAuthenticationFailed() {
                    // Called on each failed attempt; the prompt remains
                    // open. Do NOT call onFailure here — onFailure is
                    // reserved for terminal outcomes (error / cancel /
                    // no hardware). The prompt will call
                    // onAuthenticationError if the user cancels.
                    Log.i(tag, "Auth attempt failed; prompt remains open")
                }
            }

            try {
                val info = buildInfo(authenticators)
                val prompt = BiometricPrompt(activity, executor, callback)
                activePrompt = prompt
                activeActivity = WeakReference(activity)
                prompt.authenticate(info)
            } catch (e: Exception) {
                Log.e(tag, "authenticate() threw", e)
                activePrompt = null
                activeActivity = null
                onFailure()
            }
        }
    }

    /** Cancel any in-flight prompt. Safe to call unconditionally. */
    /**
     * True when this device can run the prompt with the authenticators the
     * vault uses. Alarms that are biometric-shielded are refused without it.
     */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(context).canAuthenticate(allowedAuthenticators()) ==
            BiometricManager.BIOMETRIC_SUCCESS

    suspend fun cancel() = mutex.withLock {
        val prompt = activePrompt
        if (prompt != null) {
            try {
                withContext(Dispatchers.Main) { prompt.cancelAuthentication() }
            } catch (e: Exception) {
                Log.w(tag, "cancelAuthentication threw", e)
            }
        }
        activePrompt = null
        activeActivity = null
    }

    /**
     * Verify the decoy PIN and, only on a match, mint a proof. Returns null
     * for a wrong PIN and for an unverifiable attempt alike, so the caller
     * cannot tell them apart from the result.
     */
    suspend fun unlockWithPin(pin: String): VaultUnlockProof? {
        if (auditor.isLocked()) return null
        return if (pinVerifier.verify(pin) == true) {
            auditor.recordSuccess()
            mintProof()
        } else {
            auditor.recordFailure()
            null
        }
    }

    /** Milliseconds until PIN entry is allowed again, or 0 when it is. */
    fun pinLockoutRemainingMs(): Long = auditor.remainingLockoutMs()

    /** Wrong PIN attempts left before the next cooldown. */
    fun pinAttemptsLeft(): Int = auditor.attemptsLeft()

    // ── Internals ──────────────────────────────────────────────────

    /** The authenticator set used for both the availability check and the
     *  prompt. Using one value for both keeps them from disagreeing. */
    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    private fun buildInfo(authenticators: Int): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.alarm_biometric_prompt_title))
            .setSubtitle(context.getString(R.string.alarm_biometric_prompt_subtitle))
            .setAllowedAuthenticators(authenticators)
        // BiometricPrompt throws from build() if a negative button is set
        // while DEVICE_CREDENTIAL is allowed. The device-credential screen
        // has its own cancel path in that case, so only set the button
        // when credential fallback is off.
        if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
            builder.setNegativeButtonText(
                context.getString(R.string.alarm_biometric_prompt_cancel)
            )
        }
        return builder.build()
    }
}
