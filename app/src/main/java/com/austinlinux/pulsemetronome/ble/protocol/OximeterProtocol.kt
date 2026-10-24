package com.austinlinux.pulsemetronome.ble.protocol

import java.util.UUID

enum class ProtocolId { FS20F, KS60FW, BERRY, PLX, EMAY, VIATOM }

data class KeepAlive(val command: ByteArray, val intervalMs: Long)

/** Turns raw notification bytes into events; may buffer across notifications. */
fun interface FrameDecoder { fun decode(bytes: ByteArray, nowMs: Long): List<OximeterEvent> }

sealed class OximeterEvent {
    /** pulse/spo2 null = sentinel or no finger for that metric. */
    data class Reading(val pulse: Int?, val spo2: Int?, val perfusionIndex: Double?, val atMs: Long) : OximeterEvent()
    data class SensorOff(val off: Boolean) : OximeterEvent()
    data class Battery(val percent: Int) : OximeterEvent()
}

/** One oximeter wire protocol: UUIDs and bytes only, no Android BLE types. */
interface OximeterProtocol {
    val id: ProtocolId
    val tested: Boolean
    val notifyCharacteristic: UUID
    val writeCharacteristic: UUID?
    fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>): Boolean
    fun matchesGatt(characteristicUuids: Set<UUID>): Boolean
    /** Service UUIDs this protocol's meters advertise; used as screen-off scan filters (name prefixes can't be). */
    val advertisedServiceUuids: Set<UUID> get() = emptySet()
    fun startCommands(): List<ByteArray> = emptyList()
    val keepAlive: KeepAlive? get() = null
    fun stopCommands(): List<ByteArray> = emptyList()
    fun newDecoder(): FrameDecoder
}

/** A reading with a pulse is Valid; one without is NoSignal (same mapping the parsers used). */
fun OximeterEvent.Reading.toPulseReading(): PulseReading =
    if (pulse != null) PulseReading.Valid(pulse, spo2, perfusionIndex, atMs) else PulseReading.NoSignal(atMs)

const val LOW_BATTERY_PERCENT = 20
fun isBatteryLow(percent: Int?): Boolean = percent != null && percent <= LOW_BATTERY_PERCENT

/** Expands a 16-bit Bluetooth short UUID to its full 128-bit form. */
fun uuid16(short: Int): UUID = UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))
