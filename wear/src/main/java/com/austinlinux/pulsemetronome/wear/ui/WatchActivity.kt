package com.austinlinux.pulsemetronome.wear.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import com.austinlinux.pulsemetronome.wear.service.SensingService
import com.austinlinux.pulsemetronome.wear.session.WatchUiState
import java.util.Date

/**
 * The watch app's only screen. Being resumed is what lets [com.austinlinux.pulsemetronome.wear.service.PhoneMessageListener]
 * start sensing for a phone request; the phone's deep link (pulsemetronome://start) just opens this and its Start resend does the rest.
 */
class WatchActivity : ComponentActivity() {
    private var ambient by mutableStateOf(false)
    private var hasHeart by mutableStateOf(false)
    private var notificationsAsked by mutableStateOf(false)
    private var hasNotifications by mutableStateOf(true)
    private var denied by mutableStateOf(false)
    private var stoppedPrompting by mutableStateOf(false)
    private var time by mutableStateOf("")

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) { ambient = true }
        override fun onExitAmbient() { ambient = false }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { time = currentTime() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        ContextCompat.registerReceiver(
            this, finishReceiver, IntentFilter(SensingService.ACTION_FINISH_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(this, tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            val service by SensingService.uiState.collectAsState()
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                refreshPermission()
                notificationsAsked = true // once asked, never nag again: the watch works without the notification
                denied = !hasHeart
                // After two denials Android stops prompting: shouldShowRequestPermissionRationale is false while denied.
                stoppedPrompting = denied && !shouldShowRequestPermissionRationale(heartPermission())
            }
            val state = when {
                service != WatchUiState.Ready -> service
                hasHeart && (hasNotifications || notificationsAsked) -> WatchUiState.Ready
                // Heart rate is granted but the Ongoing Activity's notification permission is not: Allow asks for it.
                hasHeart -> WatchUiState.NeedsPermission
                denied -> WatchUiState.PermissionDenied
                else -> WatchUiState.NeedsPermission
            }
            WatchScreen(state, time, ambient, stoppedPrompting) {
                if (stoppedPrompting) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                    )
                } else {
                    launcher.launch(permissionsToRequest())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        time = currentTime()
        refreshPermission() // also picks up a grant made in system settings
    }

    override fun onDestroy() {
        unregisterReceiver(finishReceiver)
        unregisterReceiver(tickReceiver)
        super.onDestroy()
    }

    private fun refreshPermission() {
        hasHeart = SensingService.hasHeartPermission(this)
        hasNotifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (hasHeart) {
            denied = false
            stoppedPrompting = false
        }
    }

    private fun currentTime(): String = DateFormat.getTimeFormat(this).format(Date())

    private fun heartPermission() =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    private fun permissionsToRequest(): Array<String> = buildList {
        if (!hasHeart) add(heartPermission())
        // The Ongoing Activity notification needs this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}
