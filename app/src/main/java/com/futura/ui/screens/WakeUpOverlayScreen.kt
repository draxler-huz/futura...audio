// futura-audio/app/src/main/java/com/futura/ui/screens/WakeUpOverlayScreen.kt
//
// Phase 1 — Wake-Up Overlay screen. Fully implemented (per the project
// tree annotation "✅ Phase 1 — full impl").
//
// ── Visual identity ───────────────────────────────────────────────────────
// The screen the user actually wakes up to. Deliberately the most minimal
// surface in the entire app:
//
//   • OLED true black canvas (#000000) — the only way to make an alarm
//     screen feel like a system surface instead of an app launcher.
//   • No album art. No lyrics. No library tabs. Zero clutter.
//   • Emerald (#00FF66) primary accent, cyan (#00E5FF) for secondary
//     chrome, matching FuturaColors in theme/Theme.kt and the anchor
//     values in res/values/colors.xml.
//   • Bold monospace display type for the current time (FuturaTypography
//     `displayLarge`, 64sp) with a soft radial emerald glow behind it.
//   • Two interactive elements: the massive SNOOZE pill and the horizontal
//     swipe-to-dismiss track. Nothing else.
//
// ── Transition behaviour ──────────────────────────────────────────────────
// Two distinct exit animations:
//
//   • Snooze: the entire screen slides left (-700dp, beyond any phone
//     width) while fading to 0 alpha. Represents "advance to Now Playing".
//     The screen waits ~360ms for the animation to complete before
//     invoking `onSnooze()`, so the transition is visible whether or not
//     the NavHost applies its own enter/exit transitions.
//
//   • Dismiss: the screen fades to 0 alpha in place. Represents "lock the
//     deck, go away". Waits ~320ms, then invokes `onDismiss()`.
//
// Both callbacks are guarded against re-entrance: once either transition
// has begun, further taps (or a swipe during a snooze) are ignored.
//
// ── Alarm session persistence ────────────────────────────────────────────
// The spec requires that snoozing "keeps the alarm session persistent".
// That is a caller responsibility (AlarmCoordinator, later) — this screen
// simply invokes `onSnooze()` and trusts that the caller begins the
// snooze-backoff handshake without tearing down the alarm engine. The
// KDoc on `onSnooze` documents this contract explicitly.
//
// ── Live clock ───────────────────────────────────────────────────────────
// The current time updates every second, synced to wall-clock second
// boundaries (not to an arbitrary 1000 ms delay loop). The displayed
// format is `hh:mm a` (e.g. "06:30 AM"), matching the design mockup.
//
// ── Parameter contract ───────────────────────────────────────────────────
//     WakeUpOverlayScreen(
//         alarmTimeLabel = "07:00 AM",   // or null to show "ALARM OFF"
//         audioOutputLabel = "BT: CyberBuds Pro • Lossless Active", // or null
//         onSnooze = { … },
//         onDismiss = { … }
//     )
//
// Both label parameters default to `null`. That makes the transition from
// the current `MainActivity.kt` placeholder a **one-line swap**:
//
//     WakeUpOverlayPlaceholder(onSnooze = …, onDismiss = …)
//   → WakeUpOverlayScreen(onSnooze = …, onDismiss = …)
//
// When `AlarmViewModel` and the audio-route resolver are delivered, the
// caller passes the real `alarmTimeLabel` and `audioOutputLabel`; this
// screen requires no changes at that point.
//
// ── Forward-reference audit ──────────────────────────────────────────────
// Imported project symbols:
//   • com.futura.R                          (generated from res/values/strings.xml
//                                            + res/drawable/*.xml)
//   • com.futura.ui.theme.FuturaColors      (delivered in theme/Theme.kt)
// Everything else is a framework or library symbol declared in
// gradle/libs.versions.toml:
//   • androidx.compose.*                    (BOM 2024.10.00)
//   • androidx.compose.material.icons.*     (material-icons-extended)
//   • kotlinx.coroutines.*                  (kotlinx-coroutines-android)
//   • java.time.*                           (JDK, natively available on minSdk 26)
// No future project class is referenced. No stub is imported.

package com.futura.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.futura.R
import com.futura.ui.theme.FuturaColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.futura.ui.theme.AlarmGreen
import com.futura.ui.components.AnalogClockFace

// ═════════════════════════════════════════════════════════════════════════
// Entry point
// ═════════════════════════════════════════════════════════════════════════

