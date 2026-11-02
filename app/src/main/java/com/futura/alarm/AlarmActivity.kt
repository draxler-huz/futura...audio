// app/src/main/java/com/futura/alarm/AlarmActivity.kt
package com.futura.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen alarm landing page.
 *
 * Launched by [DynamicNotificationLauncher]'s full-screen intent (with
 * the `futura://alarm/<specId>` deep link) or by [MainActivity] when the
 * user taps an alarm notification. Extends [FragmentActivity] because
 * [BiometricWakeUpLock] (group 5) uses `androidx.biometric:1.2.0-alpha05`
 * which requires a `FragmentActivity` on API 26–29 (on API 30+ a plain
 * `ComponentActivity` would suffice; `FragmentActivity` extends it, so
 * this single superclass works across the whole minSdk 26+ range).
 *
 * Responsibilities:
 *   • Window configuration (show when locked, turn screen on, keep
 *     screen on, immersive navigation).
 *   • Volume button swallowing while stealth mode is engaged.
 *   • Swipe-to-dismiss gate (see [DismissSurface] below).
 *   • Optional biometric confirmation before dismissing — sequenced
 *     after the swipe threshold is crossed, per Option A design.
 *   • Automatic `finish()` when the alarm is dismissed by any other
 *     path (notification action, Wear remote, watchdog timeout).
 *
 * The alarm UI is deliberately minimal — this screen is what the user
 * sees in the first half-second of waking up; the Wake-Up Overlay
 * (delivered) is the *subsequent* screen shown after a snooze action.
 * The overlay owns the "SNOOZE (START ROULETTE QUEUE)" call; this
 * activity owns the "prove you're awake and dismiss" call.
 */
@AndroidEntryPoint
class AlarmActivity : FragmentActivity() {

    @Inject lateinit var coordinator: AlarmCoordinator
    @Inject lateinit var stealthModePolicy: StealthModePolicy
    @Inject lateinit var biometricLock: BiometricWakeUpLock

    private var activeSpecId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        handleIntent(intent)

        if (activeSpecId == null) {
            Log.w(TAG, "No spec id in intent; finishing")
            finish()
            return
        }

        // Engage stealth mode for the lifetime of this activity.
        lifecycleScope.launch {
            try { stealthModePolicy.engage() } catch (e: Exception) {
                Log.w(TAG, "Failed to engage stealth mode", e)
            }
        }

