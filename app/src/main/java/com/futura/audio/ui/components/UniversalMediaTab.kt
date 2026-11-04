package com.futura.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.audio.domain.PipelineCategory
import com.futura.audio.ui.viewmodel.UniversalMediaViewModel

@Composable
fun UniversalMediaScreen(
    viewModel: UniversalMediaViewModel,
    onBackClick: (() -> Unit)? = null // Optional: only provided when pushed onto a back stack
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
            // Header with optional back button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBackClick != null) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
                Text(
                    text = "Universal Media Hub",
                    color = Color.White,
                    fontSize = 24.sp,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
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
