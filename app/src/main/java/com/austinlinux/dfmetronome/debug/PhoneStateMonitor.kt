package com.austinlinux.dfmetronome.debug

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Logs battery, power and memory state every minute and on power-mode changes while capture is on. */
class PhoneStateMonitor(private val context: Context) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var receiver: BroadcastReceiver? = null

    private val beat = object : Runnable {
        override fun run() {
            heartbeat()
            handler?.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** Starts the heartbeat and receivers; no-op when already running. */
    @Synchronized
    fun start() {
        if (thread != null) return
        val t = HandlerThread("PMCapturePhone").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val name = intent.action?.substringAfterLast('.') ?: return
                DebugCapture.event("PHONE") { name }
                heartbeat()
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ContextCompat.registerReceiver(context, r, filter, null, h, ContextCompat.RECEIVER_NOT_EXPORTED) }
        h.post(beat)
    }

    /** Stops the heartbeat, unregisters the receiver and ends the thread. */
    @Synchronized
    fun stop() {
        val t = thread ?: return
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread = null
        t.quitSafely()
    }

    private fun heartbeat() {
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus.toString() else "n/a"
            val line = "battery=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}% charging=${bm.isCharging}" +
                " powerSave=${pm.isPowerSaveMode} idle=${pm.isDeviceIdleMode} thermal=$thermal" +
                " availMb=${mem.availMem / 1_048_576} lowMemory=${mem.lowMemory} thresholdMb=${mem.threshold / 1_048_576}" +
                " appPssKb=${Debug.getPss()}"
            DebugCapture.event("PHONE") { line }
        }
    }

    private companion object {
        const val HEARTBEAT_MS = 60_000L
    }
}
