package com.futura.sources.net

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A token-bucket rate limiter keyed by host. Each host is configured from
 * its [com.futura.sources.SourceConfig.rateLimitPerMinute]; a host with no
 * configured limit uses [defaultPerMinute].
 *
 * [acquire] suspends until a token is available, so callers never busy-wait
 * and never need to know the arithmetic.
 */
@Singleton
class HostRateLimiter(
    private val defaultPerMinute: Int,
    private val clock: () -> Long
) {

    /** Production constructor; see [CircuitBreaker] for why this is not a default. */
    @Inject
    constructor() : this(
        defaultPerMinute = 30,
        clock = System::currentTimeMillis
    )

    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val mutex = Mutex()
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val perMinute = ConcurrentHashMap<String, Int>()

    /** Sets the per-minute allowance for [host]. Call once, at start-up. */
    fun configure(host: String, perMinute: Int) {
        this.perMinute[host] = perMinute.coerceAtLeast(1)
    }

    /** Suspends until one request to [host] is allowed. */
    suspend fun acquire(host: String) {
        while (true) {
            val waitMs = mutex.withLock { tryConsume(host) }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    /** Returns 0 when a token was taken, otherwise how long to wait. */
    private fun tryConsume(host: String): Long {
        val rate = perMinute[host] ?: defaultPerMinute
        val capacity = rate.toDouble()
        val refillPerMs = rate / 60_000.0
        val now = clock()

        val bucket = buckets.getOrPut(host) { Bucket(tokens = capacity, lastRefillMs = now) }
        val elapsed = (now - bucket.lastRefillMs).coerceAtLeast(0L)
        bucket.tokens = (bucket.tokens + elapsed * refillPerMs).coerceAtMost(capacity)
        bucket.lastRefillMs = now

        return if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            0L
        } else {
            val missing = 1.0 - bucket.tokens
            (missing / refillPerMs).toLong().coerceAtLeast(1L)
        }
    }
}
