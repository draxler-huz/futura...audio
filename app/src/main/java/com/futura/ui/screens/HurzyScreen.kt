package com.futura.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.futura.R
import com.futura.sources.SourceMediaItem
import com.futura.ui.screens.hurzy.HurzyUiState
import com.futura.ui.screens.hurzy.HurzyViewModel
import com.futura.ui.theme.FuturaColors
import java.util.Calendar

/**
 * Hurzy — the discovery tab.
 *
 * A Spotify-style home feed: a time-aware greeting, a debounced search
 * field, and a two-column grid of artwork cards. Content comes from every
 * enabled source through [HurzyViewModel]; tapping a card plays it and
 * queues the rest of the visible list.
 *
 * The palette stays on the Futura brand emerald rather than Spotify's own
 * green, so the tab belongs to the rest of the app.
 */
@Composable
fun HurzyScreen(
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HurzyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val greetingRes = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

    HurzyContent(
        state = state,
        greeting = stringResource(greetingRes),
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::clearQuery,
        onPlay = { item ->
            viewModel.play(item)
            onOpenNowPlaying()
        },
        modifier = modifier
    )
}

@Composable
private fun HurzyContent(
    state: HurzyUiState,
    greeting: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onPlay: (SourceMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 24.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzyHeader(greeting = greeting)
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzySearchBar(
                query = state.query,
                onQueryChange = onQueryChange,
                onClear = onClearQuery
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzySectionHeader(
                title = stringResource(
                    if (state.query.isBlank()) R.string.hurzy_section_made_for_you
                    else R.string.hurzy_section_results
                )
            )
        }

        when {
            state.loading && state.isEmpty -> item(span = { GridItemSpan(maxLineSpan) }) {
                HurzyLoading()
            }

            state.errorMessage != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                HurzyEmptyState(text = state.errorMessage)
            }

            state.isEmpty -> item(span = { GridItemSpan(maxLineSpan) }) {
                HurzyEmptyState(
                    text = stringResource(
                        if (state.searched) R.string.hurzy_no_results else R.string.hurzy_empty
                    )
                )
            }

            else -> items(state.items, key = { it.id }) { item ->
                HurzyTrackCard(item = item, onClick = { onPlay(item) })
            }
        }

        if (state.failedSources.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.hurzy_failed_sources, state.failedSources.size),
                    color = FuturaColors.TextDisabled,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Sections
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun HurzyHeader(greeting: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = greeting,
            color = FuturaColors.TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HurzySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FuturaColors.SurfaceRaised)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = FuturaColors.TextPrimary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.hurzy_search_hint),
                    color = FuturaColors.TextTertiary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = FuturaColors.TextPrimary),
                cursorBrush = SolidColor(FuturaColors.Emerald),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.hurzy_search_clear_cd),
                    tint = FuturaColors.TextTertiary
                )
            }
        }
    }
}

@Composable
private fun HurzySectionHeader(title: String) {
    Text(
        text = title,
        color = FuturaColors.TextPrimary,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    )
}

@Composable
private fun HurzyLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = FuturaColors.Emerald,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun HurzyEmptyState(text: String) {
    Text(
        text = text,
        color = FuturaColors.TextTertiary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

// ═════════════════════════════════════════════════════════════════════════
// Cards
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun HurzyTrackCard(item: SourceMediaItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        HurzyArtwork(item = item)
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            color = FuturaColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.creator ?: item.sourceName,
            color = FuturaColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Real artwork when the source supplies it, otherwise a brand-tinted
 * gradient with a music glyph, so a card never renders blank.
 */
@Composable
private fun HurzyArtwork(item: SourceMediaItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(listOf(FuturaColors.SurfaceRaised, FuturaColors.SurfaceRecessed))
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(40.dp)
        )
        if (!item.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun greetingForHour(hour: Int): Int = when (hour) {
    in 0..11 -> R.string.hurzy_greeting_morning
    in 12..16 -> R.string.hurzy_greeting_afternoon
    else -> R.string.hurzy_greeting_evening
}
