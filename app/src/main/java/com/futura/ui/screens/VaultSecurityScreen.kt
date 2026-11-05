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
import com.futura.ui.theme.FuturaColors
import com.futura.ui.viewmodel.VaultSecurityViewModel
import kotlinx.coroutines.delay
import com.futura.ui.theme.AlarmGreen
import com.futura.ui.theme.AlarmKeyBackground

/**
 * Gate in front of the vault. Offers a biometric prompt and a 6-digit PIN.
 * On first sign-in (no PIN yet) it asks the user to choose and confirm one.
 * The keypad reshuffles after each wrong attempt. Lockout is enforced by the
 * security module and shown here. [onUnlocked] runs once the vault is open.
 * FLAG_SECURE is set while the screen is shown.
 */
@Composable
fun VaultSecurityScreen(
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
    viewModel: VaultSecurityViewModel = hiltViewModel()
) {
    val activity = LocalContext.current.findFragmentActivity()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pin by rememberSaveable { mutableStateOf("") }
    var layoutSeed by remember { mutableStateOf(0) }
    val keys = remember(layoutSeed) { (0..9).shuffled() }

    // null while loading; false means first sign-in, so the screen sets a PIN.
    val hasPin by produceState<Boolean?>(initialValue = null) { value = viewModel.hasPin() }
    // Setup only: the first entry, waiting for the user to type it again.
    var firstEntry by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Tick the cooldown once a second. It is read from the security module.
    val now by produceState(System.currentTimeMillis(), state.failedAttempts) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val cooldownSeconds = (viewModel.cooldownRemainingMs().coerceAtLeast(0L) + 999L) / 1000L
    val locked = cooldownSeconds > 0L

    // A wrong attempt clears the entry and reshuffles the keypad.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) {
            pin = ""
            layoutSeed++
        }
    }

    fun press(digit: Int) {
        if (hasPin == null || locked || state.busy || pin.length >= VaultSecurityViewModel.PIN_LENGTH) return
        pin += digit.toString()
        if (pin.length < VaultSecurityViewModel.PIN_LENGTH) return
        if (hasPin == true) {
            viewModel.submitPin(pin, onUnlocked)
            return
        }
        // First sign-in: the PIN is entered twice, and both entries must match.
        val first = firstEntry
        when {
            first == null -> {
                firstEntry = pin
                pin = ""
                layoutSeed++
            }
            pin == first -> viewModel.setupPin(pin, onUnlocked)
            else -> {
                firstEntry = null
                pin = ""
                layoutSeed++
                viewModel.reportSetupMismatch()
            }
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
            text = if (hasPin == false) "CREATE VAULT PIN" else "VAULT ENCRYPTED",
            color = FuturaColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasPin == false) {
                "Choose a ${VaultSecurityViewModel.PIN_LENGTH}-digit PIN. You will use it to open the vault."
            } else {
                "Authenticate to manage protected alarms and view confidential records."
            },
            color = FuturaColors.TextSecondary,
            fontSize = 13.sp
        )

        // Biometrics are offered only after a PIN exists, so first sign-in always sets one.
        if (hasPin == true) {
            Spacer(Modifier.height(24.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 220.dp, height = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, AlarmGreen, RoundedCornerShape(24.dp))
                    .clickable(enabled = !state.busy && !locked && activity != null) {
                        activity?.let { viewModel.unlockWithBiometrics(it, onUnlocked) }
                    }
            ) {
                Text(text = "Use fingerprint or face", color = AlarmGreen, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        PinDots(filled = pin.length, total = VaultSecurityViewModel.PIN_LENGTH)

        Spacer(Modifier.height(12.dp))
        val statusText = when {
            locked -> "Locked for ${cooldownSeconds}s"
            state.message != null -> state.message.orEmpty()
            hasPin == false && firstEntry != null -> "Enter the PIN again to confirm"
            hasPin == false -> "Choose a ${VaultSecurityViewModel.PIN_LENGTH}-digit PIN"
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
            enabled = hasPin != null && !locked && !state.busy,
            onDigit = { press(it) },
            onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        )
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────

@Composable
internal fun PinDots(filled: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (index < filled) AlarmGreen else FuturaColors.SurfaceRecessed)
            )
        }
    }
}

/**
 * 3x4 keypad. Digits follow [keys], so the layout changes between attempts.
 * Bottom row: blank, the last digit, backspace.
 */
@Composable
internal fun Keypad(
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
            .background(AlarmKeyBackground)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (enabled) AlarmGreen else FuturaColors.TextDisabled,
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
