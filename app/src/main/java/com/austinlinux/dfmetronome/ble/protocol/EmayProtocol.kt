package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID

/** EMAY command framing: payload followed by sum(payload) & 0x7F (0xFF is silently ignored by the device). */
internal fun emayCommand(vararg payload: Int): ByteArray =
    ByteArray(payload.size + 1) { i -> if (i < payload.size) payload[i].toByte() else (payload.sum() and 0x7F).toByte() }

object EmayProtocol : OximeterProtocol {
    override val id = ProtocolId.EMAY
    override val tested = false
    val SERVICE: UUID = uuid16(0xFF12)
    override val advertisedServiceUuids: Set<UUID> get() = setOf(SERVICE)
    override val notifyCharacteristic: UUID = uuid16(0xFF02)
    override val writeCharacteristic: UUID = uuid16(0xFF01)
    override fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>) = name?.startsWith("SleepO2") == true || SERVICE in serviceUuids
    override fun matchesGatt(characteristicUuids: Set<UUID>) = notifyCharacteristic in characteristicUuids && writeCharacteristic in characteristicUuids
    // Command bytes from emay-sleepo2 1.0.6 (MIT): hello, device state, start realtime, get battery.
    override fun startCommands() = listOf(emayCommand(0x89), emayCommand(0x8E, 0x05), emayCommand(0x9B, 0x01), emayCommand(0x86))
    override val keepAlive = KeepAlive(emayCommand(0x9A), 1500L)   // heartbeat; the stream stops without it
    override fun stopCommands() = listOf(emayCommand(0x9B, 0x7F))
    override fun newDecoder(): FrameDecoder = EmayDecoder()
}

/** 8-byte frames EB 01 05 PR SpO2 7F 00 cks, cks = sum(bytes 0..6) & 0x7F; may arrive split or joined. */
internal class EmayDecoder : FrameDecoder {
    private val buf = ArrayList<Int>()
    private val throttle = ReadingThrottle()
    override fun decode(bytes: ByteArray, nowMs: Long): List<OximeterEvent> {
        bytes.forEach { buf.add(it.toInt() and 0xFF) }
        var latest: OximeterEvent.Reading? = null
        while (true) {
            val start = (0..buf.size - 3).firstOrNull { buf[it] == 0xEB && buf[it + 1] == 0x01 && buf[it + 2] == 0x05 }
            if (start == null) { if (buf.size > 2) buf.subList(0, buf.size - 2).clear(); break }
            if (start > 0) buf.subList(0, start).clear()
            if (buf.size < 8) break
            val f = buf.subList(0, 8).toList()
            // An invalid frame drops only its first byte, so a real header inside it isn't lost.
            if (f[5] != 0x7F || f[6] != 0x00 || (f.take(7).sum() and 0x7F) != f[7]) { buf.removeAt(0); continue }
            buf.subList(0, 8).clear()
            val pr = f[3].takeUnless { it == 0x00 || it == 0xFF }
            val spo2 = f[4].takeUnless { it == 0x00 || it == 0xFF }
            if (pr != null && pr !in 30..220) continue
            if (spo2 != null && spo2 !in 0..100) continue
            latest = OximeterEvent.Reading(pr, spo2, null, nowMs)
        }
        return listOfNotNull(latest?.takeIf { throttle.allow(nowMs) })
    }
}
