package com.futura.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.ui.viewmodel.UniversalMediaViewModel
import kotlin.math.absoluteValue

@Composable
fun UniversalMediaScreen(
    viewModel: UniversalMediaViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputUrl by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0C10))
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "Universal Media Hub",
                color = Color.White,
                fontSize = 24.sp,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedCategory == PipelineCategory.COMPLIANT_STANDARD,
                    onClick = { viewModel.setCategoryFilter(PipelineCategory.COMPLIANT_STANDARD) },
                    label = { Text("Compliant (Cat 1)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1E222B),
                        selectedLabelColor = Color(0xFF00E5FF)
                    )
                )
                FilterChip(
                    selected = uiState.selectedCategory == PipelineCategory.ADVANCED_EXTRACTION,
                    onClick = { viewModel.setCategoryFilter(PipelineCategory.ADVANCED_EXTRACTION) },
                    label = { Text("Extraction (Cat 2)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1E222B),
                        selectedLabelColor = Color(0xFFFF4081)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filtered Items
            val activeItems = uiState.items.filter { it.category == uiState.selectedCategory }

            // 3D Cover-Flow
            if (activeItems.isNotEmpty()) {
                Text(
                    text = "COVER FLOW",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                CoverFlowCarousel(
                    items = activeItems,
                    onItemSelect = { viewModel.onMediaItemClicked(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Input URL Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF141822))
                    .border(1.dp, Color(0xFF262C3A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    placeholder = { Text("Paste media link or feed URL...", color = Color.Gray, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        viewModel.processAndAddUrl(inputUrl, uiState.selectedCategory)
                        inputUrl = ""
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (uiState.selectedCategory == PipelineCategory.COMPLIANT_STANDARD) Color(0xFF00E5FF) else Color(0xFFFF4081))
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Link", tint = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Media List
            Text(
                text = "ALL SAVED SOURCES",
                color = Color.Gray,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(activeItems, key = { it.id }) { item ->
                    GlassmorphicMediaCard(
                        item = item,
                        onClick = { viewModel.onMediaItemClicked(item) }
                    )
                }
            }
        }

        // Loading Indicator Overlay
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF00E5FF)
            )
        }

        // Web Embed WebView Dialog
        uiState.activeWebViewUrl?.let { url ->
            EmbeddedWebViewDialog(
                url = url,
                onDismiss = { viewModel.dismissWebViewContainer() }
            )
        }
    }
}

@Composable
fun CoverFlowCarousel(
    items: List<MediaItem>,
    onItemSelect: (MediaItem) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(horizontal = 100.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) { page ->
        val pageOffset = (
            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        )

        val scale = lerp(0.72f, 1.0f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
        val rotationY = lerp(55f, 0f, 1f - pageOffset.coerceIn(-1f, 1f).absoluteValue) * if (pageOffset < 0) -1f else 1f
        val alpha = lerp(0.45f, 1.0f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
        val accentColor = if (items[page].category == PipelineCategory.COMPLIANT_STANDARD) Color(0xFF00E5FF) else Color(0xFFFF4081)

        // Outer box centers the card within the full-width page. Without it the card hugs the top-left.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.rotationY = rotationY
                        this.alpha = alpha
                        cameraDistance = 12 * density
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF202636), Color(0xFF10131C))))
                    .border(1.5.dp, accentColor.copy(alpha = alpha), RoundedCornerShape(16.dp))
                    .clickable { onItemSelect(items[page]) },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = accentColor, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = items[page].title, color = Color.White, fontSize = 12.sp, maxLines = 1)
                    Text(text = items[page].sourceName, color = Color.Gray, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun GlassmorphicMediaCard(item: MediaItem, onClick: () -> Unit) {
    val accentColor = if (item.category == PipelineCategory.COMPLIANT_STANDARD) Color(0xFF00E5FF) else Color(0xFFFF4081)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722).copy(alpha = 0.8f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C2230)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = accentColor)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                Row {
                    Text(text = item.sourceName, color = Color.Gray, fontSize = 11.sp)
                    Text(text = " • ${item.subcategory}", color = accentColor, fontSize = 10.sp)
                }
            }
        }
    }
}
