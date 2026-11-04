// app/src/main/java/com/futura/ui/screens/AlarmListScreen.kt
package com.futura.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.alarm.AlarmSpec
import com.futura.alarm.VaultUnlockProof
import com.futura.ui.theme.FuturaColors

/** Filter applied to the alarm list. */
enum class AlarmFilter(val label: String) {
    ALL("All"),
    SHIELDED("Biometric Shielded"),
    RAMP("Ramp-Up")
}

/**
 * Root screen: lists every alarm, shows the next one to ring, and
 * provides the entry points to add, edit, toggle and delete alarms.
 */
@Composable
fun AlarmListScreen(
    alarms: List<AlarmSpec>,
    onAdd: () -> Unit,
    onEdit: (AlarmSpec) -> Unit,
    onToggle: (AlarmSpec, Boolean, VaultUnlockProof?) -> Unit,
    onDelete: (AlarmSpec, VaultUnlockProof?) -> Unit,
    requireAuth: ((VaultUnlockProof) -> Unit) -> Unit,
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(AlarmFilter.ALL) }

    val visible = remember(alarms, filter) {
        when (filter) {
            AlarmFilter.ALL -> alarms
            AlarmFilter.SHIELDED -> alarms.filter { it.biometricLocked }
            AlarmFilter.RAMP -> alarms.filter { it.rampDurationSeconds > 0 }
        }
    }

    val nextAlarm = remember(alarms) {
        val now = System.currentTimeMillis()
        alarms
            .filter { it.enabled }
            .minByOrNull { it.nextTriggerMillis(now) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                nextAlarm = nextAlarm,
                hasAlarms = alarms.isNotEmpty(),
                onOpenVault = onOpenVault
            )

            if (alarms.isNotEmpty()) {
                FilterRow(selected = filter, onSelect = { filter = it })
                Spacer(Modifier.height(12.dp))
            }

            if (visible.isEmpty()) {
                EmptyState(
                    filtered = filter != AlarmFilter.ALL && alarms.isNotEmpty(),
                    onAdd = onAdd,
                    onClearFilters = { filter = AlarmFilter.ALL },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visible, key = { it.id }) { spec ->
                        AlarmCard(
                            spec = spec,
                            onClick = { onEdit(spec) },
                            onToggle = { enabled ->
                                gate(spec, requireAuth) { proof ->
                                    onToggle(spec, enabled, proof)
                                }
                            },
                            onDelete = {
                                gate(spec, requireAuth) { proof -> onDelete(spec, proof) }
                            }
                        )
                    }
                }
            }
        }

        // The empty state already has a clear primary action; keep the FAB
        // for the populated list so there is never a duplicate add button.
        if (alarms.isNotEmpty()) {
            AddButton(
                onClick = onAdd,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
}

/** Runs [block] directly, or after the biometric gate for a shielded alarm. */
private fun gate(
    spec: AlarmSpec,
    requireAuth: ((VaultUnlockProof) -> Unit) -> Unit,
    block: (VaultUnlockProof?) -> Unit
) {
    if (spec.biometricLocked) requireAuth(block) else block(null)
}

// ── Pieces ──────────────────────────────────────────────────────────────

@Composable
private fun TopBar(nextAlarm: AlarmSpec?, hasAlarms: Boolean, onOpenVault: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "YOUR ROUTINE",
                color = FuturaColors.Emerald,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Alarms",
                color = FuturaColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = nextAlarm?.let {
                    "Next up  ·  " + formatTime(it.hour, it.minute) + "  ·  " + formatRelative(it)
                } ?: if (hasAlarms) "No active alarms right now" else "Plan a calmer start to your day",
                color = if (nextAlarm != null) FuturaColors.TextSecondary else FuturaColors.TextTertiary,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.width(12.dp))
        Surface(
            onClick = onOpenVault,
            shape = RoundedCornerShape(14.dp),
            color = FuturaColors.SurfaceRecessed,
            modifier = Modifier.border(
                width = 1.dp,
                color = FuturaColors.Amber.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp)
            )
        ) {
            Text(
                text = "Vault",
                color = FuturaColors.Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
            )
        }
    }
}

