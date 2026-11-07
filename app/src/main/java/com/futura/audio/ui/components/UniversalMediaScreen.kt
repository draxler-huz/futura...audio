package com.futura.audio.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.ui.viewmodel.UniversalMediaViewModel
import kotlin.math.absoluteValue

@Composable
fun UniversalMediaHubScreen(viewModel: UniversalMediaViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var inputUrl by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0C10))) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(text = "Universal Media Hub", color = Color.White, fontSize = 24.sp, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.selectedCategory == PipelineCategory.COMPLIANT_STANDARD,
                    onClick = { viewModel.setCategoryFilter(PipelineCategory.COMPLIANT_STANDARD) },
                    label = { Text("Compliant Archives (Cat 1)") }
                )
                FilterChip(
                    selected = uiState.selectedCategory == PipelineCategory.ADVANCED_EXTRACTION,
                    onClick = { viewModel.setCategoryFilter(PipelineCategory.ADVANCED_EXTRACTION) },
                    label = { Text("Extraction Sources (Cat 2)") }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Instant Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text("Search across archives, tracks, or tags...", color = Color.Gray, fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Cyan) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = Color.Gray)
                        }
                    }
                },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            val activeItems = uiState.filteredItems

            if (activeItems.isNotEmpty()) {
                Text(text = "RESULTS (${activeItems.size})", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.5.sp)
                Spacer(modifier = Modifier.height(8.dp))
                HubCoverFlow(activeItems) { viewModel.onMediaItemClicked(it) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // URL Ingestion Bar
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF141822)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    placeholder = { Text("Paste open archive or feed URL...", color = Color.Gray, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { viewModel.processAndAddUrl(inputUrl); inputUrl = "" }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Cyan)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(activeItems, key = { it.id.value }) { item ->
                    GlassmorphicCard(
                        item = item,
                        downloading = uiState.downloadingId == item.id.value,
                        onClick = { viewModel.onMediaItemClicked(item) },
                        onDownload = { viewModel.download(item) }
                    )
                }
            }
        }

        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)
        }

        val banner = uiState.downloadMessage ?: uiState.errorMessage
        if (banner != null) {
            Surface(
                color = Color(0xFF141822),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = banner, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        viewModel.dismissDownloadMessage()
                        viewModel.clearError()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.Gray)
                    }
                }
            }
        }

        uiState.activeWebViewUrl?.let { url ->
            HubWebViewDialog(url) { viewModel.dismissWebView() }
        }
    }
}

@Composable
fun HubCoverFlow(items: List<com.futura.audio.domain.MediaItem>, onSelect: (com.futura.audio.domain.MediaItem) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    HorizontalPager(state = pagerState, contentPadding = PaddingValues(horizontal = 80.dp), modifier = Modifier.fillMaxWidth().height(170.dp)) { page ->
        val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
        val scale = lerp(0.75f, 1.0f, 1f - offset.absoluteValue.coerceIn(0f, 1f))
        val rotationY = lerp(45f, 0f, 1f - offset.coerceIn(-1f, 1f).absoluteValue) * if (offset < 0) -1f else 1f

        Box(
            modifier = Modifier.size(140.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; this.rotationY = rotationY; cameraDistance = 12 * density }
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF202636), Color(0xFF10131C))))
                .clickable { onSelect(items[page]) },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = items[page].title, color = Color.White, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun GlassmorphicCard(
    item: com.futura.audio.domain.MediaItem,
    downloading: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (item.isVideo) Icons.Default.Movie else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.Cyan
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                Text(text = "${item.sourceName} • ${item.subcategory.value}", color = Color.Gray, fontSize = 11.sp)
            }
            if (downloading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.Cyan, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.Cyan)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HubWebViewDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.7f), color = Color(0xFF0F1115)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Web Embed", color = Color.White)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null, tint = Color.White) }
                }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(url)
                    }
                })
            }
        }
    }
}
