// app/src/main/java/com/futura/alarm/AlarmCoordinator.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe singleton managing alarm session state.
 *
 * The current session — one active alarm at a time — is exposed as
 * [session] (`StateFlow<AlarmSession?>`). Discrete transitions are
 * broadcast on [events] (`SharedFlow<AlarmEvent>`).
 *
 * Scheduling side effects go through [AlarmSchedulerImpl]:
 *   • fire     → fallback cancelled; recurring alarms armed for next occurrence
 *   • snooze   → one-shot armed at the snooze time
 *   • dismiss  → same as fire: recurring re-armed, one-shot cancelled
 *
 * **Forward-reference seam**: `AlarmStateEventBus.kt` (group 2) will
 * subscribe to [events] and re-broadcast through the app-wide bus.
 */
@Singleton
class AlarmCoordinator @Inject constructor(
    private val store: AlarmStore,
    private val scheduler: AlarmSchedulerImpl
) {
    data class AlarmSession(
        val specId: String,
        val spec: AlarmSpec,
        val phase: Phase,
        val snoozeCount: Int,
        val fireTimeMs: Long
    )

    enum class Phase { FIRING, SNOOZED, DISMISSED }

    sealed interface AlarmEvent {
        data class Armed(val specId: String, val atMs: Long) : AlarmEvent
        data class Firing(val specId: String) : AlarmEvent
        data class Snoozed(val specId: String, val nextFireMs: Long) : AlarmEvent
        data class Dismissed(val specId: String) : AlarmEvent
        data class Missed(val specId: String) : AlarmEvent
    }

    private val tag = "AlarmCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionMutex = Mutex()

    private val _session = MutableStateFlow<AlarmSession?>(null)
    val session: StateFlow<AlarmSession?> = _session.asStateFlow()

    private val _events = MutableSharedFlow<AlarmEvent>(
        replay = 8,
        extraBufferCapacity = 32
    )
    val events: SharedFlow<AlarmEvent> = _events.asSharedFlow()

    suspend fun onAlarmFired(specId: String) {
        sessionMutex.withLock {
            val spec = store.get(specId)
            if (spec == null) {
                Log.w(tag, "onAlarmFired: unknown spec $specId")
                return
            }
            val current = _session.value?.takeIf {
                it.specId == specId && it.phase != Phase.DISMISSED
            }
            // A second fire while already firing is a duplicate; ignore it.
            if (current?.phase == Phase.FIRING) return

            _session.value = AlarmSession(
                specId = specId,
                spec = spec,
                phase = Phase.FIRING,
                // A snoozed alarm coming back keeps its count, so the cap holds.
                snoozeCount = current?.snoozeCount ?: 0,
                fireTimeMs = System.currentTimeMillis()
            )
            endOccurrence(specId)
            _events.tryEmit(AlarmEvent.Firing(specId))
        }
    }

    suspend fun onAlarmSnoozed(specId: String) {
        sessionMutex.withLock {
            val current = _session.value ?: return
            if (current.specId != specId || current.phase != Phase.FIRING) return
            if (current.snoozeCount >= current.spec.maxSnoozeCycles) {
                // Cycle cap reached: force-dismiss.
                endOccurrence(specId)
                _session.value = current.copy(phase = Phase.DISMISSED)
                _events.tryEmit(AlarmEvent.Dismissed(specId))
                return
            }
            // Exponential backoff: base × 2^snoozeCount minutes.
            val delayMs = current.spec.snoozeBaseMinutes.toLong() *
                60_000L *
                (1L shl current.snoozeCount)
            val nextFire = System.currentTimeMillis() + delayMs
            scheduler.scheduleAt(specId, nextFire)
            _session.value = current.copy(
                phase = Phase.SNOOZED,
                snoozeCount = current.snoozeCount + 1,
                fireTimeMs = nextFire
            )
            _events.tryEmit(AlarmEvent.Snoozed(specId, nextFire))
        }
    }

    suspend fun onAlarmDismissed(specId: String) {
        sessionMutex.withLock {
            val current = _session.value ?: return
            if (current.specId != specId) return
            endOccurrence(specId)
            _session.value = current.copy(phase = Phase.DISMISSED)
            _events.tryEmit(AlarmEvent.Dismissed(specId))
        }
        // Clear session on the next tick so downstream collectors see
        // the DISMISSED phase before it disappears.
        scope.launch {
            kotlinx.coroutines.delay(500)
            sessionMutex.withLock {
                if (_session.value?.specId == specId &&
                    _session.value?.phase == Phase.DISMISSED) {
                    _session.value = null
                }
            }
        }
    }

    suspend fun onAlarmMissed(specId: String) {
        _events.tryEmit(AlarmEvent.Missed(specId))
        // The MissedAlarmWatchdog (group 3) observes this via the shared
        // flow and executes the escalation chain; the coordinator does
        // not escalate itself.
    }

    /**
     * Ends the current occurrence of [specId]: recurring alarms are armed
     * for their next occurrence (which also replaces any pending snooze),
     * one-shot alarms have all their pending intents cancelled.
     */
    private fun endOccurrence(specId: String) {
        val spec = store.get(specId)
        if (spec != null && spec.recurrenceMask != 0) {
            scheduler.schedule(spec)
        } else {
            scheduler.cancel(specId)
        }
    }
}
