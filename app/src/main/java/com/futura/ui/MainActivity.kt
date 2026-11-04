// futura-audio/app/src/main/java/com/futura/ui/MainActivity.kt
//
// Single-activity Compose host for Futura Audio.
//
// ── Responsibility ────────────────────────────────────────────────────────
// • Render the Compose tree under FuturaTheme.
// • Own the application's navigation graph (NavHost) with every Phase 1–6
//   destination registered exactly once.
// • Resolve incoming Intents (launcher aliases, dynamic shortcuts from
//   res/xml/shortcuts.xml, and the futura://open deep link declared in
//   AndroidManifest.xml) into a canonical route and hand it to the NavHost.
// • Because AndroidManifest.xml sets launchMode="singleTask", subsequent
//   intents (e.g. tapping a shortcut while the app is already open) arrive
//   in onNewIntent() rather than onCreate(). The same route-resolution
//   pipeline is used for both paths via a MutableStateFlow that Compose
//   observes.
//
// ── Intentionally temporary pieces ───────────────────────────────────────
// The tree lists every destination Screen as its own file. None of those
// files have been delivered yet in this build sequence, so this file
// provides *private placeholder composables* for each route. That lets the
// app compile, launch, and prove its navigation graph end-to-end today,
// while leaving each route's UI as a clearly-marked seam to swap in the
// real Screen implementation the moment its file is authored.
//
// The shared FuturaTheme in theme/Theme.kt supplies the app-wide dark
// palette, typography, and shape system used by this activity and future
// Compose screens.
//
// ── Library dependencies used ─────────────────────────────────────────────
// All references below resolve to artifacts declared in
// gradle/libs.versions.toml and applied in app/build.gradle.kts:
//   androidx.activity:activity-compose (setContent, enableEdgeToEdge)
//   androidx.compose.* / material3     (UI)
//   androidx.navigation:navigation-compose (NavHost, rememberNavController)
//   androidx.lifecycle:lifecycle-runtime-compose (collectAsStateWithLifecycle)
//   com.google.dagger:hilt-android     (@AndroidEntryPoint)
//   kotlinx.coroutines                 (MutableStateFlow, StateFlow)
//
// No project class is imported other than the resources generated from
// the files already written in this conversation (R.string.*, R.color.*).

package com.futura.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.futura.R
import com.futura.alarm.VaultUnlockProof
import com.futura.audio.ui.components.UniversalMediaScreen
import com.futura.audio.ui.viewmodel.UniversalMediaViewModel
import com.futura.ui.screens.AlarmEditorScreen
import com.futura.ui.screens.AlarmListScreen
import com.futura.ui.screens.LoadingScreen
import com.futura.ui.screens.VaultContentScreen
import com.futura.ui.screens.VaultSecurityScreen
import com.futura.ui.screens.findFragmentActivity
import com.futura.ui.viewmodel.AlarmEditorViewModel
import com.futura.ui.viewmodel.AlarmListViewModel
import com.futura.ui.screens.NowPlayingScreen
import com.futura.ui.screens.WakeUpOverlayScreen
import com.futura.ui.theme.FuturaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ═════════════════════════════════════════════════════════════════════════
// Route catalogue
// ═════════════════════════════════════════════════════════════════════════

/**
 * Every navigation destination in the app, with the string route used by
 * NavHost. Declared as an enum so the compiler catches a missing branch
 * whenever a new destination is added.
 *
 * The `route` strings are opaque to the OS and never appear on disk — they
 * exist only inside the NavController back stack.
 */
/** Editor route. `alarmId` is optional: absent means a new alarm. */
private const val ALARM_EDITOR_ROUTE = "alarm_editor?alarmId={alarmId}"

private fun alarmEditorRoute(alarmId: String?): String =
    if (alarmId == null) "alarm_editor" else "alarm_editor?alarmId=$alarmId"

enum class MainRoute(val route: String) {
    /** Transient bootstrap screen shown while MainViewModel.isReady == false. */
    Loading("loading"),

    /** Phase 1 — full-screen alarm overlay (WakeUpOverlayScreen). */
    WakeUpOverlay("wakeup"),

    /** Root screen: the alarm list, with entry to the editor and vault. */
    AlarmList("alarms"),

    /** Biometric and PIN gate for the vault. */
    VaultSecurity("vault"),

