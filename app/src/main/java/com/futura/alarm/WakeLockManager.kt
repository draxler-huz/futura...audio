// app/src/main/java/com/futura/alarm/WakeLockManager.kt
package com.futura.alarm

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reference-counted [PowerManager.WakeLock] manager.
 *
 * Callers obtain a wakelock via [acquire] and release it via [release]
 * with the same tag. The manager holds one physical `PARTIAL_WAKE_LOCK`
 * per distinct tag, reference-counted so nested acquires from different
 * code paths share the lock.
 *
 * Timeout: every physical lock is acquired with a [MAX_ACQUIRE_MS]
 * timeout. If the caller forgets to release, the OS releases it
 * automatically at expiry, preventing the classic "stuck wakelock drains
 * battery overnight" failure mode.
 *
 * Rule 7: `locks` is guarded by [mutex]. All public methods are
 * suspending; the class is safe to call from any coroutine.
 */
@Singleton
class WakeLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "WakeLockManager"

    private data class Held(
        val lock: PowerManager.WakeLock,
        var refCount: Int
    )

    private val mutex = Mutex()
    private val locks = HashMap<String, Held>()

    suspend fun acquire(tagName: String) {
        mutex.withLock {
            val held = locks[tagName]
            if (held != null && held.lock.isHeld) {
                held.refCount++
                return
            }
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE)
                    as PowerManager
                val lock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "futura:$tagName"
                ).apply {
                    setReferenceCounted(false)  // we manage refcount
                    acquire(MAX_ACQUIRE_MS)
                }
                locks[tagName] = Held(lock, 1)
            } catch (e: Exception) {
                Log.e(tag, "Failed to acquire '$tagName'", e)
            }
        }
    }

    suspend fun release(tagName: String) {
        mutex.withLock {
            val held = locks[tagName] ?: return
            held.refCount--
            if (held.refCount <= 0) {
                try {
                    if (held.lock.isHeld) held.lock.release()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to release '$tagName'", e)
                }
                locks.remove(tagName)
            }
        }
    }

    /** Release every held lock. Called on app teardown / alarm dismissal. */
    suspend fun releaseAll() {
        mutex.withLock {
            for ((tagName, held) in locks) {
                try {
                    if (held.lock.isHeld) held.lock.release()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to release '$tagName'", e)
                }
            }
            locks.clear()
        }
    }

    companion object {
        const val MAX_ACQUIRE_MS: Long = 10L * 60L * 1000L  // 10 min
    }
}
