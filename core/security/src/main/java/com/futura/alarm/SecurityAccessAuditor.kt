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
 * Rule: after every `maxFailures` consecutive failures, PIN entry is locked
 * for `lockoutMs`. Both values come from the user's [LockoutPolicy], which is
 * read from [LockoutPolicyStore] before each PIN attempt via [refreshPolicy].
 *
 * Failure state is not persisted. A reboot clears the lockout. That is a
 * deliberate boundary: the lockout is measured in seconds to minutes, and
 * persisting it would turn a reboot into a permanent lockout for the user.
 */
@Singleton
internal class SecurityAccessAuditor @Inject constructor(
    private val policyStore: LockoutPolicyStore
) {
    private val tag = "SecurityAccessAuditor"
    private val mutex = Mutex()

    @Volatile private var policy: LockoutPolicy = LockoutPolicy.DEFAULT

    private val _failures = MutableStateFlow(0)
    val failures: StateFlow<Int> = _failures.asStateFlow()

    private val _lockedUntilMs = MutableStateFlow(0L)
    val lockedUntilMs: StateFlow<Long> = _lockedUntilMs.asStateFlow()

    /** Reload the user's lockout policy. Call before evaluating an attempt. */
    suspend fun refreshPolicy() {
        policy = policyStore.load()
    }

    /** True if the caller is currently locked out. */
    fun isLocked(): Boolean = System.currentTimeMillis() < _lockedUntilMs.value

    /** Remaining lockout duration in ms, or 0 if unlocked. */
    fun remainingLockoutMs(): Long =
        (_lockedUntilMs.value - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Record one failed verification. Returns the lockout duration in ms
     *  that now applies, or 0 if the user is still under the limit. */
    suspend fun recordFailure(): Long = mutex.withLock {
        _failures.value += 1
        val current = policy
        if (_failures.value % current.maxFailures != 0) {
            return@withLock 0L
        }
        _lockedUntilMs.value = System.currentTimeMillis() + current.lockoutMs
        Log.w(tag, "Failure #${_failures.value} — locked for ${current.lockoutMs}ms")
        current.lockoutMs
    }

    /** Record a successful verification. Clears all state. */
    suspend fun recordSuccess() = mutex.withLock {
        if (_failures.value > 0) {
            Log.i(tag, "Success — clearing ${_failures.value} failures")
        }
        _failures.value = 0
        _lockedUntilMs.value = 0L
    }

    /** Failed attempts left before the next lockout. */
    fun attemptsLeft(): Int {
        val max = policy.maxFailures
        return max - (_failures.value % max)
    }

    /** Reset on app restart — see class KDoc for the security rationale. */
    suspend fun reset() = mutex.withLock {
        _failures.value = 0
        _lockedUntilMs.value = 0L
    }
}
