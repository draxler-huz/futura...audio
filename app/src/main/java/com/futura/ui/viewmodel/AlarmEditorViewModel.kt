// app/src/main/java/com/futura/ui/viewmodel/AlarmEditorViewModel.kt
package com.futura.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.AlarmScheduler
import com.futura.alarm.AlarmSpec
import com.futura.alarm.AlarmStore
import com.futura.alarm.BiometricWakeUpLock
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Creates and edits one alarm. The screen holds the draft; this class
 * validates it and persists it through [AlarmStore] and [AlarmScheduler].
 *
 * Navigation passes `alarmId` in the saved state. When it is absent the
 * editor starts a new alarm.
 */
@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    private val store: AlarmStore,
    private val scheduler: AlarmScheduler,
    private val biometricLock: BiometricWakeUpLock,
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

    /**
     * Runs the biometric gate before a shielded alarm is changed. [onGranted]
     * fires only after a successful prompt.
     */
    fun authorize(activity: FragmentActivity, onGranted: () -> Unit) {
        viewModelScope.launch {
            biometricLock.prompt(
                activity = activity,
                onSuccess = { _ -> onGranted() },
                onFailure = { }
            )
        }
    }

    /**
     * Validates [draft] and saves it. Returns a message for the user when the
     * draft is invalid. Otherwise returns null and calls [onSaved] once the
     * store and scheduler have finished. The caller must not leave the screen
     * before that, or the save is cancelled with the ViewModel.
     */
    fun save(draft: AlarmSpec, onSaved: () -> Unit): String? {
        if (draft.label.length > MAX_LABEL_LENGTH) {
            return "Label must be $MAX_LABEL_LENGTH characters or fewer."
        }
        if (draft.rampDurationSeconds !in 0..MAX_RAMP_SECONDS) {
            return "Ramp must be between 0 and 30 minutes."
        }
        if (draft.biometricLocked && !biometricAvailable()) {
            return "This device has no biometric or screen lock set up, so the shield can't be turned on."
        }
        viewModelScope.launch {
            store.upsert(draft)
            if (draft.enabled) {
                scheduler.schedule(draft)
            } else {
                scheduler.cancel(draft.id)
            }
            onSaved()
        }
        return null
    }

    companion object {
        const val ARG_ALARM_ID = "alarmId"
        const val MAX_LABEL_LENGTH = 60
        const val MAX_RAMP_SECONDS = 30 * 60

        /** The system default alarm tone. */
        const val DEFAULT_SOUND_URI = "content://settings/system/alarm_alert"
    }
}
