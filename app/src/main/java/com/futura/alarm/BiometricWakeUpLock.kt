package com.futura.alarm

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.futura.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
class BiometricWakeUpLock @Inject constructor(
    @ApplicationContext private val context: Context
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
     * @param onSuccess invoked exactly once when authentication succeeds.
     * @param onFailure invoked exactly once when authentication fails,
     *        is cancelled, or hardware is unavailable. Not invoked if a
     *        previous prompt is already active on a live activity.
     */
    suspend fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
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
                    try { onSuccess() } catch (e: Exception) {
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
