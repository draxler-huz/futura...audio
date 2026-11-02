// app/src/main/java/com/futura/alarm/DeviceState.kt
package com.futura.alarm

/**
 * Immutable snapshot of current device operational metrics, captured by
 * [DeviceStateInspector] and consumed by any alarm subsystem that needs
 * to adapt behaviour to battery, thermal, or power-saving constraints.
 *
 * @param batteryPercent 0–100, or -1 if unknown.
 * @param charging true if the device is currently charging (any source).
 * @param thermalStatus [android.os.PowerManager] thermal status constant
 *        (THERMAL_STATUS_NONE / LIGHT / MODERATE / SEVERE / CRITICAL /
 *        EMERGENCY / SHUTDOWN), or -1 if unavailable (pre-API 29).
 * @param isPowerSaveMode Whether the OS battery saver is active.
 * @param isDeviceIdleMode Whether the device is in deep doze.
 * @param capturedAtMs Epoch ms at capture.
 */
data class DeviceState(
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalStatus: Int,
    val isPowerSaveMode: Boolean,
    val isDeviceIdleMode: Boolean,
    val capturedAtMs: Long = System.currentTimeMillis()
) {
    /** True when the state suggests throttling alarm behaviour is needed. */
    val shouldThrottle: Boolean
        get() = isPowerSaveMode ||
                isDeviceIdleMode ||
                thermalStatus >= THERMAL_STATUS_SEVERE

    companion object {
        // Mirrors android.os.PowerManager thermal constants (API 29+).
        const val THERMAL_STATUS_NONE = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4
        const val THERMAL_STATUS_EMERGENCY = 5
        const val THERMAL_STATUS_SHUTDOWN = 6
    }
}
