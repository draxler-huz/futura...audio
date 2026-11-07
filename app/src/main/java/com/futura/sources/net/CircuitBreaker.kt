package com.futura.sources.net

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporarily disables a source after repeated failures.
 *
 * After [failureThreshold] consecutive failures inside [windowMs], the
 * source is "open" and skipped until [cooldownMs] has passed. A success, or
 * the end of the cooldown, closes it again.
 *
 * The clock is injectable so the behaviour can be unit-tested without
 * waiting on real time.
 */
@Singleton
class CircuitBreaker(
    private val failureThreshold: Int,
    private val windowMs: Long,
    private val cooldownMs: Long,
    private val clock: () -> Long
) {

    /**
     * Production constructor. The threshold values live here rather than as
     * Kotlin defaults because Dagger does not honour default arguments.
     */
    @Inject
    constructor() : this(
        failureThreshold = 3,
        windowMs = 10 * 60 * 1000L,
        cooldownMs = 5 * 60 * 1000L,
        clock = System::currentTimeMillis
    )

    private data class State(
        var failures: Int = 0,
        var firstFailureMs: Long = 0L,
        var openUntilMs: Long = 0L
    )

    private val states = ConcurrentHashMap<String, State>()

    /** True when [sourceId] may be called now. */
    fun isAvailable(sourceId: String): Boolean {
        val state = states[sourceId] ?: return true
        val now = clock()
        if (state.openUntilMs > now) return false
        // Cooldown elapsed: half-open, allow a trial call.
        if (state.openUntilMs != 0L) {
            state.openUntilMs = 0L
            state.failures = 0
            state.firstFailureMs = 0L
        }
        return true
    }

    fun recordSuccess(sourceId: String) {
        states.remove(sourceId)
    }

    fun recordFailure(sourceId: String) {
        val now = clock()
        val state = states.getOrPut(sourceId) { State(firstFailureMs = now) }
        if (now - state.firstFailureMs > windowMs) {
            state.failures = 0
            state.firstFailureMs = now
        }
        state.failures += 1
        if (state.failures >= failureThreshold) {
            state.openUntilMs = now + cooldownMs
        }
    }
}
