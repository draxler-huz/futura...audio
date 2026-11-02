// app/src/main/java/com/futura/alarm/DeviceStateInspector.kt
package com.futura.alarm

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time device telemetry evaluator.
 *
 * Reads battery, thermal, and power-saving state on demand. Returns an
 * immutable [DeviceState] snapshot. Callers use [DeviceState.shouldThrottle]
 * to decide whether to reduce alarm intensity (fewer vibration cycles,
 * shorter screen-wake, less aggressive audio ramp) under constrained
 * conditions.
 */
@Singleton
class DeviceStateInspector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "DeviceStateInspector"
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun inspect(): DeviceState {
        return try {
            DeviceState(
                batteryPercent = readBatteryPercent(),
                charging = readCharging(),
                thermalStatus = readThermalStatus(),
                isPowerSaveMode = powerManager.isPowerSaveMode,
                isDeviceIdleMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager.isDeviceIdleMode
                } else false
            )
        } catch (e: Exception) {
            Log.e(tag, "inspect failed", e)
            DeviceState(
                batteryPercent = -1,
                charging = false,
                thermalStatus = -1,
                isPowerSaveMode = false,
                isDeviceIdleMode = false
            )
        }
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun readBatteryPercent(): Int {
        val intent: Intent = try {
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (e: Exception) {
            return -1
        } ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return (level * 100) / scale
    }

    private fun readCharging(): Boolean = try {
        isChargingViaBatteryManager()
    } catch (e: Exception) {
        false
    }

    private fun isChargingViaBatteryManager(): Boolean {
        val intent: Intent = try {
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (e: Exception) {
            return false
        } ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return try {
            powerManager.currentThermalStatus
        } catch (e: Exception) {
            -1
        }
    }
}
