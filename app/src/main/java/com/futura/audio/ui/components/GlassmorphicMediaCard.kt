package com.futura.audio.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.futura.audio.domain.MediaItem
import com.futura.audio.domain.PipelineCategory

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
            // Thumbnail: Coil artwork, or a play icon when no artwork URL exists
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C2230)),
                contentAlignment = Alignment.Center
            ) {
                if (!item.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.artworkUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = accentColor)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                Row {
                    Text(text = item.sourceName, color = Color.Gray, fontSize = 11.sp)
                    Text(text = " • ${item.subcategory.value}", color = accentColor, fontSize = 10.sp)
                }
            }
        }
    }
}
