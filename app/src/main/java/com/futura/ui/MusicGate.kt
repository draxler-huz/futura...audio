// app/src/main/java/com/futura/ui/MusicGate.kt
package com.futura.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.futura.ui.screens.VaultSecurityScreen

/**
 * Gates the music side (Now Playing, Library, Media Hub) behind the vault PIN.
 *
 * Uses the vault's rules through [VaultLockClock]: the PIN is asked again
 * only after the music side has been left for longer than the grace period.
 * The PIN prompt is shown in place, so cancelling returns to the previous
 * screen without re-prompting. Only the screens are locked; playback keeps
 * running.
 *
 * Leaving a screen counts as locking the music side only if it was unlocked,
 * so a cancelled PIN prompt cannot start a grace period.
 */
@Composable
fun MusicGate(onCancel: () -> Unit, content: @Composable () -> Unit) {
    var unlocked by remember {
        mutableStateOf(VaultLockClock.withinGrace() || VaultLockClock.isOpen())
    }
    val currentlyUnlocked by rememberUpdatedState(unlocked)

    DisposableEffect(Unit) {
        onDispose {
            if (currentlyUnlocked) VaultLockClock.markLocked()
        }
    }

    if (unlocked) {
        content()
    } else {
        VaultSecurityScreen(
            onUnlocked = {
                VaultLockClock.markOpen()
                unlocked = true
            },
            onCancel = onCancel
        )
    }
}
