// app/src/main/java/com/futura/ui/viewmodel/VaultSecurityViewModel.kt
package com.futura.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.BiometricWakeUpLock
import com.futura.alarm.PinManager
import com.futura.alarm.VaultManager
import com.futura.alarm.VaultUnlockProof
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the vault gate. Lockout and attempt counting belong to the security
 * module ([BiometricWakeUpLock]). This class only shows them.
 *
 * First sign-in: when no PIN exists, the gate runs in setup mode. The user
 * creates the PIN ([setupPin]), and the vault opens straight away.
 * A successful unlock spends the proof through [VaultManager.unlock] before
 * navigating.
 */
@HiltViewModel
class VaultSecurityViewModel @Inject constructor(
    private val biometricLock: BiometricWakeUpLock,
    private val pinManager: PinManager,
    private val vault: VaultManager
) : ViewModel() {

    data class UiState(
        /** Bumped on every wrong PIN, so the keypad reshuffles. */
        val failedAttempts: Int = 0,
        val busy: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** True once the user has set a PIN. False means first sign-in. */
    suspend fun hasPin(): Boolean = pinManager.hasPin()

    /** Milliseconds of cooldown left, read from the security module. */
    fun cooldownRemainingMs(): Long = biometricLock.pinLockoutRemainingMs()

    /** Wrong PIN attempts left before the next cooldown. */
    fun attemptsLeft(): Int = biometricLock.pinAttemptsLeft()

    /** Opens the biometric prompt on [activity]. */
    fun unlockWithBiometrics(activity: FragmentActivity, onUnlocked: () -> Unit) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            biometricLock.prompt(
                activity = activity,
                onSuccess = { proof -> openVault(proof, onUnlocked) },
                onFailure = {
                    _state.update {
                        it.copy(busy = false, message = "Biometric check failed. Try again or use your PIN.")
                    }
                }
            )
        }
    }

    /** Checks a full PIN. Ignored while a cooldown is running. */
    fun submitPin(pin: String, onUnlocked: () -> Unit) {
        if (cooldownRemainingMs() > 0L || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val proof = biometricLock.unlockWithPin(pin)
            if (proof == null) {
                val message = if (cooldownRemainingMs() > 0L) {
                    "Too many attempts. Wait before trying again."
                } else {
                    "Wrong PIN. ${attemptsLeft()} attempts left."
                }
                _state.update {
                    it.copy(busy = false, failedAttempts = it.failedAttempts + 1, message = message)
                }
            } else {
                openVault(proof, onUnlocked)
            }
        }
    }

    /**
     * First sign-in only: save the new PIN, then open the vault with it.
     * Does nothing if a PIN already exists, so setup cannot replace one.
     */
    fun setupPin(pin: String, onUnlocked: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            if (!pinManager.createPin(pin)) {
                _state.update { it.copy(busy = false, message = "Could not save the PIN. Try again.") }
                return@launch
            }
            val proof = biometricLock.unlockWithPin(pin)
            if (proof == null) {
                _state.update { it.copy(busy = false, message = "The vault could not open. Try again.") }
            } else {
                openVault(proof, onUnlocked)
            }
        }
    }

    /** Shown when the two PIN entries in setup do not match. */
    fun reportSetupMismatch() {
        _state.update { it.copy(message = "The PINs did not match. Start again.") }
    }

    private fun openVault(proof: VaultUnlockProof, onUnlocked: () -> Unit) {
        viewModelScope.launch {
            if (vault.unlock(proof)) {
                _state.update { it.copy(busy = false, message = null) }
                onUnlocked()
            } else {
                _state.update { it.copy(busy = false, message = "The vault could not open. Try again.") }
            }
        }
    }

    companion object {
        /** Same length as [PinManager.PIN_LENGTH]; the keypad and the store agree. */
        const val PIN_LENGTH = PinManager.PIN_LENGTH
    }
}
