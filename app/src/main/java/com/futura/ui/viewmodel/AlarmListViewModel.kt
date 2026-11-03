// app/src/main/java/com/futura/ui/viewmodel/AlarmListViewModel.kt
package com.futura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.AlarmScheduler
import com.futura.alarm.AlarmSpec
import com.futura.alarm.AlarmStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the alarm list. Reads [AlarmStore.specs] and keeps the OS
 * schedule in step with every change the user makes.
 */
@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val store: AlarmStore,
    private val scheduler: AlarmScheduler
) : ViewModel() {

    /** Alarms sorted by time of day. */
    val alarms: StateFlow<List<AlarmSpec>> = store.specs
        .map { list -> list.sortedWith(compareBy({ it.hour }, { it.minute })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Turns an alarm on or off and updates the OS schedule to match. */
    fun setEnabled(spec: AlarmSpec, enabled: Boolean) {
        viewModelScope.launch {
            store.setEnabled(spec.id, enabled)
            if (enabled) {
                scheduler.schedule(spec.copy(enabled = true))
            } else {
                scheduler.cancel(spec.id)
            }
        }
    }

    /** Removes an alarm from the store and cancels its OS timer. */
    fun delete(spec: AlarmSpec) {
        viewModelScope.launch {
            scheduler.cancel(spec.id)
            store.remove(spec.id)
        }
    }
}
