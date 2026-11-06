package com.futura.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

/** Shows the report of the last crash, if there is one, with a Copy button. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReport.pending(context)) }
    val text = report ?: return

    fun dismiss() {
        CrashReport.clear(context)
        report = null
    }

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("The app closed unexpectedly") },
        text = {
            Column(modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                Text(text = text, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Crash report", text))
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = { dismiss() }) { Text("Close") } }
    )
}
