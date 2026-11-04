// app/src/main/java/com/futura/ui/viewmodel/AlarmListViewModel.kt
package com.futura.ui.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.AlarmScheduler
import com.futura.alarm.AlarmSpec
import com.futura.alarm.AlarmStore
import com.futura.alarm.VaultUnlockProof
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the alarm list. Every change goes through [AlarmStore] first, and
 * the OS schedule follows only when the store accepts the change.
 */
@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val store: AlarmStore,
    private val scheduler: AlarmScheduler,
    private val shieldGate: ShieldGate
) : ViewModel() {

    /** Alarms sorted by time of day. */
    val alarms: StateFlow<List<AlarmSpec>> = store.specs
        .map { list -> list.sortedWith(compareBy({ it.hour }, { it.minute })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** False on Android 12+ until the user grants exact alarms in system settings. */
    fun canScheduleExact(): Boolean = scheduler.canScheduleExact()

    /** Runs the biometric gate for a shielded change. */
    fun authorize(activity: FragmentActivity, onGranted: (VaultUnlockProof) -> Unit) {
        shieldGate.authorize(activity, viewModelScope, onGranted)
    }

    /** Turns an alarm on or off. [proof] is required for shielded alarms. */
    fun setEnabled(spec: AlarmSpec, enabled: Boolean, proof: VaultUnlockProof?) {
        viewModelScope.launch {
            if (!store.setEnabled(spec.id, enabled, proof)) return@launch
            if (enabled) {
                scheduler.schedule(spec.copy(enabled = true))
            } else {
                scheduler.cancel(spec.id)
            }
        }
    }

    /** Removes an alarm. [proof] is required for shielded alarms. */
    fun delete(spec: AlarmSpec, proof: VaultUnlockProof?) {
        viewModelScope.launch {
            if (!store.remove(spec.id, proof)) return@launch
            scheduler.cancel(spec.id)
        }
    }
}
