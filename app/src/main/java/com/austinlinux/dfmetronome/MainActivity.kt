package com.austinlinux.dfmetronome

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch
import com.austinlinux.dfmetronome.ui.legal.NoticeScreen
import com.austinlinux.dfmetronome.ui.legal.UpgradeFailedScreen
import com.austinlinux.dfmetronome.data.pendingUpgradeFailure
import com.austinlinux.dfmetronome.data.clearUpgradeFailure
import com.austinlinux.dfmetronome.data.failedBackupFile
import com.austinlinux.dfmetronome.data.checkpointQuietly
import com.austinlinux.dfmetronome.ui.legal.bundledNoticeFingerprint
import com.austinlinux.dfmetronome.ui.legal.shouldShowNotice
import com.austinlinux.dfmetronome.ui.theme.PmColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.austinlinux.dfmetronome.debug.DebugCapture
import com.austinlinux.dfmetronome.engine.StartMode
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.austinlinux.dfmetronome.engine.SessionConfig
import com.austinlinux.dfmetronome.permissions.PermissionsManager
import com.austinlinux.dfmetronome.service.SessionForegroundService
import com.austinlinux.dfmetronome.service.SessionStateBus
import com.austinlinux.dfmetronome.ui.history.HistoryScreen
import com.austinlinux.dfmetronome.ui.history.HistoryViewModel
import com.austinlinux.dfmetronome.ui.legal.LegalDoc
import com.austinlinux.dfmetronome.ui.legal.LegalReadingScreen
import com.austinlinux.dfmetronome.ui.session.LiveSessionScreen
import com.austinlinux.dfmetronome.ui.session.LiveSessionViewModel
import com.austinlinux.dfmetronome.ui.summary.SessionSummaryScreen
import com.austinlinux.dfmetronome.ui.summary.SessionSummaryViewModel
import com.austinlinux.dfmetronome.ui.settings.SettingsScreen
import com.austinlinux.dfmetronome.ui.settings.SettingsViewModel
import com.austinlinux.dfmetronome.ui.setup.SetupScreen
import com.austinlinux.dfmetronome.ui.setup.SetupViewModel
import com.austinlinux.dfmetronome.ui.theme.LocalSharedScopes
import com.austinlinux.dfmetronome.ui.theme.PM_MORPH_MS
import com.austinlinux.dfmetronome.ui.theme.PmMotionEasing
import com.austinlinux.dfmetronome.ui.theme.PulseMetronomeTheme
import com.austinlinux.dfmetronome.ui.theme.SharedScopes

/** Maps a legal doc to its route; NOTICE has none so a link to it from FAQ/Privacy is ignored. */
private fun routeFor(doc: LegalDoc): String? = when (doc) {
    LegalDoc.FAQ -> Routes.FAQ
    LegalDoc.PRIVACY -> Routes.PRIVACY
    LegalDoc.INFO -> Routes.INFO
    LegalDoc.NOTICE -> null
}

private object Routes {
    const val SETUP = "setup"
    const val LIVE_SESSION = "live_session"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val FAQ = "faq"
    const val INFO = "info"
    const val PRIVACY = "privacy"
    const val NOTICE = "notice"
    const val UPGRADE_FAILED = "upgrade_failed"
    const val SUMMARY = "summary/{sessionId}?fromSession={fromSession}"
    fun summary(id: Long, fromSession: Boolean) = "summary/$id?fromSession=$fromSession"
}

