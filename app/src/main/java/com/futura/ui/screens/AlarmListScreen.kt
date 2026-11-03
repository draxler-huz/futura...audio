// app/src/main/java/com/futura/ui/screens/AlarmListScreen.kt
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
    onToggle: (AlarmSpec, Boolean) -> Unit,
    onDelete: (AlarmSpec) -> Unit,
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
            TopBar(nextAlarm = nextAlarm, onOpenVault = onOpenVault)

            FilterRow(selected = filter, onSelect = { filter = it })

            if (visible.isEmpty()) {
                EmptyState(filtered = filter != AlarmFilter.ALL && alarms.isNotEmpty())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
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
                            onToggle = { onToggle(spec, it) },
                            onDelete = { onDelete(spec) }
                        )
                    }
                }
            }
        }

        AddButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        )
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────

@Composable
private fun TopBar(nextAlarm: AlarmSpec?, onOpenVault: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "ALARMS",
            color = FuturaColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = nextAlarm?.let { "Next: ${formatTime(it.hour, it.minute)} · ${formatRelative(it)}" }
                ?: "No alarms are set",
            color = FuturaColors.TextSecondary,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Open vault",
            color = FuturaColors.Amber,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onOpenVault)
        )
    }
}

@Composable
private fun FilterRow(selected: AlarmFilter, onSelect: (AlarmFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AlarmFilter.values().forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option.label, fontSize = 12.sp) }
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
            .clip(RoundedCornerShape(16.dp))
            .background(FuturaColors.SurfaceRaised)
            .border(1.dp, FuturaColors.TextDisabled, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
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
                    checkedThumbColor = FuturaColors.OLEDBlack
                )
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = formatRecurrence(spec.recurrenceMask),
            color = FuturaColors.TextSecondary,
            fontSize = 13.sp
        )

        if (spec.label.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = spec.label,
                color = FuturaColors.TextPrimary,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tag(
                text = if (spec.biometricLocked) "Biometric shield on" else "Unrestricted",
                color = if (spec.biometricLocked) FuturaColors.Amber else FuturaColors.TextTertiary
            )
            Tag(
                text = "Ramp ${spec.rampDurationSeconds / 60}m ${spec.rampDurationSeconds % 60}s",
                color = FuturaColors.TextTertiary
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Delete",
                color = FuturaColors.Error,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onDelete).padding(4.dp)
            )
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(FuturaColors.Cyan)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = "+",
            color = FuturaColors.OLEDBlack,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (filtered) "No alarms match this filter." else "No alarms yet. Tap + to add one.",
            color = FuturaColors.TextTertiary,
            fontSize = 14.sp
        )
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
    return "in ${minutes / 60}h ${minutes % 60}m"
}
