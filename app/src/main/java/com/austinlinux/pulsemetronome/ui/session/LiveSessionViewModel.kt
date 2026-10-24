package com.austinlinux.pulsemetronome.ui.session

import androidx.lifecycle.ViewModel
import com.austinlinux.pulsemetronome.ble.BleModule
import com.austinlinux.pulsemetronome.engine.SessionState
import com.austinlinux.pulsemetronome.service.SessionStateBus
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin pass-through of SessionStateBus.state for the Compose screen - the UI layer has no
 * direct reference to the service-owned SessionEngine instance, per Task 16's design (the
 * bus is the only bridge between the foreground service and this screen). Also exposes the
 * shared BleModule's connectionState (bell-curve pacing redesign) so the AWAITING_START
 * waiting state can distinguish "Connecting…" from "Verifying pulse (n/3)".
 */
class LiveSessionViewModel(private val bleModule: BleModule) : ViewModel() {
    val scanInProgressSince: StateFlow<Long?> = bleModule.scanInProgressSince
    val connectingSince: StateFlow<Long?> = bleModule.connectingSince
    fun onScanNow() = bleModule.manualRescan()

    val state: StateFlow<SessionState?> = SessionStateBus.state
    val endedSessionId: StateFlow<Long?> = SessionStateBus.endedSessionId
    fun consumeEndedSessionId() = SessionStateBus.clearEndedSessionId()
    val connectionState: StateFlow<Boolean> = bleModule.connectionState
    val batteryPercent: StateFlow<Int?> = bleModule.batteryPercent
}
