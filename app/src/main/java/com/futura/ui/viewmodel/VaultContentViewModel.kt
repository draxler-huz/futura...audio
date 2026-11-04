// app/src/main/java/com/futura/ui/viewmodel/VaultContentViewModel.kt
package com.futura.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.BiometricWakeUpLock
import com.futura.alarm.LockoutPolicy
import com.futura.alarm.LockoutPolicyStore
import com.futura.alarm.PinManager
import com.futura.alarm.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Locks the vault and changes its security settings.
 *
 * Changing the PIN or the lockout rule always asks for a fresh biometric
 * confirmation at the moment of saving. The proof is then spent right away,
 * so it cannot go stale while the user is still typing.
 */
@HiltViewModel
class VaultContentViewModel @Inject constructor(
    private val vault: VaultManager,
    private val biometricLock: BiometricWakeUpLock,
    private val pinManager: PinManager,
    private val policyStore: LockoutPolicyStore
) : ViewModel() {

    /** Locks the vault, then calls [onLocked]. */
    fun lock(onLocked: () -> Unit) {
        viewModelScope.launch {
            vault.lock()
            onLocked()
        }
    }

    /** The lockout rule currently in force. */
    suspend fun currentPolicy(): LockoutPolicy = policyStore.load()

    /** Confirms the user biometrically, then saves [policy]. [onResult] gets a message. */
    fun saveLockout(activity: FragmentActivity, policy: LockoutPolicy, onResult: (String) -> Unit) {
        viewModelScope.launch {
            biometricLock.prompt(
                activity = activity,
                onSuccess = { proof ->
                    viewModelScope.launch {
                        val saved = policyStore.saveWithProof(policy, proof)
                        onResult(if (saved) "Lockout rule updated." else "Could not save the lockout rule.")
                    }
                },
                onFailure = { onResult("Confirmation cancelled. Nothing changed.") }
            )
        }
    }

    /** Confirms the user biometrically, then replaces the PIN with [newPin]. */
    fun changePin(activity: FragmentActivity, newPin: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            biometricLock.prompt(
                activity = activity,
                onSuccess = { proof ->
                    viewModelScope.launch {
                        val changed = pinManager.changePin(newPin, proof)
                        onResult(if (changed) "PIN changed." else "Could not change the PIN.")
                    }
                },
                onFailure = { onResult("Confirmation cancelled. Nothing changed.") }
            )
        }
    }
}
