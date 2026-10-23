package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID

object Fs20fProtocol : OximeterProtocol {
    override val id = ProtocolId.FS20F
    override val tested = true
    override val advertisedServiceUuids: Set<UUID> = setOf(uuid16(0xFFE0))
    override val notifyCharacteristic: UUID = uuid16(0xFFE4)
    override val writeCharacteristic: UUID? = null
    override fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>) =
        name?.startsWith("VTM 20F") == true || uuid16(0xFFE0) in serviceUuids
    override fun matchesGatt(characteristicUuids: Set<UUID>) = notifyCharacteristic in characteristicUuids

    override fun newDecoder(): FrameDecoder {
        val framer = MessageFramer(); val parser = Fs20fParser()
        return FrameDecoder { bytes, nowMs ->
            framer.append(bytes)
            framer.extractMessages().mapNotNull { raw ->
                try {
                    when (val r = parser.parse(raw, nowMs)) {
                        is PulseReading.Valid -> OximeterEvent.Reading(r.pulseRate, r.spo2, r.perfusionIndex, nowMs)
                        is PulseReading.NoSignal -> OximeterEvent.Reading(null, null, null, nowMs)
                        null -> OximeterEvent.SensorOff(parser.parseWave(raw).sensorOff)
                        else -> null
                    }
                } catch (e: ParseException) { null }
            }
        }
    }
}
