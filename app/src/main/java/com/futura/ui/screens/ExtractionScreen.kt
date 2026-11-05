package com.futura.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futura.data.extraction.ExtractionEvents
import com.futura.data.extraction.model.ExtractionState
import com.futura.data.extraction.workflow.F21MediaExtractionViewModel

@Composable
fun ExtractionScreen(
    onBack: () -> Unit,
    viewModel: F21MediaExtractionViewModel = hiltViewModel()
) {
    val catalog by viewModel.mediaCatalog.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var urlInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.scan(listOf(MUSIC_DIRECTORY))
        } else {
            statusMessage = "Audio permission is required to scan the Music folder."
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ExtractionEvents.ScanStarted -> {
                    statusMessage = "Starting scan of " + event.pathCount + " folder(s)…"
                }
                is ExtractionEvents.ScanProgressed -> {
                    statusMessage = "Scanned " + event.processed + " / " + event.total + " files"
                }
                is ExtractionEvents.ScanCompleted -> {
                    statusMessage = "Found " + event.trackCount + " audio track(s)"
                }
                is ExtractionEvents.ScanFailed -> statusMessage = event.reason
                else -> Unit
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("Back")
            }
            Text(
                text = "Extraction Pipeline",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Text(
            text = "Paste a direct audio-file URL, or scan the device Music folder.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Audio URL") },
            placeholder = { Text("https://example.com/audio.mp3") },
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val url = urlInput.trim()
                    if (url.isNotEmpty()) {
                        statusMessage = "Downloading audio…"
                        viewModel.extractFromTarget(url)
                        urlInput = ""
                    }
                },
                enabled = urlInput.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Fetch URL")
            }

            OutlinedButton(
                onClick = {
                    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    permissionLauncher.launch(permission)
                }
            ) {
                Text("Scan Music")
            }
        }

        if (state is ExtractionState.Scanning) {
            CircularProgressIndicator()
        }
        statusMessage?.let { message ->
            Text(
                text = message,
                color = if (state is ExtractionState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (catalog.isEmpty()) {
            Text(
                text = "No audio tracks yet.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(catalog, key = { it.id }) { track ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = track.title?.takeIf { it.isNotBlank() } ?: track.fileName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = track.format + " • " + track.category.name,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

private const val MUSIC_DIRECTORY = "/storage/emulated/0/Music"