/**
 * Full-screen Wake-Up Overlay.
 *
 * @param alarmTimeLabel Pre-formatted time of the currently armed alarm
 *        (e.g. `"07:00 AM"`). Pass `null` to render the "ALARM OFF" badge.
 *        The screen does not format this itself because the caller owns
 *        the source of truth (AlarmStore / AlarmCoordinator).
 * @param audioOutputLabel Pre-formatted description of the active audio
 *        route (e.g. `"BT: CyberBuds Pro • Lossless Active"`). Pass `null`
 *        to render "AUDIO OUT: NONE".
 * @param onSnooze Invoked ~360 ms after the snooze tap, giving the internal
 *        slide-out animation time to play. Callers MUST keep the alarm
 *        session persistent across this call (begin snooze backoff, do not
 *        stop FuturaAlarmService, do not cancel the notification).
 * @param onDismiss Invoked ~320 ms after a successful swipe-to-dismiss,
 *        giving the fade-out animation time to play. Callers should
 *        terminate the alarm session and lock the deck.
 * @param modifier Optional outer modifier.
 */
@Composable
fun WakeUpOverlayScreen(
    alarmTimeLabel: String? = null,
    audioOutputLabel: String? = null,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ── Live clock ───────────────────────────────────────────────────────
    val currentTime by rememberCurrentTime()
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
    }
    val timeText = remember(currentTime) { currentTime.format(timeFormatter) }

    // ── Exit transition state ────────────────────────────────────────────
    var snoozing by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val slideX by animateDpAsState(
        targetValue = if (snoozing) (-700).dp else 0.dp,
        animationSpec = tween(
            durationMillis = 340,
            easing = FastOutSlowInEasing
        ),
        label = "wake-snooze-slide"
    )

    val fadeAlpha by animateFloatAsState(
        targetValue = if (snoozing || dismissing) 0f else 1f,
        animationSpec = tween(durationMillis = 340),
        label = "wake-fade"
    )

    val triggerSnooze: () -> Unit = {
        if (!snoozing && !dismissing) {
            snoozing = true
            scope.launch {
                delay(360)
                onSnooze()
            }
        }
    }

    val triggerDismiss: () -> Unit = {
        if (!snoozing && !dismissing) {
            dismissing = true
            scope.launch {
                delay(320)
                onDismiss()
            }
        }
    }

    // ── Root layout ──────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack)
            .offset(x = slideX)
            .alpha(fadeAlpha)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            TopStatusBar(
                alarmTimeLabel = alarmTimeLabel,
                audioOutputLabel = audioOutputLabel
            )

            // The time surface is vertically centred between the top bar
            // and the action dock. Using weight(1f) on a wrapper Box lets
            // the time surface shrink gracefully on very small screens.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnalogClockFace(time = currentTime, modifier = Modifier.size(180.dp))
                    Spacer(Modifier.height(16.dp))
                    CoreTimeSurface(currentTimeText = timeText)
                }
            }

            ActionDock(
                onSnoozeClick = triggerSnooze,
                onSwipeDismissComplete = triggerDismiss
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Top status bar — alarm badge (left) + audio output indicator (right)
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun TopStatusBar(
    alarmTimeLabel: String?,
    audioOutputLabel: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlarmBadge(alarmTimeLabel = alarmTimeLabel)
        Spacer(Modifier.width(8.dp))
        AudioOutputIndicator(
            label = audioOutputLabel,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun AlarmBadge(alarmTimeLabel: String?) {
    val isArmed = alarmTimeLabel != null
    val accent = if (isArmed) AlarmGreen else FuturaColors.TextTertiary
    val iconRes = if (isArmed) R.drawable.ic_alarm else R.drawable.ic_alarm_off
    val label = if (isArmed) {
        stringResource(R.string.wake_alarm_armed_format, alarmTimeLabel!!)
    } else {
        stringResource(R.string.wake_alarm_off)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = stringResource(R.string.wake_cd_alarm_status),
            tint = accent,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            color = accent,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AudioOutputIndicator(
    label: String?,
    modifier: Modifier = Modifier
) {
    val text = label ?: stringResource(R.string.wake_output_unknown)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = AlarmGreen.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Speaker,
            contentDescription = stringResource(R.string.wake_cd_audio_output),
            tint = AlarmGreen,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            color = AlarmGreen,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Core time surface — massive monospace clock + pulsing subtitle
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun CoreTimeSurface(currentTimeText: String) {
    // Breathing pulse for the "ROULETTE QUEUE READY" subtitle. Reversing
    // repeat at ~1.6 s feels organic without drawing attention away from
    // the clock itself.
    val pulse by rememberInfiniteTransition(label = "wake-queue-pulse")
        .animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1600,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "wake-queue-pulse-value"
        )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Time + glow — a single Box so the radial gradient sits behind
        // the text without affecting text measurement.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .drawBehind {
                    // Soft emerald halo. Larger than the glyph bounds so
                    // the glow reads as ambient, not as a background
                    // block, even on narrow phones.
                    val glowRadius = size.maxDimension * 0.6f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AlarmGreen.copy(alpha = 0.24f),
                                AlarmGreen.copy(alpha = 0.06f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = glowRadius
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = currentTimeText,
                style = MaterialTheme.typography.displayLarge,
                color = AlarmGreen,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(12.dp))

        // Pulsing subtitle — ALL CAPS monospace to match the console voice
        // used on the launcher shortcut labels and the alarm badge.
        Text(
            text = stringResource(R.string.wake_status_roulette_ready),
            color = AlarmGreen.copy(alpha = pulse),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Action dock — snooze button (primary) + swipe-to-dismiss track (secondary)
// ═════════════════════════════════════════════════════════════════════════

@Composable
private fun ActionDock(
    onSnoozeClick: () -> Unit,
    onSwipeDismissComplete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SnoozeButton(onClick = onSnoozeClick)
        SwipeDismissTrack(onDismiss = onSwipeDismissComplete)
    }
}

@Composable
private fun SnoozeButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AlarmGreen,
            contentColor = FuturaColors.OLEDBlack
        )
    ) {
        Text(
            text = stringResource(R.string.wake_action_snooze),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp,
            maxLines = 1
        )
    }
}

/**
 * Custom horizontal swipe-to-dismiss track.
 *
 * A cyan-bordered pill with a circular emerald-free handle on the left.
 * The user drags the handle to the right; when the offset reaches 85% of
 * the available track length, the haptic fires and `onDismiss` is invoked.
 * Releasing before the threshold snaps the handle back to 0 via a spring
 * animation.
 *
 * Implementation note — animation vs. drag responsiveness:
 *   `animateFloatAsState` normally introduces lag between the target value
 *   and the rendered value because it smooths every change. During an
 *   active drag that lag would make the handle trail the finger, which is
 *   unacceptable. `snap()` is used as the animation spec while
 *   `isDragging == true`, disabling smoothing so the handle tracks the
 *   finger one-to-one; only after release does the spring animation take
 *   over to snap the handle back home on an aborted swipe.
 */
@Composable
private fun SwipeDismissTrack(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val handleSizeDp = 56.dp
    val innerPaddingDp = 4.dp
    val trackHeightDp = 64.dp

    val handleSizePx = with(density) { handleSizeDp.toPx() }
    val innerPaddingPx = with(density) { innerPaddingDp.toPx() }

    var trackWidthPx by remember { mutableIntStateOf(0) }
    var rawOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val maxOffsetPx = (trackWidthPx - handleSizePx - innerPaddingPx * 2f)
        .coerceAtLeast(0f)
    val thresholdPx = maxOffsetPx * 0.85f

    val animatedOffsetPx by animateFloatAsState(
        targetValue = rawOffsetPx,
        animationSpec = if (isDragging) snap() else spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy
        ),
        label = "wake-swipe-offset"
    )

    val visualProgress = if (maxOffsetPx > 0f) {
        (animatedOffsetPx / maxOffsetPx).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeightDp)
            .clip(RoundedCornerShape(trackHeightDp / 2))
            .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = AlarmGreen.copy(alpha = 0.4f),
                shape = RoundedCornerShape(trackHeightDp / 2)
            )
            .onSizeChanged { trackWidthPx = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (rawOffsetPx >= thresholdPx && thresholdPx > 0f) {
                            haptic.performHapticFeedback(
                                HapticFeedbackType.LongPress
                            )
                            onDismiss()
                        } else {
                            rawOffsetPx = 0f
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        rawOffsetPx = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    rawOffsetPx = (rawOffsetPx + dragAmount)
                        .coerceIn(0f, maxOffsetPx)
                }
            }
    ) {
        // Centered label — fades out as the handle approaches the threshold.
        Text(
            text = stringResource(R.string.wake_action_dismiss),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            color = AlarmGreen.copy(
                alpha = (0.85f - visualProgress * 0.85f)
                    .coerceIn(0f, 0.85f)
            ),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Draggable handle — sits inside the pill with `innerPaddingDp`
        // of inset on the leading edge so it doesn't touch the border.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (innerPaddingPx + animatedOffsetPx).roundToInt(),
                        y = 0
                    )
                }
                .padding(vertical = innerPaddingDp)
                .size(handleSizeDp)
                .clip(CircleShape)
                .background(AlarmGreen)
                .border(
                    width = 2.dp,
                    color = AlarmGreen.copy(alpha = 0.6f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, // decorative — the track label carries meaning
                tint = FuturaColors.OLEDBlack,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Live clock state helper
// ═════════════════════════════════════════════════════════════════════════

/**
 * Returns a `State<LocalTime>` that re-emits `LocalTime.now()` on every
 * wall-clock second boundary. Syncing to second boundaries (rather than
 * to an arbitrary 1000 ms delay loop) ensures the displayed minute flips
 * exactly when the real minute flips, with no drift across long-running
 * sessions.
 */
@Composable
private fun rememberCurrentTime(): State<LocalTime> {
    val state = remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.value = LocalTime.now()
            val millis = System.currentTimeMillis()
            // Sleep until the next full second, then take a fresh reading.
            delay(1000L - (millis % 1000L))
        }
    }
    return state
}