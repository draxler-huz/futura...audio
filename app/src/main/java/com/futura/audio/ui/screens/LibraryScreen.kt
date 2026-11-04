package com.futura.audio.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futura.audio.ui.components.EmbeddedWebViewDialog
import com.futura.audio.ui.components.GlassmorphicMediaCard
import com.futura.audio.ui.viewmodel.LibraryViewModel

/** Saved sources from the Media Hub. Tapping a direct source plays it, then opens Now Playing. */
@Composable
fun LibraryScreen(
    onOpenNowPlaying: () -> Unit,
    onOpenMediaHub: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val savedItems by viewModel.items.collectAsStateWithLifecycle()
    val webUrl by viewModel.activeWebViewUrl.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Reload each time the screen is shown, so sources added in the hub appear.
    LaunchedEffect(Unit) { viewModel.refresh() }

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
        Spacer(Modifier.height(16.dp))
        Text(
            text = "SAVED SOURCES",
            color = Color.Gray,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(8.dp))

        if (savedItems.isEmpty()) {
            Text(
                text = "No saved sources yet. Add a link in the Media Hub.",
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
                items(savedItems, key = { it.id }) { item ->
                    GlassmorphicMediaCard(
                        item = item,
                        onClick = { viewModel.play(item, onOpenNowPlaying) }
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
