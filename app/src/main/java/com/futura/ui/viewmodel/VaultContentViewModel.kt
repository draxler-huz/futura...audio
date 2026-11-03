// app/src/main/java/com/futura/ui/viewmodel/VaultContentViewModel.kt
package com.futura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.futura.alarm.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Locks the vault from the content screen. */
@HiltViewModel
class VaultContentViewModel @Inject constructor(
    private val vault: VaultManager
) : ViewModel() {

    /** Locks the vault, then calls [onLocked]. */
    fun lock(onLocked: () -> Unit) {
        viewModelScope.launch {
            vault.lock()
            onLocked()
        }
    }
}
