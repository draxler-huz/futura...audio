// futura-audio/app/src/main/java/com/futura/ui/screens/NowPlayingScreen.kt
//
// Phase 2 — Now Playing surface. Per the project tree this file is
// annotated "⚙️ Phase 2 — stub", unlike WakeUpOverlayScreen and
// LoadingScreen which are fully implemented.
//
// ── What "stub" means here, concretely ────────────────────────────────────
// The Phase 2 design spec calls for ten sub-components, each of which is
// its own file in the project tree under ui/components/nowplaying/:
//
//     TopBar.kt              StreamInspectorModal.kt     HeroPlayer.kt
//     KineticWaveform.kt     TrackInfo.kt                MultiPanelDeck.kt
//     UpNextPanel.kt         LyricsPanel.kt              RelatedPanel.kt
//     PlaybackDock.kt
//
// The delivered components are wired below, while the remaining
// PlaybackDock seam still uses a local placeholder. The screen therefore
// retains a small amount of local state for the not-yet-delivered playback
// ViewModel and transport component:
//
//   • Matches the layout described in the Phase 2 design spec.
//   • Uses only delivered project symbols (FuturaColors, R.*).
//   • Accepts the same callback surface MainActivity.kt's current
//     NowPlayingPlaceholder exposes, so the swap at the NavHost callsite
//     is a one-line rename once this file exists.
//   • Marks every region that becomes a real component with a
//     `STUB:` comment naming the exact file to substitute.
//
// When each component file lands, the corresponding private `…Stub`
// composable is deleted and its one `…Stub()` callsite becomes
// `TopBar(...)`, `HeroPlayer(...)`, etc. This screen itself does not
// need behavioural rework; only the seam content changes.
//
// ── Real (non-stub) behaviour already present ────────────────────────────
//   • Full-screen layout with safe-drawing insets.
//   • Tab state (`selectedTab`) and tab content switching.
//   • Play/pause button state (`isPlaying`) toggled locally.
//   • Navigation callbacks wired to onAlarmDeck / onLibrary / onDjStudio /
//     onVisualizer / onPrivacy (used by the Alarm Deck chip, the Library
//     footer chip, and the DJ / Visualizer / Privacy preview chips below
//     the tab content — these exist so every route in the NavHost remains
//     reachable during Phase 2 development; they collapse into the real
//     component interactions when those files land).
//
// ── Explicitly stubbed (deliberately not wired) ──────────────────────────
//   • Album art — no Coil load yet because there is no current track.
//   • Playback data — placeholder progress/duration until a real
//     NowPlayingViewModel provides position and duration.
//   • Format badge — opens the delivered Stream Inspector modal with
//     placeholder telemetry until the stream provider is connected.
//   • EQ toggle — clickable but no-op; wires into DspChainController later.
//   • Time display — "00:00 / -00:00" placeholder until a real
//     NowPlayingViewModel provides position/duration.
//   • Playback data — placeholder progress/duration until a real
//     NowPlayingViewModel provides position and duration.
//
// ── Design compliance ────────────────────────────────────────────────────
//   • OLED true black (#000000) root.
//   • Emerald (#00FF66) primary accent, cyan (#00E5FF) secondary.
//   • Glass-morphic chips (translucent SurfaceBase + 1dp accent border).
//   • Monospace ALL-CAPS for badges and metadata rows.
//   • Large glowing play/pause circle in the playback dock.
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                      (generated from delivered resources)
//   • com.futura.ui.theme.FuturaColors  (delivered in theme/Theme.kt)
// All other imports resolve to artifacts declared in
// gradle/libs.versions.toml:
//   • androidx.compose.*                (BOM 2024.10.00)
//   • androidx.compose.material.icons.* (material-icons-extended)
// No future project class or file is referenced.

package com.futura.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.R
import com.futura.ui.components.nowplaying.HeroPlayer
import com.futura.ui.components.nowplaying.KineticWaveform
import com.futura.ui.components.nowplaying.LyricsPanel
import com.futura.ui.components.nowplaying.MultiPanelDeck
import com.futura.ui.components.nowplaying.PlaybackDock
import com.futura.ui.components.nowplaying.RelatedPanel
import com.futura.ui.components.nowplaying.UpNextPanel
import com.futura.ui.components.nowplaying.TrackInfo
import com.futura.ui.components.nowplaying.TopBar
import com.futura.ui.components.nowplaying.StreamInspectorModal
import com.futura.ui.theme.FuturaColors
import com.futura.ui.viewmodel.NowPlayingViewModel

// ═════════════════════════════════════════════════════════════════════════
// Entry point
// ═════════════════════════════════════════════════════════════════════════

