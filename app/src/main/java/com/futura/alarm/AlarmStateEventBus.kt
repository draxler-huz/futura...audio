// app/src/main/java/com/futura/alarm/AlarmStateEventBus.kt
package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * High-throughput app-wide alarm state event bus.
 *
 * Subscribes to [AlarmCoordinator.events] and re-broadcasts them
 * through [events], a hot `SharedFlow` with replay so late subscribers
 * (UI panels that attach after the alarm has started, DSP threads that
 * connect during a session) receive the current state.
 *
 * Consumers:
 *   • UI panels observing alarm state (AlarmActivity, NowPlayingScreen).
 *   • The future DSP bridge (`engine/DspChainController.kt`, group N) —
 *     will apply alarm-specific DSP duck/ducking curves by observing
 *     `FIRING` events.
 *   • Any Wear OS listener (`system/WearOsRemoteReceiver.kt`) that needs
 *     to mirror alarm state on a paired watch.
 *
 * Hot bus — no subscriber backpressure. Emitters never block on slow
 * consumers; if the buffer overflows, `tryEmit` drops (which is safe for
 * state broadcast — the replay buffer carries the latest N events).
 */
@Singleton
class AlarmStateEventBus @Inject constructor(
    private val coordinator: AlarmCoordinator
) {
    private val tag = "AlarmStateEventBus"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null

    private val _events = MutableSharedFlow<AlarmCoordinator.AlarmEvent>(
        replay = 16,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<AlarmCoordinator.AlarmEvent> = _events.asSharedFlow()

    /** Begin mirroring [AlarmCoordinator.events] into [events]. Idempotent. */
    fun start() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            try {
                coordinator.events.collect { event ->
                    _events.tryEmit(event)
                }
            } catch (e: Exception) {
                Log.e(tag, "Event pump failed", e)
            }
        }
    }

    fun stop() {
        pumpJob?.cancel()
        pumpJob = null
    }
}