        // Auto-finish when the alarm is dismissed through any other path
        // (notification action, Wear, watchdog). This prevents the
        // biometric prompt from floating over a dismissed alarm.
        lifecycleScope.launch {
            try {
                // Finish only after a session was seen and then ended. A null
                // session on launch is a startup race, not a dismissal.
                var sawSession = false
                coordinator.session.collect { session ->
                    if (session != null) sawSession = true
                    val ended = (sawSession && session == null) ||
                        session?.phase == AlarmCoordinator.Phase.DISMISSED
                    if (ended && !isFinishing) finish()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Session collector failed", e)
            }
        }

        setContent {
            FuturaTheme {
                AlarmScreen(specId = activeSpecId!!)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Swallow volume keys while stealth mode is engaged. The
        // StealthModePolicy delivers the policy decision; this method
        // owns the interception mechanics.
        if (event.action == KeyEvent.ACTION_DOWN &&
            stealthModePolicy.shouldSwallow(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        // lifecycleScope is cancelled after onDestroy returns, so a disengage
        // launched on it could be dropped. Use an independent scope.
        CoroutineScope(Dispatchers.Default).launch {
            try { stealthModePolicy.disengage() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun configureWindow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (e: Exception) {
            Log.e(TAG, "configureWindow failed", e)
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val specId = intent.data?.lastPathSegment
            ?: intent.getStringExtra(EXTRA_SPEC_ID)
        if (!specId.isNullOrBlank()) {
            activeSpecId = specId
        }
    }

    // ── Back button ────────────────────────────────────────────────

    // Registered in onCreate after super; intercepted so back does not
    // dismiss the alarm. The callback returns true (consumes) while the
    // alarm is active — the user must dismiss through the swipe gate.

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "Back press ignored while alarm is active")
                // Deliberate no-op.
            }
        })
    }

    // ── Compose UI ─────────────────────────────────────────────────

    @Composable
    private fun AlarmScreen(specId: String) {
        val session by coordinator.session.collectAsState()
        val spec = session?.spec
        val phase = session?.phase

        // Any terminal phase → no interactive UI (the activity is
        // about to finish via the session collector).
        if (phase == null || phase == AlarmCoordinator.Phase.DISMISSED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FuturaColors.OLEDBlack)
            )
            return
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FuturaColors.OLEDBlack)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(48.dp))
                AlarmHeadline(spec = spec)
                Spacer(Modifier.height(32.dp))
                DismissSurface(
                    specId = specId,
                    biometricLocked = spec?.biometricLocked == true,
                    onDismissCompleted = {
                        lifecycleScope.launch {
                            try {
                                coordinator.onAlarmDismissed(specId)
                            } catch (e: Exception) {
                                Log.e(TAG, "coordinator.onAlarmDismissed failed", e)
                            }
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun AlarmHeadline(spec: AlarmSpec?) {
        val label = spec?.label?.ifBlank { null } ?: "ALARM"
        Text(
            text = label.uppercase(),
            color = FuturaColors.Emerald,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "%02d:%02d".format(spec?.hour ?: 0, spec?.minute ?: 0),
            color = FuturaColors.White,
            fontSize = 64.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-2).sp,
            textAlign = TextAlign.Center
        )
    }

    /**
     * The swipe-to-dismiss surface.
     *
     * Gesture: continuous horizontal drag. The visual handle follows the
     * finger; releasing before the threshold spring-backs to zero;
     * releasing after the threshold either dismisses directly (if the
     * alarm is not biometric-locked) or opens the biometric prompt (if
     * it is). Biometric success dismisses; biometric failure or cancel
     * spring-backs to zero — the alarm continues.
     *
     * Threshold: 72% of the available track width. Higher than the 85%
     * used by the Wake-Up Overlay's "DISMISS ALARM & LOCK DECK" track
     * because this is the primary dismissal gesture on the alarm screen
     * itself — it must be *slightly harder* to prevent sleep-swipes, not
     * easier.
     */
    @Composable
    private fun DismissSurface(
        specId: String,
        biometricLocked: Boolean,
        onDismissCompleted: () -> Unit
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()

        var trackWidthPx by remember { mutableStateOf(0f) }
        // Plain state, written synchronously from the drag callback. A
        // coroutine-driven offset can lag behind the finger, which makes the
        // release decision on a stale value.
        var offsetPx by remember { mutableFloatStateOf(0f) }

        val handleSizeDp = 72.dp
        val handleSizePx = with(density) { handleSizeDp.toPx() }
        val trackHeightDp = 84.dp
        val innerPaddingPx = with(density) { 6.dp.toPx() }
        val maxOffsetPx = (trackWidthPx - handleSizePx - innerPaddingPx * 2f)
            .coerceAtLeast(0f)
        val thresholdPx = maxOffsetPx * DISMISS_THRESHOLD_FRACTION

        fun resetDrag() {
            scope.launch {
                Animatable(offsetPx).animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                ) { offsetPx = value }
            }
        }

        fun completeDismiss() {
            if (biometricLocked) {
                biometricLock.prompt(
                    activity = this@AlarmActivity,
                    onSuccess = { onDismissCompleted() },
                    onFailure = { resetDrag() }
                )
            } else {
                onDismissCompleted()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp)
                .clip(RoundedCornerShape(trackHeightDp / 2))
                .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
                .dismissTrackBorder(trackHeightDp)
                // Keyed so the gesture closure is rebuilt when the track size
                // or lock mode changes. Without the keys it kept the 0px values
                // from the first frame, so the threshold was never reached.
                .pointerInput(trackWidthPx, biometricLocked) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (thresholdPx > 0f && offsetPx >= thresholdPx) {
                                completeDismiss()
                            } else {
                                resetDrag()
                            }
                        },
                        onDragCancel = { resetDrag() }
                    ) { change, dragAmount ->
                        change.consume()
                        offsetPx = (offsetPx + dragAmount).coerceIn(0f, maxOffsetPx)
                    }
                }
                .onSizeChangedCompat { trackWidthPx = it }
        ) {
            // Centred label, which fades as the handle advances.
            val progress = if (maxOffsetPx > 0f) {
                (offsetPx / maxOffsetPx).coerceIn(0f, 1f)
            } else 0f
            Text(
                text = "SWIPE TO DISMISS",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                color = FuturaColors.Cyan.copy(
                    alpha = (0.85f - progress * 0.85f).coerceIn(0f, 0.85f)
                ),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                maxLines = 1
            )

            // Draggable handle.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
                    .offsetCompat(xPx = offsetPx)
                    .size(handleSizeDp)
                    .clip(RoundedCornerShape(handleSizeDp / 2))
                    .background(FuturaColors.Cyan)
                    .dismissTrackBorder(handleSizeDp)
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun Modifier.dismissTrackBorder(height: androidx.compose.ui.unit.Dp): Modifier =
        this.then(
            androidx.compose.foundation.border(
                width = 1.dp,
                color = FuturaColors.Cyan.copy(alpha = 0.4f),
                shape = RoundedCornerShape(height / 2)
            )
        )

    private fun Modifier.offsetCompat(xPx: Float): Modifier =
        this.then(
            androidx.compose.foundation.layout.offset {
                androidx.compose.ui.unit.IntOffset(
                    x = xPx.toInt(),
                    y = 0
                )
            }
        )

    private fun Modifier.onSizeChangedCompat(block: (Float) -> Unit): Modifier =
        this.then(
            androidx.compose.ui.layout.onSizeChanged { block(it.width.toFloat()) }
        )

    companion object {
        private const val TAG = "AlarmActivity"
        private const val EXTRA_SPEC_ID = "com.futura.alarm.EXTRA_SPEC_ID"
        private const val DISMISS_THRESHOLD_FRACTION = 0.72f

        /** Construct an intent to launch this activity for [specId]. */
        fun intent(context: Context, specId: String): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                setClassName(context, AlarmActivity::class.java.name)
                data = android.net.Uri.parse("futura://alarm/$specId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                         Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}