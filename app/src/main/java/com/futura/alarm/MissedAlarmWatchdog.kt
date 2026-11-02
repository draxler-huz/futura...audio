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
 * Background watchdog that escalates an unanswered alarm and holds the
 * wake lease that keeps the CPU awake while it rings.
 *
 * Lifecycle:
 *   • [arm] (specId): called on `Firing`. Acquires the wake lease, whose
 *     ceiling is [WakeLockManager.MAX_ACQUIRE_MS] (10 minutes).
 *   • Every [STAGE_INTERVAL_MS] tick, the watchdog renews the lease, so it
 *     never lapses while the alarm is active. This covers every tier,
 *     including SIREN, which stops changing stage. A stage-change-only
 *     renewal would let the lease expire at SIREN.
 *   • Escalation starts once [MISSED_TIMEOUT_MS] has passed and advances one
 *     stage per tick, holding at SIREN.
 *   • Safety cap: once [MAX_SESSION_MS] has passed since [arm], the watchdog
 *     auto-dismisses the session, writes an audit log entry, and releases the
 *     lease. This bounds battery drain if the alarm is abandoned.
 *   • [disarm]: called on `Snoozed` or `Dismissed`. Cancels the loop and
 *     releases the lease.
 *
 * The per-stage intervention (notification re-post, TTS, volume, screen,
 * vibration) is deferred to a `MissedAlarmWatchdogExecutor` (not in this
 * group). The stage is a [StateFlow]. Because StateFlow does not re-deliver an
 * unchanged value, every tick is also emitted on [stageEvents].
 */
@Singleton
class MissedAlarmWatchdog @Inject constructor(
    private val coordinator: AlarmCoordinator,
    private val wakeLocks: WakeLockManager
) {
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

            // Lease starts at FIRING, with the 10-minute ceiling.
            wakeLocks.acquire(LEASE_TAG)
            val armedAt = System.currentTimeMillis()

            watchdogJob = scope.launch {
                try {
                    while (isActive && _activeSpecId.value == specId) {
                        delay(STAGE_INTERVAL_MS)
                        val elapsed = System.currentTimeMillis() - armedAt

                        // Safety cap: no session outlives MAX_SESSION_MS.
                        if (elapsed >= MAX_SESSION_MS) {
                            autoDismiss(specId, elapsed)
                            return@launch
                        }

                        // Escalate once the initial window has passed.
                        if (elapsed >= MISSED_TIMEOUT_MS) {
                            val next = _currentStage.value.next()
                            _currentStage.value = next
                            _stageEvents.tryEmit(next)
                            Log.w(tag, "Escalating $specId to $next")
                        }

                        // Heartbeat: renew on every tick, well inside the lease
                        // ceiling. Re-acquire if the lease was lost.
                        if (!wakeLocks.renewLease(LEASE_TAG)) {
                            Log.w(tag, "Lease for $specId lost; re-acquiring")
                            wakeLocks.acquire(LEASE_TAG)
                        }
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
            if (_activeSpecId.value != null) wakeLocks.release(LEASE_TAG)
            _activeSpecId.value = null
            _currentStage.value = EscalationStage.GENTLE
        }
    }

    /**
     * Ends the session when the safety cap is reached. Called from the loop,
     * so it does not take [mutex]. The lease is released here.
     */
    private suspend fun autoDismiss(specId: String, elapsedMs: Long) {
        // No persistent audit store exists yet, so the record goes to the log
        // under a dedicated tag.
        Log.w(
            AUDIT_TAG,
            "event=auto_dismiss spec=$specId elapsed_ms=$elapsedMs " +
                "cap_ms=$MAX_SESSION_MS reason=session_cap"
        )
        coordinator.onAlarmDismissed(specId)
        wakeLocks.release(LEASE_TAG)
        _activeSpecId.value = null
        _currentStage.value = EscalationStage.GENTLE
    }

    companion object {
        const val MISSED_TIMEOUT_MS: Long = 10L * 60L * 1000L   // escalation starts at 10 min
        const val STAGE_INTERVAL_MS: Long = 30L * 1000L         // tick: stage advance + lease renewal
        const val MAX_SESSION_MS: Long = 30L * 60L * 1000L      // hard cap: 3 x 10-minute windows
        private const val LEASE_TAG = "alarm_session"
        private const val AUDIT_TAG = "SecurityAudit"
    }
}
