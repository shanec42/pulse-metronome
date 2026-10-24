package com.austinlinux.pulsemetronome.ble.protocol

internal const val TOKEN_START: Int = 0xFE
internal const val TYPE_PO_PARAM: Int = 0x55
internal const val TYPE_PO_WAVE: Int = 0x56
internal const val LEN_PO_PARAM: Int = 10
internal const val LEN_PO_WAVE: Int = 8
internal const val INVALID_PR: Int = 511
internal const val INVALID_SPO2: Int = 127
internal const val INVALID_PI: Int = 0

/** to_signed_short(low, high) per RAG/FS20F/parser.py: little-endian signed 16-bit. */
internal fun toSignedShort(low: Int, high: Int): Int {
    val unsigned = (low and 0xFF) or ((high and 0xFF) shl 8)
    return unsigned.toShort().toInt()
}

/**
 * Public sealed type consumed by SessionEngine (Task 9) and BleModule (Task 11).
 * Connected/Disconnected are connection-level events emitted by BleModule itself
 * (never by the parser) — Connected fires on a successful GATT connection
 * (fresh connect or reconnect), and SessionEngine.onReconnected() is wired to it
 * so signal loss can clear after a reconnect (see Task 9/Task 16 wiring notes).
 */
sealed class PulseReading {
    data class Valid(
        val pulseRate: Int,
        val spo2: Int?,
        val perfusionIndex: Double?,
        val timestampMs: Long
    ) : PulseReading()

    data class NoSignal(val timestampMs: Long) : PulseReading()

    object Connected : PulseReading()

    object Disconnected : PulseReading()
}
