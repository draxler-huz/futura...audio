package com.futura.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory
import kotlin.math.absoluteValue

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
        val item = items[page]
        val pageOffset = (
            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        )

        val scale = lerp(0.72f, 1.0f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
        val rotationY = lerp(55f, 0f, 1f - pageOffset.coerceIn(-1f, 1f).absoluteValue) * if (pageOffset < 0) -1f else 1f
        val alpha = lerp(0.45f, 1.0f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
        val accentColor = if (item.category == PipelineCategory.COMPLIANT_STANDARD) Color(0xFF00E5FF) else Color(0xFFFF4081)

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
                    .clickable { onItemSelect(item) },
                contentAlignment = Alignment.Center
            ) {
                if (!item.artworkUrl.isNullOrBlank()) {
                    // Real artwork via Coil
                    AsyncImage(
                        model = item.artworkUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Fallback when no artwork URL is available
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = accentColor, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = item.title, color = Color.White, fontSize = 12.sp, maxLines = 1)
                        Text(text = item.sourceName, color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
