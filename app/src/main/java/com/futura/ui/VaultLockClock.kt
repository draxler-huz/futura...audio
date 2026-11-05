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

    fun markLocked(now: Long = System.currentTimeMillis()) {
        lockedAtMs = now
    }

    fun withinGrace(now: Long = System.currentTimeMillis()): Boolean {
        val locked = lockedAtMs ?: return false
        return now - locked < GRACE_MS
    }
}
