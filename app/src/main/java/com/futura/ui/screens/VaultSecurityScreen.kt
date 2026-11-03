// app/src/main/java/com/futura/ui/screens/VaultSecurityScreen.kt
package com.futura.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futura.alarm.VaultUnlockProof
import com.futura.ui.theme.FuturaColors
import com.futura.ui.viewmodel.VaultSecurityViewModel
import kotlinx.coroutines.delay

/**
 * Gate in front of the vault and of biometric alarm changes.
 *
 * Offers a biometric prompt and a 5-digit PIN keypad. The keypad layout is
 * shuffled on every attempt. A successful unlock returns a single-use
 * [VaultUnlockProof] through [onUnlocked]. FLAG_SECURE keeps the screen out
 * of screenshots and the app switcher while it is shown.
 */
@Composable
fun VaultSecurityScreen(
    onUnlocked: (VaultUnlockProof) -> Unit,
    onCancel: () -> Unit,
    viewModel: VaultSecurityViewModel = hiltViewModel()
) {
    val activity = LocalContext.current.findFragmentActivity()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pin by rememberSaveable { mutableStateOf("") }
    var layoutSeed by remember { mutableStateOf(0) }
    val keys = remember(layoutSeed) { (0..9).shuffled() }

    // Keep secure content off screenshots and the app switcher.
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Count the cooldown down once a second.
    val now by produceState(System.currentTimeMillis(), state.lockedUntilMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val cooldownSeconds = ((state.lockedUntilMs - now).coerceAtLeast(0L) + 999L) / 1000L
    val locked = cooldownSeconds > 0L

    // A wrong attempt clears the entry and reshuffles the keypad.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) {
            pin = ""
            layoutSeed++
        }
    }

    fun press(digit: Int) {
        if (locked || state.busy || pin.length >= VaultSecurityViewModel.PIN_LENGTH) return
        pin += digit.toString()
        if (pin.length == VaultSecurityViewModel.PIN_LENGTH) {
            viewModel.submitPin(pin, onUnlocked)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Cancel",
                color = FuturaColors.TextTertiary,
                modifier = Modifier.clickable(onClick = onCancel).padding(8.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "VAULT ENCRYPTED",
            color = FuturaColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Authenticate to manage protected alarms and view confidential records.",
            color = FuturaColors.TextSecondary,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(24.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 220.dp, height = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, FuturaColors.Cyan, RoundedCornerShape(24.dp))
                .clickable(enabled = !state.busy && activity != null) {
                    activity?.let { viewModel.unlockWithBiometrics(it, onUnlocked) }
                }
        ) {
            Text(
                text = "Use fingerprint or face",
                color = FuturaColors.Cyan,
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        PinDots(filled = pin.length, total = VaultSecurityViewModel.PIN_LENGTH)

        Spacer(Modifier.height(12.dp))
        val statusText = when {
            locked -> "Locked for ${cooldownSeconds}s"
            state.message != null -> state.message.orEmpty()
            else -> "Enter ${VaultSecurityViewModel.PIN_LENGTH}-digit PIN"
        }
        Text(
            text = statusText,
            color = if (locked || state.message != null) FuturaColors.Error else FuturaColors.TextTertiary,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(24.dp))
        Keypad(
            keys = keys,
            enabled = !locked && !state.busy,
            onDigit = { press(it) },
            onBackspace = {
                if (pin.isNotEmpty()) pin = pin.dropLast(1)
            }
        )
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────

@Composable
private fun PinDots(filled: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < filled) FuturaColors.Cyan else FuturaColors.SurfaceRecessed
                    )
            )
        }
    }
}

/**
 * 3x4 keypad. The digits come in the order given by [keys], so the layout
 * changes between attempts. Bottom row: blank, the last digit, backspace.
 */
@Composable
private fun Keypad(
    keys: List<Int>,
    enabled: Boolean,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (col in 0 until 3) {
                    val digit = keys[row * 3 + col]
                    KeyButton(label = digit.toString(), enabled = enabled, onClick = { onDigit(digit) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.size(72.dp))
            KeyButton(label = keys[9].toString(), enabled = enabled, onClick = { onDigit(keys[9]) })
            KeyButton(label = "⌫", enabled = enabled, onClick = onBackspace)
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(FuturaColors.SurfaceRaised)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (enabled) FuturaColors.TextPrimary else FuturaColors.TextDisabled,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
