// futura-audio/app/src/main/java/com/futura/FuturaApp.kt
//
// Application entry point for Futura Audio.
//
// Annotated with @HiltAndroidApp so Hilt generates the root DI component
// at build time; every @AndroidEntryPoint activity, @HiltViewModel
// viewmodel, and injected receiver picks up its dependency graph from
// this component.
//
// ── Implementation status ────────────────────────────────────────────────
// Per the tree annotation `@HiltAndroidApp, init, lifecycle`, this class
// owns three concerns:
//
//   1. Hilt root component registration (@HiltAndroidApp).
//   2. Application-scoped initialization hooks.
//   3. Application foreground/background lifecycle observation.
//
// The **actual** initialization work that the feature list calls for
// (AlarmSchedulerImpl re-arm, MediaIndexer warm-up, NativeEngine load,
// VaultManager key unlock, WidgetStateHolder bootstrap, …) cannot be
// performed here yet because none of those classes have been authored.
// Attempting to reference them would violate the "no forward references"
// build rule and would fail to compile. Instead, this file establishes
// the lifecycle scaffolding and the WorkManager manual-initialization
// contract that the AndroidManifest already requires, and exposes a
// well-defined seam (`FuturaApp.onCreate()`'s tail) where each future
// subsystem will attach its own initializer as it is implemented.
//
// ── WorkManager initialization ───────────────────────────────────────────
// AndroidManifest.xml declares `androidx.startup.InitializationProvider`
// with `<meta-data android:name="androidx.work.WorkManagerInitializer"
// tools:node="remove" />`. That removal **disables** WorkManager's automatic
// initialization and requires the Application class to implement
// `androidx.work.Configuration.Provider` instead. If this class did not
// implement it, the very first call to `WorkManager.getInstance(context)`
// would throw IllegalStateException at runtime. `workManagerConfiguration`
// below satisfies that contract.
//
// No custom WorkerFactory is supplied because no `@HiltWorker` classes
// exist yet. When the first one is added (e.g. IntegrityWork,
// SelfHealJobService's upstream WorkRequest), that factory is injected
// via Hilt's HiltWorkerFactory and set here through
// `Configuration.Builder.setWorkerFactory(...)`. The default
// WorkerFactory used right now is fully functional for zero workers.
//
// ── Foreground/background state ──────────────────────────────────────────
// `isForeground: StateFlow<Boolean>` is the canonical signal for
// subsystems that must react to app visibility. Planned consumers,
// each already named in the feature catalogue, are:
//
//   • AutoOffGuard (VisualizerDeckScreen)        → disable shaders when backgrounded
//   • LowPowerOffloadEngine (system)             → downgrade to HW offload on background
//   • OledBurnInPreventer (system)               → pause pixel shift when not visible
//   • SleepTimerEngine (system)                  → only run while foreground OR playing
//
// Exposing the state here, once, avoids each of those classes reinventing
// its own ProcessLifecycleOwner observer and keeps behaviour consistent
// across the whole app.
//
// ── Uncaught exception handler ───────────────────────────────────────────
// Installed after super.onCreate() so that any crash during Hilt graph
// construction is already captured by the previous handler. The handler
// is transparent: it logs the crash with a stable tag and then delegates
// to whatever handler was installed before it (system default in release,
// Android Studio's debugger in debug). No crash is swallowed.

