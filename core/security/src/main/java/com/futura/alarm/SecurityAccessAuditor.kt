package com.futura.alarm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory counter tracking failed PIN attempts.
 *
 * Applies exponential backoff: after `N` failures, the caller must wait
 * `BACKOFF_BASE_MS * 2^(N - 1)` before trying again. `N` never resets
 * except on a successful verify or on [reset] (which is called on app
 * restart, because the lockout state is not persisted — a device reboot
 * during lockout is a legitimate escape hatch and is the correct
 * security boundary: without persistence, an attacker with physical
 * access cannot be *permanently* locked out, and the lockout cost is
 * measured in seconds-to-minutes, not minutes-to-hours, which matches
 * the threat model of a decoy PIN that is intended to *deceive the user*,
 * not to stop a determined adversary).
 */
@Singleton
internal class SecurityAccessAuditor @Inject constructor() {
    private val tag = "SecurityAccessAuditor"
    private val mutex = Mutex()

    private val _failures = MutableStateFlow(0)
    val failures: StateFlow<Int> = _failures.asStateFlow()

    private val _lockedUntilMs = MutableStateFlow(0L)
    val lockedUntilMs: StateFlow<Long> = _lockedUntilMs.asStateFlow()

    /** True if the caller is currently locked out. */
    fun isLocked(): Boolean = System.currentTimeMillis() < _lockedUntilMs.value

    /** Remaining lockout duration in ms, or 0 if unlocked. */
    fun remainingLockoutMs(): Long =
        (_lockedUntilMs.value - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Record one failed verification. Returns the resulting lockout
     *  duration in ms (0 if still under the grace threshold). */
    suspend fun recordFailure(): Long = mutex.withLock {
        val newFailures = _failures.value + 1
        _failures.value = newFailures
        if (newFailures < MIN_FAILURES_BEFORE_LOCKOUT) {
            return@withLock 0L
        }
        val exponent = (newFailures - MIN_FAILURES_BEFORE_LOCKOUT)
            .coerceAtMost(MAX_BACKOFF_EXPONENT)
        val delayMs = BACKOFF_BASE_MS * (1L shl exponent)
        _lockedUntilMs.value = System.currentTimeMillis() + delayMs
        Log.w(tag, "Failure #$newFailures — locked for ${delayMs}ms")
        delayMs
    }

    /** Record a successful verification. Clears all state. */
    suspend fun recordSuccess() = mutex.withLock {
        if (_failures.value > 0) {
            Log.i(tag, "Success — clearing ${_failures.value} failures")
        }
        _failures.value = 0
        _lockedUntilMs.value = 0L
    }

    /** Reset on app restart — see class KDoc for the security rationale. */
    suspend fun reset() = mutex.withLock {
        _failures.value = 0
        _lockedUntilMs.value = 0L
    }

    private companion object {
        const val MIN_FAILURES_BEFORE_LOCKOUT = 3
        const val BACKOFF_BASE_MS = 2_000L
        const val MAX_BACKOFF_EXPONENT = 6  // caps at 2000 * 64 = 128s
    }
}