// A request that comes back denied this fast showed no dialog: Android auto-denied it (two earlier
// "Don't allow"s, possibly in a previous launch or after a revoke in Settings). The rationale API
// stays false in that state, so elapsed time is the only signal the app has.
private const val BLE_AUTO_DENY_MS = 500L

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Targeting API 35+ makes Android 15+ draw the app behind the system bars; keep the bars dark with light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as PulseMetronomeApp
        app.noteComponent("MainActivity")

        setContent {
            PulseMetronomeTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val navController = rememberNavController()

                    // Runtime permission gating - added in final review (C1): the app
                    // previously never requested ANY runtime permission, which crashes on the
                    // first real Scan/Start tap on API 31+ (SecurityException from
                    // startScan/connectGatt in BleModule, and from startForeground's
                    // connectedDevice type) and silently hides notifications on API 33+.
                    // Requested once per launch, only once the user is past the legal notice (the
                    // Setup route asks); SetupScreen's BLE-touching actions
                    // are wrapped to re-request if still missing (e.g. after a prior denial)
                    // rather than crashing.
                    val requiredPermissions = remember {
                        PermissionsManager.requiredRuntimePermissions(Build.VERSION.SDK_INT)
                    }
                    var permissionsGranted by remember {
                        mutableStateOf(
                            requiredPermissions.all {
                                ContextCompat.checkSelfPermission(this, it) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
                        )
                    }
                    val blePermissions = remember { PermissionsManager.requiredBlePermissions(Build.VERSION.SDK_INT) }
                    fun bleNow() = blePermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
                    var bleGranted by remember { mutableStateOf(bleNow()) }
                    var bleRationaleSeen by rememberSaveable { mutableStateOf(false) }
                    var bleDeniedPermanently by rememberSaveable { mutableStateOf(false) }
                    var bleQuietDenials by rememberSaveable { mutableStateOf(0) }  // consecutive denied-without-rationale results
                    var bleLaunchedAt by remember { mutableStateOf(0L) }
                    var bleLaunchFromTap by remember { mutableStateOf(false) }
                    fun openAppSettings() = startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) bleGranted = bleNow() }
                        lifecycleOwner.lifecycle.addObserver(obs)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        permissionsGranted = requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
                        bleGranted = bleNow()
                        val rationale = blePermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
                        bleRationaleSeen = bleRationaleSeen || rationale
                        val quietDenial = !bleGranted && !rationale
                        val noDialog = quietDenial && SystemClock.elapsedRealtime() - bleLaunchedAt < BLE_AUTO_DENY_MS
                        bleQuietDenials = if (quietDenial) bleQuietDenials + 1 else 0
                        // Permanent after an explicit second "Don't allow" (rationale seen, now gone), after a dialog-less
                        // denial, or after two quiet denials in a row (backstop for a slow auto-deny; a user who really
                        // dismissed the dialog twice lands in Settings, which is still a working path to grant it).
                        bleDeniedPermanently = quietDenial && (bleRationaleSeen || noDialog || bleQuietDenials >= 2)
                        // The tap's prompt never appeared: give it a visible result now instead of on the next tap.
                        if (bleLaunchFromTap && (noDialog || bleQuietDenials >= 2)) openAppSettings()
                    }
                    fun requestBle(permissions: List<String>, fromTap: Boolean) {
                        bleLaunchedAt = SystemClock.elapsedRealtime()
                        bleLaunchFromTap = fromTap
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                    var permissionsAsked by rememberSaveable { mutableStateOf(false) }

                    // Nothing but the background until the stored acceptance is known - no Setup flash.
                    val showNotice by produceState<Boolean?>(initialValue = null) {
                        value = shouldShowNotice(app.legalAcceptanceStore.acceptedHash(), withContext(Dispatchers.IO) { bundledNoticeFingerprint(app) })
                    }
                    // Touching app.database runs the opener first, so the marker is final when read.
                    val failure by produceState<String?>(initialValue = null) {
                        value = withContext(Dispatchers.IO) { app.database; pendingUpgradeFailure(app) } ?: ""
                    }
                    val gate = showNotice
                    if (gate == null || failure == null) {
                        Box(Modifier.fillMaxSize().background(PmColors.Background))
                        return@Surface
                    }

                    SharedTransitionLayout {
                        val sharedScope = this
                        NavHost(
                            navController = navController,
                            startDestination = if (gate) Routes.NOTICE else if (failure!!.isNotEmpty()) Routes.UPGRADE_FAILED else Routes.SETUP,
                            enterTransition = { fadeIn(tween(PM_MORPH_MS, easing = PmMotionEasing)) },
                            exitTransition = { fadeOut(tween(PM_MORPH_MS, easing = PmMotionEasing)) },
                            popEnterTransition = { fadeIn(tween(PM_MORPH_MS, easing = PmMotionEasing)) },
                            popExitTransition = { fadeOut(tween(PM_MORPH_MS, easing = PmMotionEasing)) }
                        ) {
                            composable(Routes.NOTICE) {
                                val scope = rememberCoroutineScope()
                                val currentEntry by navController.currentBackStackEntryAsState()
                                NoticeScreen(
                                    onAccept = {
                                        scope.launch {
                                            // If the write fails the notice just returns next launch; still let the user in.
                                            try {
                                                app.legalAcceptanceStore.accept(withContext(Dispatchers.IO) { bundledNoticeFingerprint(app) })
                                            } catch (_: java.io.IOException) { }
                                            // singleTop: a double tap must not stack two Setups
                                            navController.navigate(if (failure!!.isNotEmpty()) Routes.UPGRADE_FAILED else Routes.SETUP) {
                                                popUpTo(Routes.NOTICE) { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    onDecline = { finishAffinity() },
                                    onOpenDoc = { routeFor(it)?.let { r -> navController.navigate(r) } },
                                    backEnabled = currentEntry?.destination?.route == Routes.NOTICE
                                )
                            }

                            composable(Routes.UPGRADE_FAILED) {
                                val scope = rememberCoroutineScope()
                                var message by remember { mutableStateOf<String?>(null) }
                                val saveLauncher = rememberLauncherForActivityResult(
                                    ActivityResultContracts.CreateDocument("application/octet-stream")
                                ) { uri ->
                                    if (uri != null) scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val file = failedBackupFile(app, failure!!)
                                                checkpointQuietly(file)
                                                contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                                            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.isSuccess
                                        }
                                        message = if (ok) "Backup file saved" else "Couldn't save the backup file"
                                        kotlinx.coroutines.delay(3000)
                                        message = null
                                    }
                                }
                                // Back leaves the marker, so this screen returns next launch; only Continue clears it.
                                androidx.activity.compose.BackHandler { finishAffinity() }
                                UpgradeFailedScreen(
                                    onSaveBackup = { saveLauncher.launch("pulse-metronome-history-backup.db") },
                                    onContinue = {
                                        clearUpgradeFailure(app)
                                        navController.navigate(Routes.SETUP) {
                                            popUpTo(Routes.UPGRADE_FAILED) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    },
                                    message = message
                                )
                            }

                            composable(Routes.SETUP) {
                                // Asked only once the user is past the legal notice, and once per launch as before.
                                LaunchedEffect(Unit) {
                                    if (!permissionsGranted && !permissionsAsked) {
                                        permissionsAsked = true
                                        requestBle(requiredPermissions.toList(), fromTap = false)
                                    }
                                }
                                val setupViewModel: SetupViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer { SetupViewModel(app.bleModule, app.setupValuesStore) }
                                    }
                                )
                                val settingsViewModel: SettingsViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer { SettingsViewModel(app.settingsRepository, app.database.sessionDao(), app.database.pulseSampleDao()) }
                                    }
                                )
                                val settings by settingsViewModel.uiState.collectAsState()

                                SetupScreen(
                                    viewModel = setupViewModel,
                                    bleModule = app.bleModule,
                                    startingBpm = settings.staticFallbackStartingBpm,
                                    onStartingBpmChanged = settingsViewModel::onStaticFallbackStartingBpmChanged,
                                    maxOffset = settings.maxOffset ?: 2,
                                    descentPattern = settings.descentPattern,
                                    bleGranted = bleGranted,
                                    onPermissionNoticeTapped = {
                                        if (bleDeniedPermanently) openAppSettings() else requestBle(blePermissions.toList(), fromTap = true)  // Bluetooth only; notifications aren't part of this notice
                                    },
                                    onNavigateToInfo = { navController.navigate(Routes.INFO) },
                                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                                    onNavigateToHistory = { navController.navigate(Routes.HISTORY) },
                                    onNavigateToFaq = { navController.navigate(Routes.FAQ) },
                                    onStartSession = { startMode ->
                                        if (startMode != StartMode.NO_PULSE && !bleGranted) {
                                            if (bleDeniedPermanently) openAppSettings() else requestBle(blePermissions.toList(), fromTap = true)
                                            return@SetupScreen
                                        }
                                        val setupState = setupViewModel.uiState.value
                                        val targetBpm = setupState.targetBpm
                                        val totalDurationMin = setupState.totalDurationMin
                                        val rampDurationMin = setupState.rampDurationMin
                                        val maxOffset = settings.maxOffset
                                        // Meter modes ignore Starting BPM but a blank/invalid in-memory value must not block Start; NO_PULSE is already guaranteed valid by startEnabled.
                                        val staticFallbackStartingBpm = settings.staticFallbackStartingBpm?.takeIf { it in 30..200 } ?: 70
                                        // Monitor hides Target and Ramp, so a blank hidden field must not block Start; the engine ignores them in Monitor.
                                        val monitor = setupState.monitor
                                        val configTarget = if (monitor) (targetBpm ?: 60) else targetBpm
                                        val configRamp = if (monitor) (rampDurationMin ?: totalDurationMin) else rampDurationMin
                                        if (configTarget != null && totalDurationMin != null &&
                                            configRamp != null && maxOffset != null
                                        ) {
                                            val config = SessionConfig(
                                                targetBpm = configTarget.toDouble(),
                                                totalDurationMin = totalDurationMin.toDouble(),
                                                rampDurationMin = configRamp.toDouble(),
                                                maxOffset = maxOffset.toDouble(),
                                                staticFallbackStartingBpm = staticFallbackStartingBpm.toDouble(),
                                                descentPattern = settings.descentPattern,
                                                monitor = monitor,
                                                monitorSignalLoss = settings.monitorSignalLoss,
                                                pacedSignalLoss = settings.pacedSignalLoss
                                            )
                                            // Must happen before LIVE_SESSION composes - the service's own clear in onStartCommand runs later on the main looper.
                                            SessionStateBus.clearEndedSessionId()
                                            val intent = SessionForegroundService.newIntent(
                                                app, config, settings.alarmType, startMode,
                                                saveToHistory = settings.historyEnabled
                                            )
                                            startForegroundService(intent)
                                            navController.navigate(Routes.LIVE_SESSION)
                                        }
                                    }
                                )
                            }

                            composable(Routes.LIVE_SESSION) {
                                val liveSessionViewModel: LiveSessionViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer { LiveSessionViewModel(app.bleModule) }
                                    }
                                )
                                LiveSessionScreen(
                                    viewModel = liveSessionViewModel,
                                    onSessionEnded = { navController.popBackStack(Routes.SETUP, inclusive = false) },
                                    onShowSummary = { id ->
                                        navController.navigate(Routes.summary(id, fromSession = true)) {
                                            popUpTo(Routes.LIVE_SESSION) { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(
                                Routes.SUMMARY,
                                arguments = listOf(
                                    navArgument("sessionId") { type = NavType.LongType },
                                    navArgument("fromSession") { type = NavType.BoolType; defaultValue = false }
                                )
                            ) { entry ->
                                val animScope = this
                                val sessionId = entry.arguments!!.getLong("sessionId")
                                val fromSession = entry.arguments!!.getBoolean("fromSession")
                                val summaryViewModel: SessionSummaryViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer { SessionSummaryViewModel(sessionId, app.database.sessionDao(), app.database.pulseSampleDao(), app.settingsRepository, app.appScope) }
                                    }
                                )
                                CompositionLocalProvider(LocalSharedScopes provides SharedScopes(sharedScope, animScope)) {
                                    SessionSummaryScreen(
                                        viewModel = summaryViewModel,
                                        sessionId = sessionId,
                                        fromSession = fromSession,
                                        onDone = { navController.popBackStack(Routes.SETUP, inclusive = false) },
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }

                            composable(Routes.HISTORY) {
                                val animScope = this
                                val historyViewModel: HistoryViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer { HistoryViewModel(app.database.sessionDao(), app.database.pulseSampleDao(), app.settingsRepository, app.appScope) }
                                    }
                                )
                                CompositionLocalProvider(LocalSharedScopes provides SharedScopes(sharedScope, animScope)) {
                                    HistoryScreen(
                                        viewModel = historyViewModel,
                                        onBack = { navController.popBackStack() },
                                        onOpenSession = { id -> navController.navigate(Routes.summary(id, fromSession = false)) }
                                    )
                                }
                            }

                            composable(Routes.FAQ) {
                                LegalReadingScreen(
                                    LegalDoc.FAQ,
                                    onBack = { navController.popBackStack() },
                                    onOpenDoc = { routeFor(it)?.let { r -> navController.navigate(r) } }
                                )
                            }

                            composable(Routes.INFO) {
                                LegalReadingScreen(
                                    LegalDoc.INFO,
                                    onBack = { navController.popBackStack() },
                                    onOpenDoc = { routeFor(it)?.let { r -> navController.navigate(r) } }
                                )
                            }
                            composable(Routes.PRIVACY) {
                                LegalReadingScreen(
                                    LegalDoc.PRIVACY,
                                    onBack = { navController.popBackStack() },
                                    onOpenDoc = { routeFor(it)?.let { r -> navController.navigate(r) } }
                                )
                            }

                            composable(Routes.SETTINGS) {
                                val settingsViewModel: SettingsViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer { SettingsViewModel(app.settingsRepository, app.database.sessionDao(), app.database.pulseSampleDao()) }
                                    }
                                )
                                SettingsScreen(viewModel = settingsViewModel, onBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DebugCapture.event("LIFE") { "MainActivity onStart" }
    }

    override fun onStop() {
        super.onStop()
        DebugCapture.event("LIFE") { "MainActivity onStop" }
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugCapture.event("LIFE") { "MainActivity onDestroy isFinishing=$isFinishing" }
    }
}