@Composable
private fun FilterRow(selected: AlarmFilter, onSelect: (AlarmFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AlarmFilter.values().forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option.label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FuturaColors.Cyan.copy(alpha = 0.14f),
                    selectedLabelColor = FuturaColors.Cyan,
                    containerColor = FuturaColors.SurfaceRecessed,
                    labelColor = FuturaColors.TextTertiary
                )
            )
        }
    }
}

@Composable
private fun AlarmCard(
    spec: AlarmSpec,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FuturaColors.SurfaceBase)
            .border(1.dp, FuturaColors.SurfaceRaised, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(spec.hour, spec.minute),
                color = FuturaColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = spec.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = FuturaColors.Emerald,
                    checkedThumbColor = FuturaColors.OLEDBlack,
                    uncheckedTrackColor = FuturaColors.SurfaceRecessed,
                    uncheckedThumbColor = FuturaColors.TextTertiary
                )
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = formatRecurrence(spec.recurrenceMask),
            color = FuturaColors.TextTertiary,
            fontSize = 13.sp
        )

        if (spec.label.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = spec.label,
                color = FuturaColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tag(
                text = if (spec.biometricLocked) "Shielded" else "Open",
                color = if (spec.biometricLocked) FuturaColors.Amber else FuturaColors.TextTertiary
            )
            Tag(
                text = if (spec.rampDurationSeconds > 0) {
                    "Ramp " + spec.rampDurationSeconds / 60 + "m"
                } else "No ramp",
                color = FuturaColors.Cyan
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text("Delete", color = FuturaColors.Error, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(60.dp),
        shape = CircleShape,
        containerColor = FuturaColors.Cyan,
        contentColor = FuturaColors.OLEDBlack
    ) {
        Text("+", fontSize = 29.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyState(
    filtered: Boolean,
    onAdd: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(FuturaColors.SurfaceRecessed)
                .border(1.dp, FuturaColors.SurfaceBase, RoundedCornerShape(28.dp))
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(FuturaColors.Cyan.copy(alpha = 0.1f))
                    .border(1.dp, FuturaColors.Cyan.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (filtered) "↻" else "+",
                    color = FuturaColors.Cyan,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = if (filtered) "No alarms match" else "Make tomorrow easier",
                color = FuturaColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (filtered) {
                    "Try a different filter, or show all of your alarms."
                } else {
                    "Choose a wake-up time, pick your days, and add a gentle ramp-up."
                },
                color = FuturaColors.TextTertiary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))

            if (filtered) {
                TextButton(
                    onClick = onClearFilters,
                    colors = ButtonDefaults.textButtonColors(contentColor = FuturaColors.Cyan)
                ) {
                    Text("Show all alarms", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FuturaColors.Cyan,
                        contentColor = FuturaColors.OLEDBlack
                    )
                ) {
                    Text(
                        text = "Create your first alarm",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ── Formatting (shared with AlarmEditorScreen) ─────────────────────────

/** "06:30 AM" style, 12-hour with AM/PM. */
internal fun formatTime(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return "%02d:%02d %s".format(h12, minute, suffix)
}

/** "Mon, Tue, Wed" / "Daily" / "Weekdays" / "Once". Bit 0 = Sunday. */
internal fun formatRecurrence(mask: Int): String {
    if (mask == 0) return "Once"
    if (mask == 0x7F) return "Daily"
    if (mask == 0x3E) return "Weekdays"
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return names.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.joinToString(", ")
}

/** "in 10h 48m" until the next trigger, or "" for a disabled alarm. */
internal fun formatRelative(spec: AlarmSpec): String {
    val minutes = ((spec.nextTriggerMillis() - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    return "in " + (minutes / 60) + "h " + (minutes % 60) + "m"
}
