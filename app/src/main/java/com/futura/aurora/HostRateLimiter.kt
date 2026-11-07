package com.futura.aurora

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spaces out requests to each host so one search cannot flood a provider. */
@Singleton
class HostRateLimiter @Inject constructor() {

    private val mutex = Mutex()
    private val lastCallMs = mutableMapOf<String, Long>()

    /** Waits until at least [minIntervalMs] has passed since the last request to [host]. */
    suspend fun await(host: String, minIntervalMs: Long = 1_000L) {
        mutex.withLock {
            val wait = minIntervalMs - (System.currentTimeMillis() - (lastCallMs[host] ?: 0L))
            if (wait > 0) delay(wait)
            lastCallMs[host] = System.currentTimeMillis()
        }
    }
}
