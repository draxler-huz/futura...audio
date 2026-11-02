package com.futura.alarm

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages the launcher-alias DISGUISED / UNLOCKED state.
 *
 * The app ships with two launcher aliases (declared in the manifest as
 * `activity-alias` entries targeting `.ui.MainActivity`):
 *   • `.ui.MainActivityFutura`   — visible when UNLOCKED (default).
 *   • `.ui.MainActivityDisguise` — visible when DISGUISED.
 *
 * Aliases exist only in the manifest, so they are referenced by class
 * name string rather than by `::class.java`.
 *
 * [setMode] toggles the aliases via
 * [PackageManager.setComponentEnabledSetting]. The target alias is
 * enabled before the other is disabled, so the launcher never shows zero
 * icons for this app. The package-wide `DONT_KILL_APP` flag keeps the
 * process alive across the toggle.
 *
 * Persistence: state is stored in-memory only. On process death, the
 * launcher sees whichever alias was last committed to the package
 * manager, which is what the user expects. This is the correct
 * behaviour — a decoy that vanished on reboot would defeat itself.
 */
@Singleton
class DecoyStatePersister @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "DecoyStatePersister"
    private val mutex = Mutex()

    enum class Mode { DISGUISED, UNLOCKED }

    private val _mode = MutableStateFlow(Mode.UNLOCKED)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val futuraAlias by lazy { ComponentName(context, FUTURA_ALIAS) }
    private val disguiseAlias by lazy { ComponentName(context, DISGUISE_ALIAS) }

    /** Initialise from the current package-manager state. Call once at
     *  application start before any UI observes [mode]. */
    suspend fun loadCurrentMode() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val state = context.packageManager.getComponentEnabledSetting(disguiseAlias)
                _mode.value = when (state) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> Mode.DISGUISED
                    else -> Mode.UNLOCKED
                }
            } catch (e: Exception) {
                Log.e(tag, "loadCurrentMode failed", e)
            }
        }
    }

    /** Switch to [target] mode. Idempotent. */
    suspend fun setMode(target: Mode): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { applyLocked(target) }
    }

    /** Flip between the two modes. Returns the mode actually in effect
     *  afterwards, which equals the requested one only if the switch
     *  succeeded. The read and the switch happen under one lock, so two
     *  concurrent toggles cannot both flip to the same mode. */
    suspend fun toggle(): Mode = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = if (_mode.value == Mode.UNLOCKED) Mode.DISGUISED else Mode.UNLOCKED
            applyLocked(target)
            _mode.value
        }
    }

    /** Must be called with [mutex] held. */
    private fun applyLocked(target: Mode): Boolean {
        if (_mode.value == target) return true
        return try {
            val pm = context.packageManager
            val on = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            // Enable the target first. If this throws, nothing has been
            // disabled and the current icon stays. Disabling the old alias
            // second means the worst partial state is briefly two icons,
            // never zero.
            val (enable, disable) = when (target) {
                Mode.UNLOCKED -> futuraAlias to disguiseAlias
                Mode.DISGUISED -> disguiseAlias to futuraAlias
            }
            pm.setComponentEnabledSetting(enable, on, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(disable, off, PackageManager.DONT_KILL_APP)

            _mode.value = target
            Log.i(tag, "Mode → $target")
            true
        } catch (e: Exception) {
            Log.e(tag, "switch to $target failed", e)
            false
        }
    }

    private companion object {
        const val FUTURA_ALIAS = "com.futura.ui.MainActivityFutura"
        const val DISGUISE_ALIAS = "com.futura.ui.MainActivityDisguise"
    }
}
