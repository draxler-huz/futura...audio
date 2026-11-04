package com.futura.audio.ui.viewmodel

import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory

data class MediaHubUiState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedCategory: PipelineCategory = PipelineCategory.COMPLIANT_STANDARD,
    val activeWebViewUrl: String? = null // Non-null when a web embed needs to open in a container
)
