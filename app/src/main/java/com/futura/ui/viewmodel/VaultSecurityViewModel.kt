// app/src/main/java/com/futura/ui/viewmodel/VaultSecurityViewModel.kt
package com.futura.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.BiometricWakeUpLock
import com.futura.alarm.VaultUnlockProof
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the vault gate: biometric prompt and master PIN entry.
 *
 * On success the caller receives a single-use [VaultUnlockProof] and must
 * spend it through VaultManager. The attempt counter and cooldown here are
 * for display. Enforcement lives in the security module.
 */
@HiltViewModel
class VaultSecurityViewModel @Inject constructor(
    private val biometricLock: BiometricWakeUpLock
) : ViewModel() {

    data class UiState(
        val failedAttempts: Int = 0,
        val lockedUntilMs: Long = 0L,
        val busy: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Milliseconds left in the cooldown, or 0 when the keypad is usable. */
    fun cooldownRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (_state.value.lockedUntilMs - now).coerceAtLeast(0L)

    /** Opens the biometric prompt on [activity]. */
    fun unlockWithBiometrics(
        activity: FragmentActivity,
        onUnlocked: (VaultUnlockProof) -> Unit
    ) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            biometricLock.prompt(
                activity = activity,
                onSuccess = { proof ->
                    _state.update { it.copy(busy = false, failedAttempts = 0) }
                    onUnlocked(proof)
                },
                onFailure = {
                    _state.update {
                        it.copy(busy = false, message = "Biometric check failed. Try again or use your PIN.")
                    }
                }
            )
        }
    }

    /** Checks a full PIN. Ignored while the cooldown is running. */
    fun submitPin(pin: String, onUnlocked: (VaultUnlockProof) -> Unit) {
        val now = System.currentTimeMillis()
        if (cooldownRemainingMs(now) > 0L || _state.value.busy) return
        if (_state.value.lockedUntilMs != 0L && cooldownRemainingMs(now) == 0L) {
            _state.update { it.copy(failedAttempts = 0, lockedUntilMs = 0L) }
        }

        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val proof = biometricLock.unlockWithPin(pin)
            if (proof != null) {
                _state.update { it.copy(busy = false, failedAttempts = 0, lockedUntilMs = 0L) }
                onUnlocked(proof)
            } else {
                _state.update { current ->
                    val attempts = current.failedAttempts + 1
                    val lockUntil = if (attempts >= MAX_ATTEMPTS) {
                        System.currentTimeMillis() + COOLDOWN_MS
                    } else {
                        0L
                    }
                    current.copy(
                        busy = false,
                        failedAttempts = attempts,
                        lockedUntilMs = lockUntil,
                        message = if (lockUntil > 0L) {
                            "Too many attempts. Wait before trying again."
                        } else {
                            "Wrong PIN. ${MAX_ATTEMPTS - attempts} attempts left."
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val PIN_LENGTH = 5
        const val MAX_ATTEMPTS = 5
        const val COOLDOWN_MS = 30_000L
    }
}
