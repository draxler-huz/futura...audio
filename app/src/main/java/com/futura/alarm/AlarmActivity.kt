// app/src/main/java/com/futura/alarm/AlarmActivity.kt
package com.futura.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.futura.ui.theme.FuturaColors
import com.futura.ui.theme.FuturaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AlarmActivity : FragmentActivity() {

    @Inject lateinit var coordinator: AlarmCoordinator
    @Inject lateinit var stealthModePolicy: StealthModePolicy
    @Inject lateinit var biometricLock: BiometricWakeUpLock

    private var activeSpecId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmWindowHelper.applyWindowFlags(window)
        handleIntent(intent)

        if (activeSpecId == null) {
            Log.w(TAG, "No spec id in intent; finishing")
            finish()
            return
        }

        // Prompt for SYSTEM_ALERT_WINDOW if missing. Required for the
        // alarm to appear over another app when the screen is unlocked.
        if (!OverlayPermissionHelper.canDrawOverlays(this) && !isFinishing) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Alarm won't appear over other apps")
                .setMessage(
                    "To make the alarm appear over whatever is on your " +
                        "screen, grant the \"Display over other apps\" " +
                        "permission. Without it, the alarm appears on the " +
                        "lock screen or as a notification."
                )
                .setPositiveButton("Grant") { _, _ ->
                    OverlayPermissionHelper.requestOverlayPermission(this)
                }
                .setNegativeButton("Not now", null)
                .show()
        }

        lifecycleScope.launch {
            try { stealthModePolicy.engage() } catch (e: Exception) {
                Log.w(TAG, "Failed to engage stealth mode", e)
            }
        }

        lifecycleScope.launch {
            try {
                coordinator.session.collect { session ->
                    if (session == null ||
                        session.phase == AlarmCoordinator.Phase.DISMISSED) {
                        if (!isFinishing) finish()
                    }
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
        if (event.action == KeyEvent.ACTION_DOWN &&
            stealthModePolicy.shouldSwallow(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        val scope = lifecycleScope
        scope.launch {
            try { stealthModePolicy.disengage() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val specId = intent.data?.lastPathSegment
            ?: intent.getStringExtra(EXTRA_SPEC_ID)
        if (!specId.isNullOrBlank()) {
            activeSpecId = specId
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "Back press ignored while alarm is active")
            }
        })
    }

    // ── Compose ────────────────────────────────────────────────────

    @Composable
    private fun AlarmScreen(specId: String) {
        val session by coordinator.session.collectAsState()
        val spec = session?.spec
        val phase = session?.phase

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
                Spacer(Modifier.height(40.dp))
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

    @Composable
    private fun DismissSurface(
        specId: String,
        biometricLocked: Boolean,
        onDismissCompleted: () -> Unit
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()

        var trackWidthPx by remember { mutableStateOf(0f) }
        var confirmState by remember { mutableStateOf(false) }
        val dragOffset = remember { Animatable(0f) }

        val handleSizeDp = 72.dp
        val handleSizePx = with(density) { handleSizeDp.toPx() }
        val trackHeightDp = 84.dp
        val innerPaddingPx = with(density) { 6.dp.toPx() }
        val maxOffsetPx = (trackWidthPx - handleSizePx - innerPaddingPx * 2f)
            .coerceAtLeast(0f)
        val thresholdPx = maxOffsetPx * DISMISS_THRESHOLD_FRACTION

        fun animateTo(target: Float) {
            scope.launch {
                dragOffset.animateTo(
                    targetValue = target,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                )
            }
        }

        fun snapTo(target: Float) {
            scope.launch { dragOffset.snapTo(target) }
        }

        fun enterConfirm() {
            confirmState = true
            snapTo(maxOffsetPx)
        }

        fun cancelConfirm() {
            confirmState = false
            animateTo(0f)
        }

       fun doDismiss() {
    if (biometricLocked) {
        // BiometricWakeUpLock.prompt is suspend and delivers a
        // VaultUnlockProof to onSuccess. We launch in the composable's
        // coroutine scope and ignore the proof — for alarm dismissal
        // the fact that authentication succeeded is the only signal
        // that matters; VaultManager.unlock() has its own flow when
        // vault decryption is needed.
        scope.launch {
            biometricLock.prompt(
                activity = this@AlarmActivity,
                onSuccess = { _ -> onDismissCompleted() },
                onFailure = { cancelConfirm() }
            )
        }
    } else {
        onDismissCompleted()
    }
}

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = confirmState,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(180))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { doDismiss() },
                        modifier = Modifier
                            .widthIn(min = 200.dp)
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FuturaColors.Emerald,
                            contentColor = FuturaColors.OLEDBlack
                        )
                    ) {
                        Text(
                            text = if (biometricLocked) "AUTHENTICATE TO DISMISS"
                                   else "DISMISS",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { cancelConfirm() }) {
                        Text(
                            text = "Cancel",
                            color = FuturaColors.TextTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeightDp)
                    .clip(RoundedCornerShape(trackHeightDp / 2))
                    .background(FuturaColors.SurfaceBase.copy(alpha = 0.55f))
                    .border(
                        width = 1.dp,
                        color = FuturaColors.Cyan.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(trackHeightDp / 2)
                    )
                    .onSizeChanged { trackWidthPx = it.width.toFloat() }
                    .then(
                        if (!confirmState) {
                            Modifier.pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (thresholdPx > 0f &&
                                            dragOffset.value >= thresholdPx) {
                                            enterConfirm()
                                        } else {
                                            animateTo(0f)
                                        }
                                    },
                                    onDragCancel = { animateTo(0f) }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val newOffset =
                                        (dragOffset.value + dragAmount)
                                            .coerceIn(0f, maxOffsetPx)
                                    snapTo(newOffset)
                                }
                            }
                        } else Modifier
                    )
            ) {
                val progress =
                    if (maxOffsetPx > 0f)
                        (dragOffset.value / maxOffsetPx).coerceIn(0f, 1f)
                    else 0f
                Text(
                    text = if (confirmState) "READY" else "SWIPE TO DISMISS",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 100.dp),
                    color = if (confirmState)
                        FuturaColors.Cyan.copy(alpha = 0.5f)
                    else
                        FuturaColors.Cyan.copy(
                            alpha = (0.85f - progress * 0.85f).coerceIn(0f, 0.85f)
                        ),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
                        .offset {
                            IntOffset(x = dragOffset.value.roundToInt(), y = 0)
                        }
                        .size(handleSizeDp)
                        .clip(CircleShape)
                        .background(
                            if (confirmState) FuturaColors.Emerald
                            else FuturaColors.Cyan
                        )
                        .border(
                            width = 2.dp,
                            color = if (confirmState)
                                FuturaColors.Emerald.copy(alpha = 0.6f)
                            else
                                FuturaColors.Cyan.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                )
            }
        }
    }

    companion object {
        private const val TAG = "AlarmActivity"
        private const val EXTRA_SPEC_ID = "com.futura.alarm.EXTRA_SPEC_ID"
        private const val DISMISS_THRESHOLD_FRACTION = 0.72f

        fun intent(context: Context, specId: String): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                setClassName(context, AlarmActivity::class.java.name)
                data = android.net.Uri.parse("futura://alarm/$specId")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
    }
}
