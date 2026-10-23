package com.austinlinux.dfmetronome.ble.protocol

// Header for the SpO2/pulse-carrying message on a Wellue KS-60FW pulse oximeter, delivered
// over the generic Nordic UART Service (characteristic 6E400003-...) rather than the FS20F's
// dedicated 0xFFE4 characteristic - a different hardware generation/OEM rebrand than the
// FS20F/VTM-20F this app was originally built against (see BleModule's device-name comment).
// Confirmed against the same device family's public reference implementation
// (github.com/pythag/pc-60fw, an ESP32 BLE proxy for the Viatom PC-60FW, which uses this
// exact service/characteristic UUID pair).
internal val KS60FW_HEADER = byteArrayOf(0xAA.toByte(), 0x55, 0x0F, 0x08, 0x01)

/**
 * Parses raw BLE notification bytes from a KS-60FW's Nordic UART Service TX characteristic.
 * Unlike Fs20fParser/MessageFramer, this protocol needs no resync/framing buffer of its own -
 * the reference implementation simply scans each raw notification payload for a fixed 5-byte
 * header wherever it appears, then reads two fixed-offset data bytes after it. Frames not
 * carrying this header (this device also emits other message types - observed during capture
 * with different header bytes, e.g. AA 55 0F 07 02, likely waveform/status data) are ignored
 * by returning null, mirroring how Fs20fParser.parse returns null for a wave message.
 *
 * Field order is offset+5=SpO2, offset+6=pulse - taken directly from the reference
 * implementation's own source ("l_sat=pData[i+5]; l_hr=pData[i+6];"), not from its prose,
 * after an earlier version of this parser had the two swapped: the numeric values (96, 83)
 * matched the user's report regardless of which field was labeled which, so that mismatch
 * wasn't caught until the user separately confirmed which reading was actually pulse vs. SpO2
 * on the device's own display.
 *
 * The byte after 0x0F is the frame length (payload + checksum), and the last byte is a
 * CRC-8/MAXIM over the whole frame from 0xAA up to it - confirmed against all 1722 frames
 * captured from the user's KS-60FW on 2026-10-10 (lead from github.com/coelner/viatom_pc60fw).
 * A frame that is cut off or fails the checksum is dropped. Perfusion index is the byte four
 * after the function byte, in tenths of a percent (0 = none).
 */
class Ks60fwParser {
    fun parse(raw: ByteArray, timestampMs: Long): PulseReading? {
        val headerIndex = findHeader(raw) ?: return null
        val length = raw[headerIndex + 3].toInt() and 0xFF
        val frameEnd = headerIndex + 4 + length  // exclusive; the checksum is the last byte
        if (frameEnd > raw.size) return null
        if (crc8Maxim(raw, headerIndex, frameEnd - 1) != (raw[frameEnd - 1].toInt() and 0xFF)) return null

        val spo2Index = headerIndex + KS60FW_HEADER.size
        val pulseIndex = spo2Index + 1
        val piRaw = raw[headerIndex + 8].toInt() and 0xFF

        val spo2 = raw[spo2Index].toInt() and 0xFF
        val pulseRate = raw[pulseIndex].toInt() and 0xFF
        // The device reports pulse 0 while it is still acquiring a finger (e.g. right after
        // reconnecting); treating that as a reading recorded 0 BPM samples and dragged pacing down.
        if (pulseRate == 0) return PulseReading.NoSignal(timestampMs)

        return PulseReading.Valid(
            pulseRate = pulseRate,
            spo2 = spo2,
            perfusionIndex = if (piRaw == 0) null else piRaw / 10.0,
            timestampMs = timestampMs
        )
    }

    private fun findHeader(raw: ByteArray): Int? {
        if (raw.size < KS60FW_HEADER.size) return null
        for (i in 0..(raw.size - KS60FW_HEADER.size)) {
            var matches = true
            for (j in KS60FW_HEADER.indices) {
                if (raw[i + j] != KS60FW_HEADER[j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return null
    }
}

/** CRC-8/MAXIM (Dallas 1-Wire: reflected poly 0x8C, init 0) over [from, until) of [bytes]. */
internal fun crc8Maxim(bytes: ByteArray, from: Int, until: Int): Int {
    var crc = 0
    for (i in from until until) {
        crc = crc xor (bytes[i].toInt() and 0xFF)
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
    }
    return crc
}
