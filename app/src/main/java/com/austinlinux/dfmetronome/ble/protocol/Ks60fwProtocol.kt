package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID

object Ks60fwProtocol : OximeterProtocol {
    override val id = ProtocolId.KS60FW
    override val tested = true
    override val notifyCharacteristic: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    override val writeCharacteristic: UUID? = null
    override fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>) = name?.startsWith("KS-60FW") == true
    override fun matchesGatt(characteristicUuids: Set<UUID>) = notifyCharacteristic in characteristicUuids

    override fun newDecoder(): FrameDecoder {
        val parser = Ks60fwParser()
        return FrameDecoder { bytes, nowMs ->
            when (val r = parser.parse(bytes, nowMs)) {
                is PulseReading.Valid -> listOf(OximeterEvent.Reading(r.pulseRate, r.spo2, r.perfusionIndex, nowMs))
                is PulseReading.NoSignal -> listOf(OximeterEvent.Reading(null, null, null, nowMs))
                else -> emptyList()
            }
        }
    }
}
