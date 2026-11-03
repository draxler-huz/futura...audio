// app/src/main/java/com/futura/ui/screens/AlarmEditorScreen.kt
package com.futura.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.alarm.AlarmSpec
import com.futura.alarm.VaultUnlockProof
import com.futura.ui.theme.FuturaColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ramp curves, mapped to the `rampProfile` exponent in [1, 4] (see AlarmSpec). */
private enum class RampCurve(val label: String, val exponent: Float) {
    LINEAR("Linear", 1.0f),
    EXPONENTIAL("Exponential", 2.0f),
    LOGARITHMIC("Logarithmic", 4.0f)
}

/**
 * Full-screen editor for one alarm.
 *
 * Edits a local draft and hands it to [onSave]. [onSave] returns an error
 * message, or null once the save has started. When an existing alarm is
 * biometric-shielded, changes first go through [requireAuth].
 */
@Composable
fun AlarmEditorScreen(
    initial: AlarmSpec,
    isEditing: Boolean,
    biometricAvailable: Boolean,
    requireAuth: (onGranted: (VaultUnlockProof) -> Unit) -> Unit,
    onSave: (AlarmSpec, VaultUnlockProof?, onResult: (String?) -> Unit) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    fun commit(proof: VaultUnlockProof?) {
        onSave(draft, proof) { error = it }
    }

    fun onSaveClicked() {
        // Editing a shielded alarm needs a fresh proof from the biometric gate.
        if (isEditing && initial.biometricLocked) {
            requireAuth { proof -> commit(proof) }
        } else {
            commit(null)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack)
            .statusBarsPadding()
    ) {
        TopBar(
            title = if (isEditing) "EDIT ALARM" else "NEW ALARM",
            onCancel = onCancel,
            onSave = { onSaveClicked() }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TimeSection(
                draft = draft,
                onChange = { draft = it }
            )

            Section(title = "LABEL") {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { draft = draft.copy(label = it.take(60)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Morning rotation", color = FuturaColors.TextTertiary) }
                )
            }

            Section(title = "REPEAT") {
                DaySelector(
                    mask = draft.recurrenceMask,
                    onMaskChange = { draft = draft.copy(recurrenceMask = it) }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroChip("Once", selected = draft.recurrenceMask == 0) {
                        draft = draft.copy(recurrenceMask = 0)
                    }
                    MacroChip("Weekdays", selected = draft.recurrenceMask == 0x3E) {
                        draft = draft.copy(recurrenceMask = 0x3E)
                    }
                    MacroChip("Every day", selected = draft.recurrenceMask == 0x7F) {
                        draft = draft.copy(recurrenceMask = 0x7F)
                    }
                }
            }

            Section(title = "RAMP") {
                val minutes = draft.rampDurationSeconds / 60
                Text(
                    text = "Duration: $minutes min",
                    color = FuturaColors.TextPrimary,
                    fontSize = 14.sp
                )
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = {
                        draft = draft.copy(rampDurationSeconds = it.roundToInt() * 60)
                    },
                    valueRange = 0f..30f,
                    steps = 29
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RampCurve.values().forEach { curve ->
                        FilterChip(
                            selected = abs(draft.rampProfile - curve.exponent) < 0.01f,
                            onClick = { draft = draft.copy(rampProfile = curve.exponent) },
                            label = { Text(curve.label, fontSize = 12.sp) }
                        )
                    }
                }
            }

            Section(title = "BIOMETRIC SHIELD") {
                ToggleRow(
                    label = "Require biometrics to change or dismiss",
                    checked = draft.biometricLocked,
                    enabled = biometricAvailable || draft.biometricLocked,
                    onChange = { draft = draft.copy(biometricLocked = it) }
                )
                if (!biometricAvailable) {
                    Text(
                        text = "Set up a fingerprint, face or screen lock on this device to use the shield.",
                        color = FuturaColors.Warning,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Stepper(
                    label = "Snoozes allowed",
                    value = draft.maxSnoozeCycles,
                    range = 0..10,
                    onChange = { draft = draft.copy(maxSnoozeCycles = it) }
                )
            }

            Section(title = "STATUS") {
                ToggleRow(
                    label = "Active",
                    checked = draft.enabled,
                    enabled = true,
                    onChange = { draft = draft.copy(enabled = it) }
                )
            }

            error?.let {
                Text(text = it, color = FuturaColors.Error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────

@Composable
private fun TopBar(title: String, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel", color = FuturaColors.TextTertiary)
        }
        Text(
            text = title,
            color = FuturaColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp
        )
        TextButton(onClick = onSave) {
            Text("Save", color = FuturaColors.Cyan, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FuturaColors.SurfaceRaised)
            .border(1.dp, FuturaColors.TextDisabled, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = FuturaColors.TextSecondary,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun TimeSection(draft: AlarmSpec, onChange: (AlarmSpec) -> Unit) {
    val isPm = draft.hour >= 12
    val hour12 = if (draft.hour % 12 == 0) 12 else draft.hour % 12

    Section(title = "TIME") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            StepperDigit(
                text = "%02d".format(hour12),
                onMinus = {
                    val h = if (hour12 == 1) 12 else hour12 - 1
                    onChange(draft.copy(hour = (h % 12) + if (isPm) 12 else 0))
                },
                onPlus = {
                    val h = if (hour12 == 12) 1 else hour12 + 1
                    onChange(draft.copy(hour = (h % 12) + if (isPm) 12 else 0))
                }
            )
            Text(
                text = ":",
                color = FuturaColors.TextPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            StepperDigit(
                text = "%02d".format(draft.minute),
                onMinus = { onChange(draft.copy(minute = (draft.minute + 59) % 60)) },
                onPlus = { onChange(draft.copy(minute = (draft.minute + 1) % 60)) }
            )
            Spacer(Modifier.width(16.dp))
            Column {
                MacroChip("AM", selected = !isPm) {
                    onChange(draft.copy(hour = hour12 % 12))
                }
                Spacer(Modifier.height(6.dp))
                MacroChip("PM", selected = isPm) {
                    onChange(draft.copy(hour = (hour12 % 12) + 12))
                }
            }
        }
    }
}

@Composable
private fun StepperDigit(text: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onPlus) {
            Text("▲", color = FuturaColors.Cyan)
        }
        Text(
            text = text,
            color = FuturaColors.TextPrimary,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold
        )
        TextButton(onClick = onMinus) {
            Text("▼", color = FuturaColors.Cyan)
        }
    }
}

@Composable
private fun DaySelector(mask: Int, onMaskChange: (Int) -> Unit) {
    val letters = listOf("S", "M", "T", "W", "T", "F", "S")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        letters.forEachIndexed { index, letter ->
            val on = (mask shr index) and 1 == 1
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) FuturaColors.Cyan else FuturaColors.SurfaceRecessed)
                    .clickable { onMaskChange(mask xor (1 shl index)) }
            ) {
                Text(
                    text = letter,
                    color = if (on) FuturaColors.OLEDBlack else FuturaColors.TextTertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun MacroChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) }
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = FuturaColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = FuturaColors.Emerald,
                checkedThumbColor = FuturaColors.OLEDBlack
            )
        )
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = FuturaColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { if (value > range.first) onChange(value - 1) }) {
            Text("−", color = FuturaColors.Cyan, fontSize = 18.sp)
        }
        Text(text = value.toString(), color = FuturaColors.TextPrimary, fontWeight = FontWeight.Bold)
        TextButton(onClick = { if (value < range.last) onChange(value + 1) }) {
            Text("+", color = FuturaColors.Cyan, fontSize = 18.sp)
        }
    }
}
