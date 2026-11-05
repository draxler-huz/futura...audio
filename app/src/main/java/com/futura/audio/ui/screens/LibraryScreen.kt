package com.futura.audio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futura.audio.ui.components.EmbeddedWebViewDialog
import com.futura.audio.ui.components.GlassmorphicMediaCard
import com.futura.audio.ui.viewmodel.LibraryViewModel

/**
 * Saved music and video: files added with the picker, device media, and
 * sources saved from the Media Hub. Tapping plays it; video shows its picture.
 */
@Composable
fun LibraryScreen(
    onOpenNowPlaying: () -> Unit,
    onOpenMediaHub: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val savedItems by viewModel.items.collectAsStateWithLifecycle()
    val webUrl by viewModel.activeWebViewUrl.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Music and Videos tabs. Video is recognised by the item's isVideo flag.
    var tab by rememberSaveable { mutableStateOf(0) }
    val visibleItems = savedItems.filter { if (tab == 0) !it.isVideo else it.isVideo }

    // Reload each time the screen is shown, so sources added elsewhere appear.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.addFiles(uris) }

    val mediaPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val requestMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.any { it }) viewModel.scanDevice() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
            .padding(16.dp)
    ) {
        Text(
            text = "Library",
            color = Color.White,
            fontSize = 24.sp,
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenNowPlaying) { Text("Now Playing") }
            OutlinedButton(onClick = onOpenMediaHub) { Text("Media Hub") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { pickFiles.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream")) }
            ) { Text("Add files") }
            OutlinedButton(
                onClick = {
                    val granted = mediaPermissions.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                    if (granted) viewModel.scanDevice() else requestMedia.launch(mediaPermissions)
                },
                enabled = !scanning
            ) { Text(if (scanning) "Scanning..." else "Scan device") }
        }
        Spacer(Modifier.height(16.dp))
        TabRow(selectedTabIndex = tab, containerColor = Color(0xFF0A0C10), contentColor = Color.White) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Music") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Videos") })
        }
        Spacer(Modifier.height(8.dp))

        if (visibleItems.isEmpty()) {
            Text(
                text = if (tab == 0) {
                    "No music yet. Add files, scan the device, or share a file to Futura from another app."
                } else {
                    "No videos yet. Add files, scan the device, or share a video to Futura from another app."
                },
                color = Color.Gray,
                fontSize = 14.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visibleItems, key = { it.id }) { item ->
                    GlassmorphicMediaCard(
                        item = item,
                        // Queue the visible list so Next and auto-advance continue through it.
                        onClick = { viewModel.play(item, visibleItems, onOpenNowPlaying) }
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = Color(0xFFFF4081), fontSize = 12.sp)
        }
    }

    webUrl?.let { url ->
        EmbeddedWebViewDialog(url = url, onDismiss = viewModel::dismissWebView)
    }
}
