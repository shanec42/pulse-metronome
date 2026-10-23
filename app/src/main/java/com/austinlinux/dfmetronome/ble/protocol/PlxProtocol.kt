package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID

/** IEEE 11073-20601 16-bit SFLOAT; null for NaN, NRes, reserved and ±infinity. */
internal fun sfloat(raw: Int): Double? {
    when (raw) { 0x07FF, 0x0800, 0x0801, 0x07FE, 0x0802 -> return null }
    var mantissa = raw and 0x0FFF
    if (mantissa and 0x0800 != 0) mantissa -= 0x1000
    var exponent = (raw shr 12) and 0x0F
    if (exponent and 0x08 != 0) exponent -= 0x10
    return mantissa * Math.pow(10.0, exponent.toDouble())
}

/**
 * Bluetooth SIG Pulse Oximeter Service, continuous measurement only (untested on hardware).
 *
 * KNOWN RISK (diagnosis note, see docs/CREATION.md §12): the PLX spec allows a continuous
 * measurement "typically" every 1-4 s, but the engine declares signal lost after
 * STALENESS_TIMEOUT_MS (4 s) without a reading. A device sending at the slow end can flicker
 * between "Signal lost" and readings. Symptom: a PLX device whose Live screen flashes
 * "Signal lost · reconnecting" while OximeterRaw shows records arriving every ~4 s.
 */
object PlxProtocol : OximeterProtocol {
    override val id = ProtocolId.PLX
    override val tested = false
    val SERVICE: UUID = uuid16(0x1822)
    override val advertisedServiceUuids: Set<UUID> get() = setOf(SERVICE)
    override val notifyCharacteristic: UUID = uuid16(0x2A5F)   // PLX Continuous Measurement
    override val writeCharacteristic: UUID? = null
    override fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>) = SERVICE in serviceUuids
    override fun matchesGatt(characteristicUuids: Set<UUID>) = notifyCharacteristic in characteristicUuids
    override fun newDecoder(): FrameDecoder {
        val throttle = ReadingThrottle()
        return FrameDecoder { bytes, nowMs -> decodeRecord(bytes, nowMs, throttle) }
    }

    /** One notification = one record: flags, SpO2PR-Normal, then optional fields by flag bit. */
    internal fun decodeRecord(b: ByteArray, nowMs: Long, throttle: ReadingThrottle): List<OximeterEvent> {
        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        if (b.size < 5) return emptyList()
        val flags = b[0].toInt() and 0xFF
        val spo2 = sfloat(u16(1)); val pr = sfloat(u16(3))
        var i = 5
        if (flags and 0x01 != 0) i += 4   // SpO2PR-Fast
        if (flags and 0x02 != 0) i += 4   // SpO2PR-Slow
        if (flags and 0x04 != 0) i += 2   // Measurement Status
        val events = mutableListOf<OximeterEvent>()
        if (flags and 0x08 != 0 && i + 3 <= b.size) {   // Device and Sensor Status (u24)
            val status = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16)
            events += OximeterEvent.SensorOff(status and (1 shl 11) != 0 || status and (1 shl 15) != 0)
        }
        if (throttle.allow(nowMs)) events += OximeterEvent.Reading(pr?.let { Math.round(it).toInt() }, spo2?.let { Math.round(it).toInt() }, null, nowMs)
        return events
    }
}
