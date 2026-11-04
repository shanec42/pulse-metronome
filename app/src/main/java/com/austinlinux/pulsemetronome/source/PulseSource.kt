package com.austinlinux.pulsemetronome.source

import com.austinlinux.pulsemetronome.ble.TimedPulse
import com.austinlinux.pulsemetronome.ble.protocol.PulseReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What a session needs from a pulse provider (BLE oximeter today, watch later). */
interface PulseSource {
    val readings: Flow<PulseReading>
    val sensorOffState: Flow<Boolean>
    val connectionState: StateFlow<Boolean>
    val connectedDeviceName: StateFlow<String?>
    val batteryPercent: StateFlow<Int?>
    val latestValidPulse: StateFlow<TimedPulse?>

    /** Starts (or resumes) connecting for a session; a no-op if already connected. */
    fun beginSession()

    /** Retries connecting only if nothing is connected or pending. */
    fun retryIfIdle()

    /** Stops automatic reconnection when the session ends. */
    fun endSession()
}
