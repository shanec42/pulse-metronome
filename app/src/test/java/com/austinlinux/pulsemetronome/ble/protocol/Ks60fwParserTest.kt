package com.austinlinux.pulsemetronome.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ks60fwParserTest {
    private val parser = Ks60fwParser()

    // Real bytes captured live from a Wellue KS-60FW over its Nordic UART Service TX
    // characteristic (6E400003-...). Field order verified against the pc-60fw reference
    // implementation's own source (not just its prose), which is authoritative here:
    // https://github.com/pythag/pc-60fw/blob/main/arduino-proxy/oximeter-bt-proxy/oximeter-bt-proxy.ino
    // "l_sat=pData[i+5]; l_hr=pData[i+6];" - offset+5 is SpO2 (sat), offset+6 is pulse (hr).
    // An earlier version of this parser had these swapped (matched the reported "96 83"
    // numeric values against the wrong field), caught when the user reported the app's
    // "pulse" reading was actually showing their oxygen percentage. Header
    // {0xAA, 0x55, 0x0F, 0x08, 0x01}; this captured frame's device-reported values were
    // SpO2=96 (offset+5=0x60), pulse=83 (offset+6=0x53).
    private val realCapturedFrame = byteArrayOf(
        0xAA.toByte(), 0x55, 0x0F, 0x08, 0x01,
        0x60, 0x53, 0x00, 0x67.toByte(), 0x00, 0xC0.toByte(), 0x51
    )

    @Test
    fun `real captured frame decodes to the pulse and spo2 shown on the device at capture time`() {
        val reading = parser.parse(realCapturedFrame, timestampMs = 1000L)
        assertTrue(reading is PulseReading.Valid)
        val valid = reading as PulseReading.Valid
        assertEquals(83, valid.pulseRate)
        assertEquals(96, valid.spo2)
        assertEquals(1000L, valid.timestampMs)
    }

    @Test
    fun `header found at a non-zero offset within a larger buffer still decodes correctly`() {
        // The reference implementation searches for the header ANYWHERE within the
        // notification payload, not only at offset 0 - simulate garbage/other-message
        // bytes preceding the real header.
        val padded = byteArrayOf(0x11, 0x22, 0x33) + realCapturedFrame
        val reading = parser.parse(padded, timestampMs = 2000L)
        assertTrue(reading is PulseReading.Valid)
        val valid = reading as PulseReading.Valid
        assertEquals(83, valid.pulseRate)
        assertEquals(96, valid.spo2)
    }

    @Test
    fun `buffer with no matching header returns null - not a pulse-carrying frame`() {
        // Real non-pulse frames seen from this device during capture (waveform/status
        // messages with different subtype bytes, e.g. AA 55 0F 07 02 ... and AA 55 F0 03 ...).
        val waveformFrame = byteArrayOf(
            0xAA.toByte(), 0x55, 0x0F, 0x07, 0x02, 0x28, 0x25, 0x23, 0x20, 0x1D, 0xCE.toByte()
        )
        assertNull(parser.parse(waveformFrame, timestampMs = 3000L))
    }

    @Test
    fun `buffer shorter than the header plus two data bytes returns null`() {
        val tooShort = byteArrayOf(0xAA.toByte(), 0x55, 0x0F, 0x08, 0x01, 0x60)
        assertNull(parser.parse(tooShort, timestampMs = 4000L))
    }

    @Test
    fun `pulse byte 0 is no signal - the device sends it while re-acquiring a finger`() {
        // Seen on-device 2026-10-08: right after the KS-60FW reconnected, pulse=0 frames were
        // accepted as real readings and recorded as a 0 BPM sample.
        val zeroPulse = withValidCrc(realCapturedFrame.copyOf().also { it[6] = 0x00 })
        val reading = parser.parse(zeroPulse, timestampMs = 6000L)
        assertEquals(PulseReading.NoSignal(6000L), reading)
    }

    /** Recomputes the trailing CRC-8/MAXIM after a test edits a frame's bytes. */
    private fun withValidCrc(frame: ByteArray): ByteArray =
        frame.also { it[it.size - 1] = crc8Maxim(it, 0, it.size - 1).toByte() }

    @Test
    fun `perfusion index is the byte four after the function byte, in tenths of a percent`() {
        // 0x67 = 103 -> 10.3 %. Confirmed position on the user's KS-60FW, 2026-10-10 capture.
        val reading = parser.parse(realCapturedFrame, timestampMs = 5000L) as PulseReading.Valid
        assertEquals(10.3, reading.perfusionIndex!!, 1e-9)
    }

    @Test
    fun `perfusion index 0 means none`() {
        val noPi = withValidCrc(realCapturedFrame.copyOf().also { it[8] = 0x00 })
        val reading = parser.parse(noPi, timestampMs = 5000L) as PulseReading.Valid
        assertNull(reading.perfusionIndex)
    }

    @Test
    fun `crc8 maxim matches the device's checksum on real captured frames`() {
        // All 1722 frames captured from the user's KS-60FW on 2026-10-10 matched CRC-8/MAXIM
        // over the whole frame from AA up to the byte before the checksum.
        assertEquals(0x51, crc8Maxim(realCapturedFrame, 0, realCapturedFrame.size - 1))
        val waveform = byteArrayOf(0xAA.toByte(), 0x55, 0x0F, 0x07, 0x02, 0x28, 0x25, 0x23, 0x20, 0x1D, 0xCE.toByte())
        assertEquals(0xCE, crc8Maxim(waveform, 0, waveform.size - 1))
    }

    @Test
    fun `a frame whose checksum does not match is dropped`() {
        val corrupted = realCapturedFrame.copyOf().also { it[5] = 0x61 }  // SpO2 changed, CRC kept
        assertNull(parser.parse(corrupted, timestampMs = 7000L))
    }

    @Test
    fun `a frame cut off before its checksum is dropped`() {
        val truncated = realCapturedFrame.copyOf(realCapturedFrame.size - 1)
        assertNull(parser.parse(truncated, timestampMs = 8000L))
    }

    @Test
    fun `a valid frame preceded by other bytes still passes the checksum`() {
        val padded = byteArrayOf(0x11, 0x22, 0x33) + realCapturedFrame
        assertTrue(parser.parse(padded, timestampMs = 9000L) is PulseReading.Valid)
    }
}
