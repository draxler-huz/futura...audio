package com.futura.sources.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CircuitBreakerTest {

    private var now = 1_000_000L
    private fun breaker() = CircuitBreaker(
        failureThreshold = 3,
        windowMs = 10 * 60 * 1000L,
        cooldownMs = 5 * 60 * 1000L,
        clock = { now }
    )

    @Test
    fun `stays available until the threshold is reached`() {
        val b = breaker()
        b.recordFailure("x")
        b.recordFailure("x")
        assertThat(b.isAvailable("x")).isTrue()
        b.recordFailure("x")
        assertThat(b.isAvailable("x")).isFalse()
    }

    @Test
    fun `closes again after the cooldown`() {
        val b = breaker()
        repeat(3) { b.recordFailure("x") }
        assertThat(b.isAvailable("x")).isFalse()
        now += 5 * 60 * 1000L + 1
        assertThat(b.isAvailable("x")).isTrue()
    }

    @Test
    fun `a success clears the failure history`() {
        val b = breaker()
        b.recordFailure("x")
        b.recordFailure("x")
        b.recordSuccess("x")
        b.recordFailure("x")
        assertThat(b.isAvailable("x")).isTrue()
    }

    @Test
    fun `failures spread beyond the window do not trip the breaker`() {
        val b = breaker()
        b.recordFailure("x")
        now += 11 * 60 * 1000L
        b.recordFailure("x")
        now += 11 * 60 * 1000L
        b.recordFailure("x")
        assertThat(b.isAvailable("x")).isTrue()
    }
}
