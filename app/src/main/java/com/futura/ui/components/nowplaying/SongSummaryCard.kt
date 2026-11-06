package com.futura.ui.components.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the summary card shows. Only the active track's summary is ever in this state. */
sealed interface SongSummaryState {
    data object Idle : SongSummaryState
    data object Loading : SongSummaryState
    data class Ready(val text: String) : SongSummaryState
    data class Failed(val message: String) : SongSummaryState
}

/**
 * Shown in the lyrics slot when no synced lyrics exist. Summarize is an
 * explicit tap, so no request is made until the user asks.
 */
@Composable
fun SongSummaryCard(
    state: SongSummaryState,
    hasKey: Boolean,
    onSummarize: () -> Unit,
    onSaveKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showKeyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF131722), RoundedCornerShape(14.dp))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "SONG SUMMARY", color = Color(0xFF00E5FF), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        when (state) {
            SongSummaryState.Idle -> Text(
                text = "Gemini researches this song and describes its meaning, mood and themes. Lyrics are not shown.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            SongSummaryState.Loading -> CircularProgressIndicator(color = Color(0xFF00E5FF))
            is SongSummaryState.Ready -> Text(text = state.text, color = Color.White, fontSize = 14.sp)
            is SongSummaryState.Failed -> Text(text = state.message, color = Color(0xFFFF8A9B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasKey) {
                OutlinedButton(
                    onClick = onSummarize,
                    enabled = state != SongSummaryState.Loading
                ) { Text("Summarize") }
                TextButton(onClick = { showKeyDialog = true }) { Text("Change key") }
            } else {
                OutlinedButton(onClick = { showKeyDialog = true }) { Text("Add Gemini key") }
            }
        }
    }

    if (showKeyDialog) {
        GeminiKeyDialog(
            onDismiss = { showKeyDialog = false },
            onSave = onSaveKey
        )
    }
}

@Composable
private fun GeminiKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini API key") },
        text = {
            Column {
                Text(
                    text = "Stored encrypted on this device. Sent only to Google's Gemini API.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("Paste your key") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(key)
                    onDismiss()
                },
                enabled = key.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
