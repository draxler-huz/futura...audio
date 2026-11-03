// app/src/main/java/com/futura/ui/viewmodel/ShieldGate.kt
package com.futura.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import com.futura.alarm.BiometricWakeUpLock
import com.futura.alarm.VaultUnlockProof
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the biometric prompt that unlocks a shielded alarm change. It hands
 * the single-use proof to the caller, who passes it to the alarm store.
 */
@Singleton
class ShieldGate @Inject constructor(
    private val biometricLock: BiometricWakeUpLock
) {
    fun authorize(
        activity: FragmentActivity,
        scope: CoroutineScope,
        onGranted: (VaultUnlockProof) -> Unit
    ) {
        scope.launch {
            biometricLock.prompt(
                activity = activity,
                onSuccess = onGranted,
                onFailure = { }
            )
        }
    }
}