/**
 * Phase 2 Now Playing surface (stub skeleton).
 *
 * @param onAlarmDeck   Navigates back to the Phase 1 Wake-Up Overlay
 *                      (the "ALARM DECK" chip in the top bar).
 * @param onLibrary     Navigates to the Phase 3 Library screen.
 * @param onDjStudio    Navigates to the Phase 4 DJ Studio.
 * @param onVisualizer  Navigates to the Phase 5 Visualizer Deck.
 * @param onPrivacy     Navigates to the Phase 6 Privacy Vault.
 * @param modifier      Optional outer modifier.
 *
 * Signature compatibility:
 *   MainActivity.kt currently declares
 *     NowPlayingPlaceholder(onAlarmDeck, onLibrary, onDjStudio,
 *                           onVisualizer, onPrivacy)
 *   This screen exposes exactly the same five parameters in the same
 *   order, so the swap is a single rename at the callsite.
 */
@Composable
fun NowPlayingScreen(
    onAlarmDeck: () -> Unit,
    onLibrary: () -> Unit,
    onDjStudio: () -> Unit,
    onVisualizer: () -> Unit,
    onPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = hiltViewModel()
) {
    var isStreamInspectorVisible by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val bassLevel by viewModel.bassLevel.collectAsStateWithLifecycle()
    val waveformData by viewModel.waveformData.collectAsStateWithLifecycle()
    val playbackProgress = if (uiState.durationMs > 0L) {
        (positionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            TopBar(
                onAlarmDeckClick = onAlarmDeck,
                onFormatBadgeClick = { isStreamInspectorVisible = true },
                onEqToggleClick = viewModel::onEqToggleClick,
                formatBadgeText = uiState.formatBadgeText,
                isEqEnabled = uiState.isEqEnabled
            )

            Spacer(Modifier.height(12.dp))

            HeroPlayer(
                modifier = Modifier.fillMaxWidth(0.72f),
                artworkModel = uiState.artworkModel,
                bassLevel = bassLevel
            )

            Spacer(Modifier.height(20.dp))

            KineticWaveform(
                waveformData = waveformData,
                progress = playbackProgress,
                durationSeconds = uiState.durationMs / 1000L,
                onSeek = viewModel::onSeek,
                trackKey = uiState.trackId
            )

            Spacer(Modifier.height(8.dp))

            TimeLabelStub()

            Spacer(Modifier.height(16.dp))

            TrackInfo(
                title = uiState.trackTitle,
                artist = uiState.trackArtist,
                hasSyncedLyrics = uiState.hasSyncedLyrics
            )

            Spacer(Modifier.height(16.dp))

            MultiPanelDeck(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                upNextContent = {
                    UpNextPanel(
                        items = uiState.upNext,
                        onReorder = viewModel::onReorderQueue,
                        onItemClick = viewModel::onQueueItemClick,
                        onSimilarClick = viewModel::onSimilarSuggestionClick,
                        similarSuggestions = uiState.similarSuggestions
                    )
                },
                lyricsContent = {
                    LyricsPanel(
                        lines = uiState.lyricLines,
                        currentPositionMs = positionMs,
                        onSeekToLine = viewModel::onSeekToLine
                    )
                },
                relatedContent = {
                    RelatedPanel(
                        relatedTracks = uiState.relatedTracks,
                        currentMoods = uiState.currentMoods,
                        onRelatedTrackClick = viewModel::onRelatedTrackClick
                    )
                }
            )

            Spacer(Modifier.height(12.dp))

            PlaybackDock(
                isPlaying = uiState.isPlaying,
                onPlayPauseClick = viewModel::onPlayPauseClick,
                isShuffleEnabled = uiState.isShuffleEnabled,
                isLoopEnabled = uiState.isLoopEnabled,
                onPreviousClick = viewModel::onPreviousClick,
                onNextClick = viewModel::onNextClick,
                onShuffleClick = viewModel::onShuffleClick,
                onLoopClick = viewModel::onLoopClick
            )

            Spacer(Modifier.height(12.dp))
        }

        StreamInspectorModal(
            visible = isStreamInspectorVisible,
            onDismiss = { isStreamInspectorVisible = false },
            sampleRateText = uiState.sampleRateText,
            audioHalText = uiState.audioHalText,
            latencyMs = uiState.latencyMs,
            bufferFrames = uiState.bufferFrames,
            isBitPerfect = uiState.isBitPerfect
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Top bar stub
// ═════════════════════════════════════════════════════════════════════════
//
// Layout: [ALARM DECK chip] [format badge] [EQ icon]
// Matches the Phase 2 spec's "Top Bar & Diagnostics Header".

// ═════════════════════════════════════════════════════════════════════════
// Hero player stub
// ═════════════════════════════════════════════════════════════════════════
//
// Layout: album art placeholder + static waveform + time label +
// track info + "DUET / SYNCED LYRICS AVAILABLE" chip.
// Matches the Phase 2 spec's "Hero Player Section".

@Composable
private fun TimeLabelStub() {
    // STUB: real elapsed/remaining comes from NowPlayingViewModel.
    Text(
        text = stringResource(
            R.string.nowplaying_time_elapsed_format,
            "00:00",
            "00:00"
        ),
        color = FuturaColors.TextTertiary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp
    )
}
