package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID

object BerryProtocol : OximeterProtocol {
    override val id = ProtocolId.BERRY
    override val tested = false
    val SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
    override val advertisedServiceUuids: Set<UUID> get() = setOf(SERVICE)
    override val notifyCharacteristic: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
    override val writeCharacteristic: UUID? = null   // the device's RX (49535343-8841-…) is never written
    override fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>) =
        name?.trimEnd('\u0000')?.startsWith("BerryMed") == true || SERVICE in serviceUuids
    override fun matchesGatt(characteristicUuids: Set<UUID>) = notifyCharacteristic in characteristicUuids
    override fun newDecoder(): FrameDecoder = BerryDecoder()
}

/** BCI 5-byte packets: header byte has bit 7 set, the four data bytes don't (resync on it). */
internal class BerryDecoder : FrameDecoder {
    private val packet = ArrayList<Int>(5)
    private val throttle = ReadingThrottle()
    private var confirmedAbsent: Boolean? = null   // last finger state reported
    private var candidate: Boolean? = null
    private var candidateCount = 0
    override fun decode(bytes: ByteArray, nowMs: Long): List<OximeterEvent> {
        val events = mutableListOf<OximeterEvent>()
        var latest: OximeterEvent.Reading? = null
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v and 0x80 != 0) { packet.clear(); packet.add(v); continue }
            if (packet.isEmpty()) continue            // waiting for a header
            packet.add(v)
            if (packet.size < 5) continue
            val d1 = packet[2]; val d2 = packet[3]; val d3 = packet[4]
            packet.clear()
            val fingerAbsent = d1 and 0x10 != 0
            // Debounce: report a finger-state change only once 3 consecutive packets agree, so a flapping bit can't flood the event flow.
            if (fingerAbsent == confirmedAbsent) { candidate = null; candidateCount = 0 }
            else {
                if (fingerAbsent == candidate) candidateCount++ else { candidate = fingerAbsent; candidateCount = 1 }
                if (candidateCount >= 3) { confirmedAbsent = fingerAbsent; candidate = null; candidateCount = 0; events += OximeterEvent.SensorOff(fingerAbsent) }
            }
            val pulseRaw = d2 or ((d1 and 0x40) shl 1)
            val pulse = if (fingerAbsent || pulseRaw == 255) null else pulseRaw
            val spo2 = if (d3 == 127) null else d3
            latest = OximeterEvent.Reading(pulse, spo2, null, nowMs)
        }
        latest?.let { if (throttle.allow(nowMs)) events += it }
        return events
    }
}
