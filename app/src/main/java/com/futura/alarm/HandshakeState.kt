// app/src/main/java/com/futura/alarm/HandshakeState.kt
package com.futura.alarm

import java.util.UUID

/**
 * Atomic four-phase state machine describing the lifecycle of the
 * high-priority alarm panic stream → standard player queue handoff.
 *
 * Transitions:
 *
 *   Pending ─────→ Transitioned
 *      │
 *      ├──────────→ Aborted      (caller cancelled before handshake fired)
 *      └──────────→ TimedOut     (no ack within timeout)
 *
 * Terminal states are `Transitioned`, `Aborted`, `TimedOut`. Once a
 * terminal state is reached, further transitions are no-ops; callers
 * query [isTerminal] to detect this.
 */
sealed class HandshakeState {

    /** Unique per handshake attempt; callers use this to correlate
     *  log lines and completion callbacks. */
    abstract val handshakeId: String

    /** Epoch ms when this state was entered. */
    abstract val enteredAtMs: Long

    data class Pending(
        override val handshakeId: String = UUID.randomUUID().toString(),
        override val enteredAtMs: Long = System.currentTimeMillis(),
        val specId: String,
        val targetPlayerQueueMs: Long = 0L
    ) : HandshakeState()

    data class Transitioned(
        override val handshakeId: String,
        override val enteredAtMs: Long = System.currentTimeMillis(),
        val specId: String
    ) : HandshakeState()

    data class Aborted(
        override val handshakeId: String,
        override val enteredAtMs: Long = System.currentTimeMillis(),
        val specId: String,
        val reason: String = ""
    ) : HandshakeState()

    data class TimedOut(
        override val handshakeId: String,
        override val enteredAtMs: Long = System.currentTimeMillis(),
        val specId: String,
        val timeoutMs: Long
    ) : HandshakeState()

    val isTerminal: Boolean
        get() = this !is Pending
}
