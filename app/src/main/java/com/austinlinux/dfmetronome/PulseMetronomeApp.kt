package com.austinlinux.dfmetronome

import com.austinlinux.dfmetronome.ui.legal.DataStoreLegalAcceptanceStore
import com.austinlinux.dfmetronome.ui.legal.LegalAcceptanceStore
import com.austinlinux.dfmetronome.ui.setup.DataStoreSetupValuesStore
import com.austinlinux.dfmetronome.ui.setup.SetupValuesStore
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.austinlinux.dfmetronome.debug.CAPTURE_DIR
import com.austinlinux.dfmetronome.debug.DebugCapture
import com.austinlinux.dfmetronome.debug.ExitHistory
import com.austinlinux.dfmetronome.debug.PhoneStateMonitor
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.austinlinux.dfmetronome.ble.BleModule
import com.austinlinux.dfmetronome.ble.SharedPreferencesDeviceAddressStore
import com.austinlinux.dfmetronome.data.AppDatabase
import com.austinlinux.dfmetronome.data.IncompleteSessionRecovery
import com.austinlinux.dfmetronome.ui.history.cleanUpPendingDeletes
import com.austinlinux.dfmetronome.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PulseMetronomeApp : Application() {
    // App-scoped singleton, not per-session and not owned by SessionForegroundService -
    // see the plan's Global Constraints / Task 18's Ownership clarification. Lazily
    // constructed so it isn't touched until something actually needs it (Setup screen,
    // or SessionForegroundService reusing it on session start).
    val bleModule: BleModule by lazy {
        BleModule(this, SharedPreferencesDeviceAddressStore(this))
    }

    val database: AppDatabase by lazy { AppDatabase.instance(this) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val setupValuesStore: SetupValuesStore by lazy { DataStoreSetupValuesStore(this) }
    val legalAcceptanceStore: LegalAcceptanceStore by lazy { DataStoreLegalAcceptanceStore(this) }

    /** Outlives screens; used for deletes that must finish even if the user navigates away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var firstComponent: String? = null

    /** Records the first component (activity, service) created this process, and a LIFE line for each. */
    fun noteComponent(name: String) {
        if (firstComponent == null) firstComponent = name
        DebugCapture.event("LIFE") { "$name created (first component: $firstComponent)" }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugCapture.event("LIFE") { "trimMemory level=$level" }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DebugCapture.event("LIFE") { "lowMemory" }
    }

    override fun onCreate() {
        super.onCreate()
        startDebugCapture()
        // Must run before any session UI is shown, so a mid-session process death from a
        // PRIOR launch is reflected in History as INCOMPLETE rather than silently staying
        // "in progress" forever (Task 17).
        appScope.launch {
            // Unsaved sessions (History off) left over from a killed app go first, so they're deleted
            // rather than being marked INCOMPLETE by the recovery below.
            cleanUpPendingDeletes(
                settingsRepository.pendingDeleteIdsNow(),
                { database.sessionDao().deleteById(it) },
                { settingsRepository.clearPendingDeletes() }
            )
            IncompleteSessionRecovery(database.sessionDao()) { System.currentTimeMillis() }
                .markIncompleteSessions()
                .let { ids -> if (ids.isNotEmpty()) DebugCapture.event("APP") { "recovery marked incomplete: $ids" } }
        }
    }

    /** Debug builds only: on by default synchronously so no early line is lost, then the stored setting decides. */
    private fun startDebugCapture() {
        DebugCapture.init(this)
        if (!DebugCapture.isAvailable(this)) return
        val monitor = PhoneStateMonitor(this)
        DebugCapture.addEnabledListener { if (it) monitor.start() else monitor.stop() }
        // Synchronous, before any Activity or Service onCreate, so the first-component line is never dropped while DataStore loads.
        DebugCapture.setEnabled(true)
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val importance = runCatching { ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance }.getOrDefault(-1)
        DebugCapture.event("APP") {
            "start version=${info?.versionName}(${info?.let { PackageInfoCompat.getLongVersionCode(it) }}) " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "pid=${android.os.Process.myPid()} importance=$importance"
        }
        appScope.launch {
            ExitHistory.record(this@PulseMetronomeApp, File(filesDir, CAPTURE_DIR))
        }
        appScope.launch {
            settingsRepository.settings.map { it.debugCapture }.distinctUntilChanged().collect { DebugCapture.setEnabled(it) }
        }
        appScope.launch {
            delay(10_000)
            if (firstComponent == null) {
                DebugCapture.event("APP") { "no activity or service created 10 s after start (started for a receiver, provider or backup)" }
            }
        }
    }
}
