package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Starts and stops native audio and missed-alarm actions from the coordinator's session state.
 * The existing audio and escalation implementations remain responsible for their own work.
 */
@Singleton
class AlarmSessionOrchestrator @Inject constructor(
    private val coordinator: AlarmCoordinator,
    private val audioSession: AlarmAudioSession,
    private val executor: MissedAlarmExecutor
) {
    private val tag = "AlarmSessionOrchestrator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var observer: Job? = null

    /** Attach once; StateFlow also delivers a session restored after process restart. */
    @Synchronized
    fun attach() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            try {
                coordinator.session
                    .distinctUntilChanged { old, new ->
                        old?.specId == new?.specId && old?.phase == new?.phase
                    }
                    .collect { session ->
                        try {
                            if (session?.phase == AlarmCoordinator.Phase.FIRING) {
                                audioSession.start(session.spec)
                                executor.start(session.spec)
                            } else {
                                executor.stop()
                                audioSession.stop()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(tag, "Session transition failed", e)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Session observer failed", e)
            }
        }
    }
}
