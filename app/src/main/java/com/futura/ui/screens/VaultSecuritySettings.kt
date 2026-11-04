// app/src/main/java/com/futura/ui/screens/VaultSecuritySettings.kt
package com.futura.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.futura.alarm.LockoutPolicy
import com.futura.alarm.PinManager
import com.futura.ui.theme.FuturaColors
import com.futura.ui.viewmodel.VaultContentViewModel

private enum class SettingsPanel { NONE, CHANGE_PIN, LOCKOUT }

/**
 * Security settings inside an unlocked vault: change the PIN, or change the
 * lockout rule. Each panel saves only after a fresh biometric confirmation,
 * so opening the panel alone changes nothing.
 */
@Composable
fun VaultSecuritySettings(
    activity: FragmentActivity?,
    viewModel: VaultContentViewModel
) {
    var panel by rememberSaveable { mutableStateOf(SettingsPanel.NONE) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineButton(
                label = "Change PIN",
                enabled = activity != null,
                modifier = Modifier.weight(1f),
                onClick = { panel = SettingsPanel.CHANGE_PIN; message = null }
            )
            OutlineButton(
                label = "Lockout rule",
                enabled = activity != null,
                modifier = Modifier.weight(1f),
                onClick = { panel = SettingsPanel.LOCKOUT; message = null }
            )
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = FuturaColors.TextSecondary, fontSize = 13.sp)
        }

        if (activity != null) {
            Spacer(Modifier.height(12.dp))
            when (panel) {
                SettingsPanel.NONE -> Unit
                SettingsPanel.CHANGE_PIN -> ChangePinPanel(
                    activity = activity,
                    viewModel = viewModel,
                    onDone = { msg -> message = msg; panel = SettingsPanel.NONE },
                    onCancel = { panel = SettingsPanel.NONE }
                )
                SettingsPanel.LOCKOUT -> LockoutPanel(
                    activity = activity,
                    viewModel = viewModel,
                    onDone = { msg -> message = msg; panel = SettingsPanel.NONE },
                    onCancel = { panel = SettingsPanel.NONE }
                )
            }
        }
    }
}

// ── Change PIN ──────────────────────────────────────────────────────────

@Composable
private fun ChangePinPanel(
    activity: FragmentActivity,
    viewModel: VaultContentViewModel,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    val length = PinManager.PIN_LENGTH
    // The new PIN is typed twice. Both entries must match before the save prompt.
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var pin by rememberSaveable { mutableStateOf("") }
    var layoutSeed by rememberSaveable { mutableStateOf(0) }
    val keys = remember(layoutSeed) { (0..9).shuffled() }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun press(digit: Int) {
        if (busy || pin.length >= length) return
        pin += digit.toString()
        if (pin.length < length) return
        val entered = first
        when {
            entered == null -> {
                first = pin
                pin = ""
                layoutSeed++
                error = null
            }
            pin == entered -> {
                busy = true
                viewModel.changePin(activity, pin) { msg ->
                    busy = false
                    onDone(msg)
                }
            }
            else -> {
                first = null
                pin = ""
                layoutSeed++
                error = "The PINs did not match. Start again."
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (first == null) "Enter the new $length-digit PIN" else "Enter it again to confirm",
            color = FuturaColors.TextSecondary,
            fontSize = 13.sp
        )
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, color = FuturaColors.Error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        PinDots(filled = pin.length, total = length)
        Spacer(Modifier.height(12.dp))
        Keypad(
            keys = keys,
            enabled = !busy,
            onDigit = { press(it) },
            onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        )
        Spacer(Modifier.height(12.dp))
        OutlineButton(label = "Cancel", enabled = !busy, onClick = onCancel)
    }
}

// ── Lockout rule ────────────────────────────────────────────────────────

@Composable
private fun LockoutPanel(
    activity: FragmentActivity,
    viewModel: VaultContentViewModel,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    val current by produceState<LockoutPolicy?>(initialValue = null) {
        value = viewModel.currentPolicy()
    }
    // null means "keep the saved value"; a tap changes it.
    var failures by rememberSaveable { mutableStateOf<Int?>(null) }
    var durationMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }

    val shownFailures = failures ?: current?.maxFailures ?: LockoutPolicy.DEFAULT.maxFailures
    val shownDuration = durationMs ?: current?.lockoutMs ?: LockoutPolicy.DEFAULT.lockoutMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Wrong attempts before lockout",
            color = FuturaColors.TextSecondary,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        LockoutPolicy.ALLOWED_FAILURES.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { n ->
                    Choice(
                        label = "$n",
                        selected = n == shownFailures,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { failures = n }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = "Lock for",
            color = FuturaColors.TextSecondary,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LockoutPolicy.ALLOWED_DURATIONS_MS.forEach { ms ->
                Choice(
                    label = durationLabel(ms),
                    selected = ms == shownDuration,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { durationMs = ms }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineButton(
                label = "Cancel",
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onCancel
            )
            OutlineButton(
                label = "Save",
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    val policy = LockoutPolicy(shownFailures, shownDuration)
                    viewModel.saveLockout(activity, policy) { msg ->
                        busy = false
                        onDone(msg)
                    }
                }
            )
        }
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────

private fun durationLabel(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1_000L} s"
    else -> "${ms / 60_000L} min"
}

@Composable
private fun OutlineButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .border(
                1.dp,
                if (enabled) FuturaColors.Cyan else FuturaColors.TextDisabled,
                RoundedCornerShape(24.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Text(
            text = label,
            color = if (enabled) FuturaColors.Cyan else FuturaColors.TextDisabled,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) FuturaColors.Cyan else FuturaColors.SurfaceRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = if (selected) FuturaColors.OLEDBlack else FuturaColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
