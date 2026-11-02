package com.futura.alarm

import android.util.Log
import com.futura.engine.DspChainController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the audio side of an alarm session. The circadian ramp drives the
 * DSP pre-amp, and nothing else.
 *
 * - Firing starts the DSP chain, the ramp and the gain loop.
 * - Snoozed and Dismissed stop them. The gain loop sends 0f before it ends.
 *
 * The sink carries only the circadian gain. The Fletcher-Munson boost is
 * already part of that gain, so no band correction goes to the EQ.
 * Ducking stays in [VoiceDropStitcher], so no duck goes to the DSP.
 */
@Singleton
class AlarmAudioSession @Inject constructor(
    private val coordinator: AlarmCoordinator,
    private val ramp: CircadianRampModule,
    private val sync: CircadianAudioSync,
    private val dsp: DspChainController
) {
    private val tag = "AlarmAudioSession"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null

    /** Subscribe to the coordinator's events. Safe to call more than once. */
    fun attach() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is AlarmCoordinator.AlarmEvent.Firing -> {
                        coordinator.session.value?.spec?.let { start(it) }
                    }
                    is AlarmCoordinator.AlarmEvent.Snoozed,
                    is AlarmCoordinator.AlarmEvent.Dismissed -> stop()
                    else -> Unit
                }
            }
        }
    }

    /** Restart the audio after the service is restored with a FIRING session. */
    suspend fun resumeIfFiring() {
        val session = coordinator.session.value ?: return
        if (session.phase == AlarmCoordinator.Phase.FIRING) start(session.spec)
    }

    private suspend fun start(spec: AlarmSpec) {
        val now = System.currentTimeMillis()
        val plan = CircadianRampModule.RampPlan(
            startMs = now,
            endMs = now + spec.rampDurationSeconds.coerceAtLeast(1) * 1000L,
            aggression = spec.rampProfile
        )
        // The sink must be set before the loop starts, or its gain is dropped.
        sync.nativeGainSink = { gain -> dsp.setCircadianLinear(gain) }
        dsp.start()
        if (!dsp.enableChain()) {
            Log.w(tag, "DSP chain did not enable; the ramp runs without the DSP")
        }
        ramp.start(plan)
        sync.start()
        Log.i(tag, "Audio session started for ${spec.id}")
    }

    private suspend fun stop() {
        sync.stop()          // cancels the loop and sends 0f to the sink
        // The 0f is only written to the DSP on the next coalescer tick. Give
        // that tick time to run before the chain is disabled.
        delay(FLUSH_GRACE_MS)
        ramp.stop()
        dsp.stop()
        sync.nativeGainSink = null
        Log.i(tag, "Audio session stopped")
    }

    private companion object {
        /** Longer than the coalescer's 10 ms flush interval. */
        const val FLUSH_GRACE_MS = 20L
    }
}
