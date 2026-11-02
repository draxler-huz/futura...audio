// app/src/main/java/com/futura/alarm/MissedAlarmWatchdog.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Autonomous background watchdog executing progressive five-stage
 * escalation when an alarm remains unanswered.
 *
 * Lifecycle:
 *   • [arm] (specId) — called when `AlarmCoordinator` emits `Firing`.
 *     Starts a timer; after [MISSED_TIMEOUT_MS] without dismissal, the
 *     watchdog begins escalation.
 *   • [disarm] — called when the coordinator emits `Dismissed` or
 *     `Snoozed`. Cancels the timer and clears the escalation stage.
 *   • Escalation progresses one stage per [STAGE_INTERVAL_MS] until
 *     [EscalationStage.SIREN] is reached, then keeps emitting SIREN every
 *     interval until [disarm] is called.
 *
 * The per-stage intervention (re-post notification, TTS nudge, max volume,
 * wake screen, sustained vibration) is deferred to a
 * `MissedAlarmWatchdogExecutor` (not part of this group). The current stage
 * is a [StateFlow]; because StateFlow does not re-deliver an unchanged
 * value, every interval, including repeated SIREN, is also emitted on
 * [stageEvents].
 */
@Singleton
class MissedAlarmWatchdog @Inject constructor() {
    private val tag = "MissedAlarmWatchdog"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _currentStage = MutableStateFlow(EscalationStage.GENTLE)
    val currentStage: StateFlow<EscalationStage> = _currentStage.asStateFlow()

    private val _activeSpecId = MutableStateFlow<String?>(null)
    val activeSpecId: StateFlow<String?> = _activeSpecId.asStateFlow()

    private val _stageEvents = MutableSharedFlow<EscalationStage>(
        extraBufferCapacity = 8
    )
    val stageEvents: SharedFlow<EscalationStage> = _stageEvents.asSharedFlow()

    private var watchdogJob: Job? = null

    suspend fun arm(specId: String) {
        mutex.withLock {
            if (_activeSpecId.value == specId && watchdogJob?.isActive == true) {
                return // already armed for this spec
            }
            watchdogJob?.cancel()
            _activeSpecId.value = specId
            _currentStage.value = EscalationStage.GENTLE

            watchdogJob = scope.launch {
                try {
                    delay(MISSED_TIMEOUT_MS)
                    // Escalation loop: advance one stage per interval, then
                    // hold at SIREN and keep emitting it.
                    while (isActive && _activeSpecId.value == specId) {
                        val next = _currentStage.value.next()
                        _currentStage.value = next
                        _stageEvents.tryEmit(next)
                        Log.w(tag, "Escalating $specId to $next")
                        delay(STAGE_INTERVAL_MS)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Watchdog loop failed for $specId", e)
                }
            }
        }
    }

    suspend fun disarm() {
        mutex.withLock {
            watchdogJob?.cancel()
            watchdogJob = null
            _activeSpecId.value = null
            _currentStage.value = EscalationStage.GENTLE
        }
    }

    companion object {
        const val MISSED_TIMEOUT_MS: Long = 10L * 60L * 1000L  // 10 minutes
        const val STAGE_INTERVAL_MS: Long = 30L * 1000L        // 30 s per stage
    }
}
