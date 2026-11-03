// app/src/main/java/com/futura/ui/viewmodel/AlarmEditorViewModel.kt
package com.futura.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.AlarmScheduler
import com.futura.alarm.AlarmSpec
import com.futura.alarm.AlarmStore
import com.futura.alarm.BiometricWakeUpLock
import com.futura.alarm.VaultUnlockProof
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Creates and edits one alarm. The screen holds the draft. This class
 * validates it, then saves through [AlarmStore] with a proof when the
 * existing alarm is shielded.
 */
@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    private val store: AlarmStore,
    private val scheduler: AlarmScheduler,
    private val biometricLock: BiometricWakeUpLock,
    private val shieldGate: ShieldGate,
    savedState: SavedStateHandle
) : ViewModel() {

    /** The alarm being edited, or null when creating a new one. */
    val original: AlarmSpec? = savedState.get<String>(ARG_ALARM_ID)?.let { store.get(it) }

    /** Starting point for the draft. */
    val initial: AlarmSpec = original ?: AlarmSpec(
        hour = 7,
        minute = 0,
        soundUri = DEFAULT_SOUND_URI
    )

    /** Whether this device can satisfy a biometric shield. */
    fun biometricAvailable(): Boolean = biometricLock.canAuthenticate()

    /** Runs the biometric gate before a shielded alarm is changed. */
    fun authorize(activity: FragmentActivity, onGranted: (VaultUnlockProof) -> Unit) {
        shieldGate.authorize(activity, viewModelScope, onGranted)
    }

    /**
     * Validates and saves [draft]. [onResult] receives an error message, or
     * null once the store and scheduler have accepted the change.
     */
    fun save(draft: AlarmSpec, proof: VaultUnlockProof?, onResult: (String?) -> Unit) {
        if (draft.label.length > MAX_LABEL_LENGTH) {
            onResult("Label must be $MAX_LABEL_LENGTH characters or fewer.")
            return
        }
        if (draft.rampDurationSeconds !in 0..MAX_RAMP_SECONDS) {
            onResult("Ramp must be between 0 and 30 minutes.")
            return
        }
        if (draft.biometricLocked && !biometricAvailable()) {
            onResult("This device has no biometric or screen lock set up, so the shield can't be turned on.")
            return
        }
        viewModelScope.launch {
            val shielded = store.get(draft.id)?.biometricLocked == true
            if (shielded && proof == null) {
                onResult("Authenticate to change this alarm.")
                return@launch
            }
            if (!store.upsert(draft, proof)) {
                onResult("Authentication expired. Try again.")
                return@launch
            }
            if (draft.enabled) {
                scheduler.schedule(draft)
            } else {
                scheduler.cancel(draft.id)
            }
            onResult(null)
        }
    }

    companion object {
        const val ARG_ALARM_ID = "alarmId"
        const val MAX_LABEL_LENGTH = 60
        const val MAX_RAMP_SECONDS = 30 * 60

        /** The system default alarm tone. */
        const val DEFAULT_SOUND_URI = "content://settings/system/alarm_alert"
    }
}