    /** The unlocked vault: protected records. */
    VaultContent("vault_content"),

    /** Phase 2 — Now Playing surface (NowPlayingScreen). */
    NowPlaying("now_playing"),

    /** Phase 3 — Library browser (LibraryScreen). */
    Library("library"),

    /** Universal Media Hub: Category 1 sources, cover flow, web embeds. */
    MediaHub("media_hub"),

    /** Phase 4 — DJ Studio (DjStudioScreen). */
    DjStudio("dj_studio"),

    /** Phase 5 — Visualizer Deck (VisualizerDeckScreen). */
    VisualizerDeck("visualizer"),

    /** Phase 6 — Car Mode (CarModeScreen). */
    CarMode("car_mode"),

    /** Phase 6 — Bedside Sleep Dock (SleepDockScreen). */
    SleepDock("sleep_dock"),

    /** Phase 6 — Privacy Vault (PrivacyVaultScreen). */
    PrivacyVault("privacy_vault");

    companion object {

        // ── Launcher shortcut actions (res/xml/shortcuts.xml) ────────────
        // These action strings are the contract declared by shortcuts.xml.
        // Tapping a shortcut starts .ui.MainActivity with the corresponding
        // action; LauncherApps.startShortcut() bypasses the exported=false
        // restriction, so no manifest intent-filter is required.
        private const val ACTION_SHORTCUT_ALARM = "com.futura.action.SHORTCUT_ALARM"
        private const val ACTION_SHORTCUT_FAVORITES = "com.futura.action.SHORTCUT_FAVORITES"
        private const val ACTION_SHORTCUT_PLAYLIST = "com.futura.action.SHORTCUT_PLAYLIST"

        // ── Deep link constants (AndroidManifest.xml) ────────────────────
        private const val DEEP_LINK_SCHEME = "futura"
        private const val DEEP_LINK_HOST = "open"

        /**
         * Default destination when the app is launched without an intent
         * that resolves to a specific route — i.e. the cold-start
         * launcher tap with no pending alarm.
         *
         * NOTE: Once MainViewModel (isReady StateFlow) and LoadingScreen
         * are delivered, the start destination of the NavHost becomes
         * Loading, and this constant becomes the route Loading transitions
         * *to* when the app finishes bootstrapping. Until then it is the
         * NavHost's start destination, which is why the loading placeholder
         * is never actually reached during ordinary use.
         */
        val START = AlarmList

        /**
         * Resolve an incoming Intent to a route, or null if the intent
         * does not target a specific destination (plain launcher tap,
         * configuration change, etc.).
         *
         * Handles three intent sources:
         *   1. Launcher shortcut actions from res/xml/shortcuts.xml.
         *   2. The futura://open/<destination> deep link declared in
         *      AndroidManifest.xml (reachable via in-app
         *      startActivity(Intent(Intent.ACTION_VIEW, …)) and via
         *      any external tool that has been granted access).
         *   3. Everything else → null, which means "no forced navigation".
         */
        fun fromIntent(intent: Intent?): MainRoute? {
            intent ?: return null
            return when (intent.action) {
                ACTION_SHORTCUT_ALARM -> WakeUpOverlay
                ACTION_SHORTCUT_FAVORITES -> NowPlaying
                ACTION_SHORTCUT_PLAYLIST -> Library
                Intent.ACTION_VIEW -> resolveDeepLink(intent)
                else -> null
            }
        }

        private fun resolveDeepLink(intent: Intent): MainRoute? {
            val data = intent.data ?: return null
            if (data.scheme != DEEP_LINK_SCHEME) return null
            if (data.host != DEEP_LINK_HOST) return null
            val key = data.pathSegments.firstOrNull() ?: data.lastPathSegment ?: return null
            return when (key) {
                "wakeup", "alarm" -> WakeUpOverlay
                "now_playing", "nowplaying" -> NowPlaying
                "library" -> Library
                "hub", "media", "media_hub" -> MediaHub
                "dj", "dj_studio" -> DjStudio
                "visualizer" -> VisualizerDeck
                "car", "car_mode" -> CarMode
                "sleep", "sleep_dock" -> SleepDock
                "privacy", "vault" -> PrivacyVault
                else -> null
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Activity
// ═════════════════════════════════════════════════════════════════════════

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Broadcasts a new destination to the Compose tree when the activity
     * receives an Intent that should trigger navigation. Set in onCreate
     * (cold start) and onNewIntent (singleTask re-entry). Null means "no
     * forced navigation — let the NavHost start on its default route".
     */
    private val pendingRoute = MutableStateFlow<MainRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only read the launching intent on first creation. On configuration
        // change (rotation, dark-mode toggle) the NavHost restores its
        // back stack from savedInstanceState, so re-applying the original
        // intent's route would incorrectly navigate away from whatever
        // screen the user was on.
        if (savedInstanceState == null) {
            pendingRoute.value = MainRoute.fromIntent(intent)
        }

        setContent {
            FuturaTheme {
                MainNavHost(
                    pendingRoute = pendingRoute.asStateFlow(),
                    onFinish = ::finish
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With launchMode="singleTask", subsequent intents (shortcut taps,
        // deep links) arrive here while the activity is already running.
        // Replacing the activity's intent keeps `getIntent()` consistent
        // with what was most recently delivered — matching what the OS
        // would have done had the activity been created fresh.
        setIntent(intent)
        pendingRoute.value = MainRoute.fromIntent(intent)
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Navigation host
// ═════════════════════════════════════════════════════════════════════════

/**
 * Wraps the NavController, observes [pendingRoute] for intent-driven
 * navigation, and registers every destination in the app.
 *
 * Each `composable(...)` block currently points at a private placeholder
 * function. As each Screen file is delivered, that one line is replaced
 * with a call to the real Screen composable; nothing else in this file
 * changes.
 */
@Composable
private fun MainNavHost(
    pendingRoute: StateFlow<MainRoute?>,
    onFinish: () -> Unit
) {
    val navController = rememberNavController()
    val pending by pendingRoute.collectAsStateWithLifecycle()

    // React to intents that arrive after first composition. On first
    // composition, the NavHost's startDestination below already reflects
    // any launching intent, so this effect is a no-op for the initial
    // route and only fires for onNewIntent() deliveries.
    LaunchedEffect(pending) {
        pending?.let { route ->
            navController.navigate(route.route) {
                launchSingleTop = true
            }
        }
    }

    // First composition: prefer any route the launching intent specified,
    // otherwise show Loading until UI bootstrap is ready.
    val startDestination = remember {
        pendingRoute.value?.route ?: MainRoute.Loading.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        composable(MainRoute.Loading.route) {
            LoadingScreen(
                onReady = {
                    navController.navigate(MainRoute.START.route) {
                        popUpTo(MainRoute.Loading.route) { inclusive = true }
                    }
                }
            )
        }
        composable(MainRoute.AlarmList.route) {
            val vm: AlarmListViewModel = hiltViewModel()
            val alarms by vm.alarms.collectAsStateWithLifecycle()
            val activity = LocalContext.current.findFragmentActivity()
            AlarmListScreen(
                alarms = alarms,
                onAdd = { navController.navigate(alarmEditorRoute(null)) },
                onEdit = { navController.navigate(alarmEditorRoute(it.id)) },
                onToggle = { spec, on, proof -> vm.setEnabled(spec, on, proof) },
                onDelete = { spec, proof -> vm.delete(spec, proof) },
                requireAuth = { granted -> activity?.let { vm.authorize(it, granted) } },
                onOpenVault = { navController.navigate(MainRoute.VaultSecurity.route) }
            )
        }
        composable(
            route = ALARM_EDITOR_ROUTE,
            arguments = listOf(
                navArgument(AlarmEditorViewModel.ARG_ALARM_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            val vm: AlarmEditorViewModel = hiltViewModel()
            val activity = LocalContext.current.findFragmentActivity()
            AlarmEditorScreen(
                initial = vm.initial,
                isEditing = vm.original != null,
                biometricAvailable = vm.biometricAvailable(),
                requireAuth = { granted ->
                    activity?.let { vm.authorize(it, granted) }
                },
                onSave = { spec, proof, result ->
                    vm.save(spec, proof) { error ->
                        result(error)
                        if (error == null) navController.popBackStack()
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(MainRoute.VaultSecurity.route) {
            VaultSecurityScreen(
                onUnlocked = {
                    navController.navigate(MainRoute.VaultContent.route) {
                        popUpTo(MainRoute.VaultSecurity.route) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(MainRoute.VaultContent.route) {
            VaultContentScreen(
                onLocked = { navController.popBackStack(MainRoute.AlarmList.route, false) }
            )
        }
        composable(MainRoute.WakeUpOverlay.route) {
            WakeUpOverlayScreen(
                onSnooze = { navController.navigateSingleTop(MainRoute.NowPlaying) },
                onDismiss = onFinish
            )
        }
        composable(MainRoute.NowPlaying.route) {
            NowPlayingScreen(
                onAlarmDeck = { navController.navigateSingleTop(MainRoute.WakeUpOverlay) },
                onLibrary = { navController.navigateSingleTop(MainRoute.Library) },
                onDjStudio = { navController.navigateSingleTop(MainRoute.DjStudio) },
                onVisualizer = { navController.navigateSingleTop(MainRoute.VisualizerDeck) },
                onPrivacy = { navController.navigateSingleTop(MainRoute.PrivacyVault) }
            )
        }
        composable(MainRoute.Library.route) {
            LibraryPlaceholder(
                onNowPlaying = { navController.navigateSingleTop(MainRoute.NowPlaying) },
                onMediaHub = { navController.navigateSingleTop(MainRoute.MediaHub) }
            )
        }
        composable(MainRoute.MediaHub.route) {
            val vm: UniversalMediaViewModel = hiltViewModel()
            UniversalMediaScreen(viewModel = vm)
        }
        composable(MainRoute.DjStudio.route) {
            DjStudioPlaceholder(
                onBack = { navController.popBackStack() }
            )
        }
        composable(MainRoute.VisualizerDeck.route) {
            VisualizerDeckPlaceholder(
                onBack = { navController.popBackStack() }
            )
        }
        composable(MainRoute.CarMode.route) {
            CarModePlaceholder(
                onBack = { navController.popBackStack() }
            )
        }
        composable(MainRoute.SleepDock.route) {
            SleepDockPlaceholder(
                onBack = { navController.popBackStack() }
            )
        }
        composable(MainRoute.PrivacyVault.route) {
            PrivacyVaultPlaceholder(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private fun NavHostController.navigateSingleTop(route: MainRoute) {
    navigate(route.route) { launchSingleTop = true }
}

@Composable
private fun LibraryPlaceholder(onNowPlaying: () -> Unit, onMediaHub: () -> Unit) {
    // TEMPORARY: replaces ui/screens/LibraryScreen.kt.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.library_dock_library),
            color = Color(0xFF00FF66),
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNowPlaying, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.nowplaying_top_alarm_deck))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onMediaHub, modifier = Modifier.fillMaxWidth()) {
            Text("Universal Media Hub")
        }
    }
}

@Composable
private fun DjStudioPlaceholder(onBack: () -> Unit) {
    // TEMPORARY: replaces ui/screens/DjStudioScreen.kt.
    PlaceholderScreen(
        title = stringResource(R.string.dj_studio_title),
        onBack = onBack
    )
}

@Composable
private fun VisualizerDeckPlaceholder(onBack: () -> Unit) {
    // TEMPORARY: replaces ui/screens/VisualizerDeckScreen.kt.
    PlaceholderScreen(
        title = stringResource(R.string.visualizer_title),
        onBack = onBack
    )
}

@Composable
private fun CarModePlaceholder(onBack: () -> Unit) {
    // TEMPORARY: replaces ui/screens/CarModeScreen.kt.
    PlaceholderScreen(
        title = stringResource(R.string.car_mode_title),
        onBack = onBack
    )
}

@Composable
private fun SleepDockPlaceholder(onBack: () -> Unit) {
    // TEMPORARY: replaces ui/screens/SleepDockScreen.kt.
    PlaceholderScreen(
        title = stringResource(R.string.sleep_dock_title),
        onBack = onBack
    )
}

@Composable
private fun PrivacyVaultPlaceholder(onBack: () -> Unit) {
    // TEMPORARY: replaces ui/screens/PrivacyVaultScreen.kt.
    PlaceholderScreen(
        title = stringResource(R.string.library_dock_privacy),
        onBack = onBack
    )
}

/**
 * Shared body for the simpler placeholders. Renders a centred title and a
 * single "Back" button, which is all that is needed to prove the route
 * exists and can be exited.
 */
@Composable
private fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = Color(0xFF00FF66),
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back))
        }
    }
}