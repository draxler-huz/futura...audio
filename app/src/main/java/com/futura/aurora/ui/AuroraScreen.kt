package com.futura.aurora.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import com.futura.aurora.AuroraKind
import com.futura.aurora.AuroraMediaItem
import com.futura.aurora.LicensePolicy

private const val ATTRIBUTION_PROMPT =
    "This track requires attribution. Copy this text to your video description:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuroraScreen(onBack: () -> Unit, viewModel: AuroraViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetItem = state.all.firstOrNull { it.id == state.sheetItemId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aurora") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            AuroraBottomBar(
                state = state,
                onDownloadSelected = viewModel::downloadSelected,
                onClearSelection = viewModel::clearSelection,
                onCancel = viewModel::cancelBatch,
                onRetry = viewModel::retryFailed
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val isAudio = state.kind == AuroraKind.AUDIO
            TabRow(selectedTabIndex = if (isAudio) 0 else 1) {
                Tab(
                    selected = isAudio,
                    onClick = { viewModel.onKindChange(AuroraKind.AUDIO) },
                    text = { Text("Music") }
                )
                Tab(
                    selected = !isAudio,
                    onClick = { viewModel.onKindChange(AuroraKind.VIDEO) },
                    text = { Text("Video") }
                )
            }

            SearchRow(
                query = state.query,
                loading = state.loading,
                strict = state.policy == LicensePolicy.STRICT,
                onQueryChange = viewModel::onQueryChange,
                onToggleFilter = viewModel::togglePolicy
            )

            if (state.policy == LicensePolicy.STRICT) {
                Text(
                    "Note: This will significantly reduce the number of search results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            if (state.failedSources.isNotEmpty()) {
                Text(
                    "Could not reach: ${state.failedSources.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val visible = state.visible
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.query.isBlank() -> CenterText(
                        "Search Internet Archive and Wikimedia Commons."
                    )
                    state.searched && visible.isEmpty() && !state.loading -> CenterText(
                        if (state.policy == LicensePolicy.STRICT) {
                            "No results with the strict license filter."
                        } else {
                            "No results for this search."
                        },
                        action = if (state.policy == LicensePolicy.STRICT) {
                            { TextButton(onClick = viewModel::clearFilters) { Text("Clear filters") } }
                        } else {
                            null
                        }
                    )
                    else -> LazyColumn {
                        items(visible, key = { it.id }) { item ->
                            ResultRow(
                                item = item,
                                selected = item.id in state.selected,
                                job = state.jobs[item.id],
                                onClick = { viewModel.toggleSelected(item.id) },
                                onMore = { viewModel.openSheet(item.id) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (sheetItem != null) {
        ModalBottomSheet(onDismissRequest = { viewModel.openSheet(null) }) {
            Text(
                sheetItem.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            SheetAction("Download") {
                viewModel.download(sheetItem.id)
                viewModel.openSheet(null)
            }
            SheetAction("Play Preview", enabled = sheetItem.previewUrl != null) {
                openPreview(context, sheetItem)
                viewModel.openSheet(null)
            }
            SheetAction("Copy Attribution", enabled = sheetItem.license.requiresAttribution) {
                copyToClipboard(context, sheetItem.attributionText())
                viewModel.openSheet(null)
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }

    state.attributionText?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAttribution,
            title = { Text("Attribution") },
            text = {
                Column {
                    Text(ATTRIBUTION_PROMPT, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { copyToClipboard(context, text) }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAttribution) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SearchRow(
    query: String,
    loading: Boolean,
    strict: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleFilter: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            },
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggleFilter) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = "License filter",
                tint = if (strict) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultRow(
    item: AuroraMediaItem,
    selected: Boolean,
    job: DownloadJob?,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val downloading = job?.state.isActive()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(url = item.thumbnailUrl, isVideo = item.kind == AuroraKind.VIDEO)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.creator?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                listOfNotNull(item.durationSec?.let(::formatDuration), item.license.label).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            downloading -> {
                val percent = job?.percent ?: 0
                if (percent > 0) {
                    CircularProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            job?.state == WorkInfo.State.SUCCEEDED -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.primary
            )
            job?.state.isFailed() -> Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Download failed",
                tint = MaterialTheme.colorScheme.error
            )
            else -> IconButton(onClick = onMore) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
        }
    }
}

@Composable
private fun Thumbnail(url: String?, isVideo: Boolean) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                if (isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuroraBottomBar(
    state: AuroraUiState,
    onDownloadSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val total = state.batch.size
    val selectedCount = state.selected.size
    // Idle: nothing selected and no batch, so the bar stays hidden.
    if (selectedCount == 0 && total == 0) return

    val active = state.batch.count { state.jobs[it]?.state.isActive() }
    val done = state.batch.count { state.jobs[it]?.state == WorkInfo.State.SUCCEEDED }
    val failed = state.batch.count { state.jobs[it]?.state.isFailed() }

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (active > 0) {
                val finished = total - active
                Text("Downloading $finished of $total…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { finished / total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else if (total > 0) {
                val summary = if (failed == 0) "$done downloaded" else "$done of $total — $failed failed"
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                if (failed > 0) TextButton(onClick = onRetry) { Text("Retry failed") }
            }

            if (selectedCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownloadSelected, modifier = Modifier.weight(1f)) {
                        Text("Download Selected ($selectedCount)")
                    }
                    TextButton(onClick = onClearSelection) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                label,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun CenterText(message: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        action?.invoke()
    }
}

private fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun openPreview(context: Context, item: AuroraMediaItem) {
    val url = item.previewUrl ?: return
    val mime = if (item.kind == AuroraKind.VIDEO) "video/*" else "audio/*"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime))
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No app can play this preview", Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Attribution", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
