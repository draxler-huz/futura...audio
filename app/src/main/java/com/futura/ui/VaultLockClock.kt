package com.futura.ui

/**
 * Remembers when the vault was last locked (the user left the vault screen).
 * Reopening within [GRACE_MS] skips the PIN gate; after that the PIN is asked.
 * Held in memory only, so a fresh process always asks for the PIN.
 */
object VaultLockClock {
    const val GRACE_MS = 5 * 60_000L

    @Volatile
    private var lockedAtMs: Long? = null

    /** True while the music side is open after a PIN unlock. */
    @Volatile
    private var open: Boolean = false

    fun markLocked(now: Long = System.currentTimeMillis()) {
        lockedAtMs = now
        open = false
    }

    fun markOpen() {
        open = true
    }

    fun isOpen(): Boolean = open

    fun withinGrace(now: Long = System.currentTimeMillis()): Boolean {
        val locked = lockedAtMs ?: return false
        return now - locked < GRACE_MS
    }
}
