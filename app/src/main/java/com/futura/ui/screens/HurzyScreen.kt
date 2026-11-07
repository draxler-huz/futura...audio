package com.futura.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.futura.R
import com.futura.ui.theme.FuturaColors
import java.util.Calendar

/**
 * Hurzy — the discovery tab.
 *
 * A Spotify-style home feed: a time-aware greeting, a pill search field, a
 * two-column "Recently played" grid, and full-width carousels for
 * recommended playlists and shows. The palette stays on the Futura brand
 * emerald rather than Spotify's own green, so the tab belongs to the rest
 * of the app.
 *
 * Content is placeholder data held in this file; wiring it to the library,
 * the native engine, or a ViewModel is a later, additive step.
 */
@Composable
fun HurzyScreen(
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    val greetingRes = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    val greeting = stringResource(greetingRes)

    val recent = remember { HurzySamples.recentlyPlayed }
    val madeForYou = remember { HurzySamples.madeForYou }
    val mixes = remember { HurzySamples.mixes }
    val visibleRecent = if (query.isBlank()) {
        recent
    } else {
        recent.filter { it.title.contains(query, ignoreCase = true) }
    }

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
        // ── Greeting header ──────────────────────────────────────────────
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzyHeader(greeting = greeting)
        }

        // ── Search ───────────────────────────────────────────────────────
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzySearchBar(
                query = query,
                onQueryChange = { query = it }
            )
        }

        // ── Recently played ──────────────────────────────────────────────
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzySectionHeader(title = stringResource(R.string.hurzy_section_recent))
        }
        if (visibleRecent.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HurzyEmptyState(
                    text = stringResource(
                        if (query.isBlank()) R.string.hurzy_empty else R.string.library_search_empty
                    )
                )
            }
        } else {
            items(visibleRecent, key = { it.title }) { item ->
                HurzyRecentCard(item = item, onClick = onOpenNowPlaying)
            }
        }

        // ── Made for you ─────────────────────────────────────────────────
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzySectionHeader(title = stringResource(R.string.hurzy_section_made_for_you))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzyCarousel(items = madeForYou, onItemClick = onOpenNowPlaying)
        }

        // ── Your top mixes ───────────────────────────────────────────────
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzySectionHeader(title = stringResource(R.string.hurzy_section_mixes))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            HurzyCarousel(items = mixes, onItemClick = onOpenNowPlaying)
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
        IconButton(onClick = { /* Filter seam */ }) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = stringResource(R.string.hurzy_filter_cd),
                tint = FuturaColors.TextTertiary
            )
        }
        IconButton(onClick = { /* Settings seam */ }) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.hurzy_settings_cd),
                tint = FuturaColors.TextTertiary
            )
        }
    }
}

@Composable
private fun HurzySearchBar(query: String, onQueryChange: (String) -> Unit) {
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
            IconButton(onClick = { onQueryChange("") }) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = FuturaColors.TextPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { /* See-all seam */ }) {
            Text(
                text = stringResource(R.string.hurzy_see_all),
                color = FuturaColors.TextTertiary,
                style = MaterialTheme.typography.labelLarge
            )
        }
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

/** Compact two-column tile used by "Recently played". */
@Composable
private fun HurzyRecentCard(item: HurzyItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FuturaColors.SurfaceRaised)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HurzyArtwork(item = item, size = 64.dp, corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.title,
            color = FuturaColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

/** Full-width horizontal carousel of larger artwork cards. */
@Composable
private fun HurzyCarousel(items: List<HurzyItem>, onItemClick: () -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items, key = { it.title }) { item ->
            HurzyFeatureCard(item = item, onClick = onItemClick)
        }
    }
}

@Composable
private fun HurzyFeatureCard(item: HurzyItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        HurzyArtwork(item = item, size = 144.dp, corner = 12.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            color = FuturaColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.subtitle,
            color = FuturaColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Artwork placeholder: a brand-tinted gradient with a music glyph. Swap for
 * a Coil `AsyncImage` once items carry real cover URLs.
 */
@Composable
private fun HurzyArtwork(item: HurzyItem, size: Dp, corner: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(listOf(item.tint, FuturaColors.SurfaceRecessed))
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(size * 0.35f)
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Models & placeholder content
// ═════════════════════════════════════════════════════════════════════════

/** One card on the Hurzy feed. */
data class HurzyItem(
    val title: String,
    val subtitle: String,
    val tint: Color
)

private fun greetingForHour(hour: Int): Int = when (hour) {
    in 0..11 -> R.string.hurzy_greeting_morning
    in 12..16 -> R.string.hurzy_greeting_afternoon
    else -> R.string.hurzy_greeting_evening
}

/** Static feed content until the library and recommendation data are wired. */
private object HurzySamples {

    private val emeraldA = Color(0xFF004D26)
    private val emeraldB = Color(0xFF006B34)
    private val cyanA = Color(0xFF004A55)
    private val amberA = Color(0xFF5C4000)

    val recentlyPlayed = listOf(
        HurzyItem("Night Drive", "Playlist", emeraldA),
        HurzyItem("Focus Flow", "Playlist", cyanA),
        HurzyItem("Deep Sleep", "Playlist", emeraldB),
        HurzyItem("Morning Lift", "Playlist", amberA),
        HurzyItem("Rain on Glass", "Ambient", emeraldA),
        HurzyItem("Analog Dreams", "Album", cyanA)
    )

    val madeForYou = listOf(
        HurzyItem("Your Daily Mix 1", "Futura, and more", emeraldB),
        HurzyItem("Discover Weekly", "Fresh picks for you", cyanA),
        HurzyItem("Deep Focus", "Instrumental, ambient", emeraldA),
        HurzyItem("Late Night", "Chill, downtempo", amberA),
        HurzyItem("Rewind", "Songs you loved", cyanA)
    )

    val mixes = listOf(
        HurzyItem("Synthwave Mix", "Neon, retro, synth", emeraldB),
        HurzyItem("Lo-Fi Mix", "Beats to relax to", cyanA),
        HurzyItem("Ambient Mix", "Textures and drones", emeraldA),
        HurzyItem("Jazz Mix", "Standards and more", amberA),
        HurzyItem("Classical Mix", "Orchestral calm", emeraldB)
    )
}