package com.futura

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.futura.alarm.AlarmStore
import com.futura.alarm.DndBypassResolver
import com.futura.alarm.PermissionGuard
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class FuturaApp : Application(), Configuration.Provider {

    @Inject
    lateinit var alarmStore: AlarmStore

    @Inject
    lateinit var dndBypassResolver: DndBypassResolver

    @Inject
    lateinit var permissionGuard: PermissionGuard

    companion object {
        private const val TAG = "FuturaApp"

        // Static accessor for components that cannot use Hilt injection.
        // Broadcast receivers registered at runtime by third-party apps
        // (Tasker through TaskerIntentGateway, Wear through
        // FuturaWearableListener) reach for the Application instance via
        // `context.applicationContext as FuturaApp` in the common case,
        // but a static accessor keeps low-level glue code (JNI callbacks
        // from :engine that need a Context) simpler. Injecting @ApplicationContext
        // is still preferred anywhere Hilt is available.
        @Volatile
        private lateinit var INSTANCE: FuturaApp

        fun get(): FuturaApp {
            check(::INSTANCE.isInitialized) {
                "FuturaApp.get() called before Application.onCreate() ran"
            }
            return INSTANCE
        }
    }

    // ── Lifecycle observation ────────────────────────────────────────────
    private val _isForeground = MutableStateFlow(false)

    /**
     * Emits `true` while at least one activity is in the started state,
     * `false` when the app is fully backgrounded. Hot StateFlow, safe to
     * collect from any coroutine scope.
     */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    // Tracks started activities, not resumed ones, because "started"
    // persists across configuration changes (rotation) while "resumed"
    // briefly drops to false during the change — that drop would
    // incorrectly signal backgrounding to consumers like AutoOffGuard.
    private var startedActivities: Int = 0

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        // Load saved alarms so the Alarms list is populated on a fresh start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { alarmStore.load() }

        Log.i(
            TAG,
            "onCreate() — starting Futura Audio " +
                "versionName=${BuildConfig.VERSION_NAME} " +
                "versionCode=${BuildConfig.VERSION_CODE} " +
                "debug=${BuildConfig.DEBUG}"
        )

        // Hook 1: process-level visibility state.
        registerActivityLifecycleCallbacks(ForegroundTracker())

        // Alarm channel bypasses Do Not Disturb. Created once; later calls are no-ops.
        dndBypassResolver.ensureChannel()
        Log.i(TAG, "permissions: ${permissionGuard.checkAll()}")

        // Hook 2: crash diagnostics. Installed after Hilt has already
        // wired up its own graph, so any exception thrown from a Hilt
        // component is captured here too.
        installUncaughtExceptionHandler()

        // ── Future initialization seam ───────────────────────────────────
        // Each subsystem implemented in later turns attaches its own
        // initializer at this exact location. Order matters (each line
        // depends on the one above), so it is documented now to prevent
        // reordering:
        //
        //   1. VaultManager                 — master key must exist before
        //                                     DecoyPinStore or any other
        //                                     encrypted pref is opened.
        //   2. DecoyStatePersister          — decides which launcher alias
        //                                     is currently enabled; must run
        //                                     before first activity launch.
        //   3. AlarmStore / AlarmSchedulerImpl
        //                                   — re-arms pending alarms; the
        //                                     scheduler reads state that the
        //                                     store populated.
        //   4. FuturaAlarmService controller — starts the foreground service
        //                                     if an alarm is due while the
        //                                     process is cold.
        //   5. MediaIndexer                 — warms the Room cache off the
        //                                     main thread (CoroutineScope).
        //   6. NativeEngine                 — loads libfutura_engine.so and
        //                                     validates ABI; must succeed
        //                                     before any DSP/ML call.
        //   7. AudioTapSubscription         — attaches the tap subscriber
        //                                     exposed by the engine.
        //   8. WidgetStateHolder            — hydrates the RemoteViews state
        //                                     so the launcher widget shows
        //                                     the correct track from cold.
        //   9. AppShortcutManager           — reconciles dynamic shortcuts
        //                                     against current library state.
        //  10. ForegroundServiceWatchdog    — begins periodic health check
        //                                     for FuturaAlarmService.
        //  11. SelfHealJobService           — schedules the persisted
        //                                     JobService if none is pending.
        //  12. LogcatDiagnosticRecorder     — opens the encrypted log sink.
        //
        // Every one of those classes is already listed in futura-audio.txt
        // under app/src/main/java/com/futura/ and will be delivered in
        // dependency order. This comment is the contract for where each
        // will be wired in.

        Log.i(TAG, "onCreate() — Futura Audio ready")
    }

    // ── WorkManager manual configuration ─────────────────────────────────
    //
    // Required because the manifest removes `WorkManagerInitializer`
    // from `androidx.startup.InitializationProvider`. Without this
    // override, the first `WorkManager.getInstance()` call would throw.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    // ── Uncaught exception capture ───────────────────────────────────────
    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log first, delegate second. If the delegate itself crashes
            // while handling, our log line is already on disk.
            Log.e(TAG, "Uncaught exception on thread '${thread.name}'", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ── Foreground tracker ───────────────────────────────────────────────
    private inner class ForegroundTracker : Application.ActivityLifecycleCallbacks {

        override fun onActivityStarted(activity: Activity) {
            if (startedActivities == 0) {
                _isForeground.value = true
                Log.d(TAG, "App entered foreground (started=${activity.javaClass.simpleName})")
            }
            startedActivities++
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) {
                _isForeground.value = false
                Log.d(TAG, "App left foreground (stopped=${activity.javaClass.simpleName})")
            }
        }

        // The remaining callbacks are required by the interface but are
        // not needed for foreground tracking. They are kept empty rather
        // than omitted so that a future subsystem which wants finer
        // granularity (e.g. per-activity timers) can extend the same
        // callback without introducing a second ActivityLifecycleCallbacks
        // registration.
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}