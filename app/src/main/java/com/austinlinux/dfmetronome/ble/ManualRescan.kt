package com.austinlinux.dfmetronome.ble

enum class ManualRescanAction { NO_OP, SCAN, CLOSE_THEN_SCAN }

/** Decides what a user-initiated "Scan now" does given current BLE state. */
fun manualRescanAction(connected: Boolean, scanOpen: Boolean, gattPending: Boolean): ManualRescanAction = when {
    connected || scanOpen -> ManualRescanAction.NO_OP
    // A pending autoConnect attempt can wait indefinitely; the user asking to rescan is the
    // signal to abandon it. Closing first keeps the one-GATT-client-at-a-time invariant.
    gattPending -> ManualRescanAction.CLOSE_THEN_SCAN
    else -> ManualRescanAction.SCAN
}

// A connect attempt older than this is treated as stuck, so the user is offered a restart.
const val CONNECT_RETRY_AFTER_MS: Long = 20_000L

enum class ScanPillState { IDLE, SCANNING, CONNECTING, RETRY }

/** What the waiting screen's Scan now pill shows for the current scan / connect timestamps. */
fun scanPillState(scanningSince: Long?, connectingSince: Long?, nowMs: Long): ScanPillState = when {
    scanningSince != null -> ScanPillState.SCANNING
    connectingSince == null -> ScanPillState.IDLE
    nowMs - connectingSince < CONNECT_RETRY_AFTER_MS -> ScanPillState.CONNECTING
    else -> ScanPillState.RETRY
}

/** Fraction (0..1) of the scan window elapsed, so a pill can resume a fill already in progress. */
fun scanFillFraction(sinceMs: Long, nowMs: Long, windowMs: Long = SCAN_WINDOW_MS): Float =
    ((nowMs - sinceMs).toFloat() / windowMs).coerceIn(0f, 1f)
