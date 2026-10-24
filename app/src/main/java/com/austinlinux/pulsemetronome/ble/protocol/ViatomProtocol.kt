package com.austinlinux.pulsemetronome.ble.protocol

import java.util.UUID

/** CRC-8 (poly 0x07, init 0, not reflected) - the O2Ring algorithm in RAG/wellue-o2ring-protocol. */
internal fun crc8Viatom(bytes: List<Int>): Int {
    var crc = 0
    for (b in bytes) { crc = crc xor b; repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF } }
    return crc
}

/** Request frame: AA cmd ~cmd block(u16 LE)=0 length(u16 LE)=0 crc. */
internal fun viatomRequest(cmd: Int): ByteArray {
    val body = listOf(0xAA, cmd, cmd xor 0xFF, 0, 0, 0, 0)
    return (body + crc8Viatom(body)).map { it.toByte() }.toByteArray()
}

object ViatomProtocol : OximeterProtocol {
    override val id = ProtocolId.VIATOM
    override val tested = false
    val SERVICE: UUID = UUID.fromString("14839ac4-7d7e-415c-9a42-167340cf2339")
    override val advertisedServiceUuids: Set<UUID> get() = setOf(SERVICE)
    override val notifyCharacteristic: UUID = UUID.fromString("0734594a-a8e7-4b1a-a6b1-cd5243059a57")
    override val writeCharacteristic: UUID = UUID.fromString("8b00ace7-eb0b-49b0-bbe9-9aee0a26e1a3")
    private val namePatterns = listOf(Regex("^O2Ring"), Regex("^O2 "), Regex("^Checkme O2"), Regex("^PO\\d"))
    override fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>) =
        SERVICE in serviceUuids || (name != null && namePatterns.any { it.containsMatchIn(name) })
    override fun matchesGatt(characteristicUuids: Set<UUID>) = notifyCharacteristic in characteristicUuids && writeCharacteristic in characteristicUuids
    override val keepAlive = KeepAlive(viatomRequest(0x17), 1000L)   // the live-values poll
    override fun newDecoder(): FrameDecoder = ViatomDecoder()
}

/** Responses: 55 cmd ~cmd block(u16) len(u16 LE) payload crc, possibly split across notifications. */
internal class ViatomDecoder : FrameDecoder {
    private val buf = ArrayList<Int>()
    private val throttle = ReadingThrottle()
    override fun decode(bytes: ByteArray, nowMs: Long): List<OximeterEvent> {
        bytes.forEach { buf.add(it.toInt() and 0xFF) }
        val events = mutableListOf<OximeterEvent>()
        while (true) {
            val start = buf.indexOf(0x55)
            if (start < 0) { buf.clear(); break }
            if (start > 0) buf.subList(0, start).clear()
            if (buf.size < 7) break
            if (buf[2] != (buf[1] xor 0xFF)) { buf.removeAt(0); continue }   // not a real header
            val len = buf[5] or (buf[6] shl 8)
            if (len > 64) { buf.removeAt(0); continue }   // a false header would otherwise hold the buffer
            val total = 7 + len + 1
            if (buf.size < total) break
            // On a bad CRC drop only the header byte, so a real frame inside the span isn't lost.
            if (crc8Viatom(buf.subList(0, total - 1)) != buf[total - 1]) { buf.removeAt(0); continue }
            val frame = buf.subList(0, total).toList(); buf.subList(0, total).clear()
            // Replies carry a status, not the request's command: 00 FF = OK, 01 FE = error
            // (observed reply 55 00 FF 00 00 0D 00 <13 bytes> 5A, docs/OXIMETER-PROTOCOLS.md §4.3).
            // The app only ever sends 0x17, so every OK reply is a live-values reply.
            if (frame[1] != 0x00 || len < 8) continue
            val p = frame.subList(7, 7 + len)
            val spo2 = p[0].takeUnless { it == 0 || it == 255 }
            val hr = p[1].takeUnless { it == 0 || it == 255 }
            events += OximeterEvent.Battery(p[7])
            if (throttle.allow(nowMs)) events += OximeterEvent.Reading(hr, spo2, null, nowMs)
        }
        return events
    }
}
