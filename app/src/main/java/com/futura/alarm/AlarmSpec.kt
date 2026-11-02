// app/src/main/java/com/futura/alarm/AlarmSpec.kt
package com.futura.alarm

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Immutable alarm configuration.
 *
 * Persistence-optimised: `@Entity` so `AlarmStore` can persist directly
 * without a separate DTO. Recurrence is encoded as a bitmask so the
 * scheduler can compute the next trigger cheaply.
 *
 * Volume ramp profile is a logarithmic exponent in `[1.0f, 4.0f]`:
 *   1.0 → linear ramp over [rampDurationSeconds]
 *   2.0 → quadratic (default)
 *   4.0 → aggressive logarithmic ramp
 * The `CircadianAudioSync` module will read this to synchronise against
 * `CircadianRampModule`.
 */
@Entity(tableName = "alarm_specs")
data class AlarmSpec(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val label: String = "",
    /** Days-of-week bitmask: bit 0 = Sunday … bit 6 = Saturday. 0 = one-shot. */
    val recurrenceMask: Int = 0,
    /** Sound URI pointer (string form). May be encrypted at rest by AlarmStore. */
    val soundUri: String,
    /** 0.0f..1.0f — final volume target reached after the ramp. */
    val targetVolume: Float = 1.0f,
    /** Seconds the volume ramp should take from minVolume → targetVolume. */
    val rampDurationSeconds: Int = 30,
    /** Log-profile exponent for the ramp; see class KDoc. */
    val rampProfile: Float = 2.0f,
    /** Whether this alarm requires biometric authentication to dismiss. */
    val biometricLocked: Boolean = false,
    /** Whether the alarm is currently enabled. */
    val enabled: Boolean = true,
    /** Snooze backoff base in minutes; exponential per snooze index. */
    val snoozeBaseMinutes: Int = 5,
    /** Maximum snooze cycles before the alarm force-dismisses. */
    val maxSnoozeCycles: Int = 3,
    /** Creation timestamp (epoch ms). */
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(hour in 0..23) { "hour must be in [0, 23]" }
        require(minute in 0..59) { "minute must be in [0, 59]" }
        require(recurrenceMask in 0..0x7F) { "recurrenceMask must fit 7-day bits" }
        require(targetVolume in 0.0f..1.0f) { "targetVolume must be in [0, 1]" }
        require(rampDurationSeconds >= 0) { "rampDurationSeconds must be >= 0" }
        require(rampProfile in 1.0f..4.0f) { "rampProfile must be in [1, 4]" }
        require(snoozeBaseMinutes in 1..60) { "snoozeBaseMinutes must be in [1, 60]" }
        require(maxSnoozeCycles in 0..10) { "maxSnoozeCycles must be in [0, 10]" }
    }

    /** Next trigger epoch in ms, strictly after [from] (default: now). */
    fun nextTriggerMillis(from: Long = System.currentTimeMillis()): Long {
        val base = java.util.Calendar.getInstance().apply {
            timeInMillis = from
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (recurrenceMask == 0) {
            if (base.timeInMillis <= from) base.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        // Recurring: the first enabled day, counting from today, whose
        // trigger time is still ahead of `from`. Each candidate is computed
        // from the same base, so the date cursor never drifts.
        for (delta in 0..7) {
            val candidate = base.clone() as java.util.Calendar
            candidate.add(java.util.Calendar.DAY_OF_YEAR, delta)
            val dayBit = candidate.get(java.util.Calendar.DAY_OF_WEEK) - 1 // 0 = Sun
            if (((recurrenceMask shr dayBit) and 1) == 1 && candidate.timeInMillis > from) {
                return candidate.timeInMillis
            }
        }
        // Unreachable with a non-zero mask; kept as a safe fallback.
        return from + 24 * 60 * 60 * 1000L
    }
}
