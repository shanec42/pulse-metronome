package com.austinlinux.dfmetronome.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fs20fParserTest {
    private val parser = Fs20fParser()
    private fun hexToBytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `valid param message parses pulse rate spo2 and perfusion index`() {
        // Real captured bytes from RAG/FS20F/sample_raw_data.hex: "FE 0A 55 00 4F 63 14 BF 39 1D".
        // raw[3]=0x00 (high), raw[4]=0x4F (low) -> pulse toSignedShort(0x4F,0x00) = 79.
        // raw[5]=0x63 (99) -> spo2. raw[6]=0x14 (high), raw[7]=0xBF (low) ->
        // PI raw = toSignedShort(low=0xBF, high=0x14) = 5311 -> perfusionIndex = 5.311.
        // (Verified directly against RAG/FS20F/sample_raw_data.hex and parser.py's own
        // `pi = to_signed_short(raw[7], raw[6])` mapping before writing this assertion.)
        val raw = hexToBytes("FE 0A 55 00 4F 63 14 BF 39 1D")
        val result = parser.parse(raw, timestampMs = 1000L)
        assertTrue(result is PulseReading.Valid)
        val valid = result as PulseReading.Valid
        assertEquals(79, valid.pulseRate)
        assertEquals(99, valid.spo2)
        assertEquals(5.311, valid.perfusionIndex!!, 0.0001)
        assertEquals(1000L, valid.timestampMs)
    }

    @Test
    fun `pulse rate sentinel 511 yields NoSignal not a literal 511 bpm reading`() {
        // Array positions: raw[0..2]=header, raw[3]=high byte of pulse rate,
        // raw[4]=low byte, raw[5]=spo2, raw[6]=high byte of PI, raw[7]=low byte of PI,
        // raw[8..9]=counter/unknown. toSignedShort(low=raw[4], high=raw[3]) per parser:
        // raw[3]=0x01 (high), raw[4]=0xFF (low) -> 0x01FF = 511 = INVALID_PR.
        val raw = byteArrayOf(
            0xFE.toByte(), 0x0A, 0x55,
            0x01, 0xFF.toByte(),
            0x63,
            0x14, 0x00,
            0x00, 0x00
        )
        val result = parser.parse(raw, timestampMs = 2000L)
        assertTrue(result is PulseReading.NoSignal)
        assertEquals(2000L, (result as PulseReading.NoSignal).timestampMs)
    }

    @Test
    fun `spo2 sentinel 127 yields null spo2 but still a valid reading if pulse rate is valid`() {
        // raw[3]=0x00, raw[4]=0x4F -> pulse rate 79 (valid). raw[5]=127 -> INVALID_SPO2.
        val raw = byteArrayOf(
            0xFE.toByte(), 0x0A, 0x55,
            0x00, 0x4F,
            127.toByte(),
            0x14, 0xBF.toByte(),
            0x00, 0x00
        )
        val result = parser.parse(raw, timestampMs = 3000L) as PulseReading.Valid
        assertEquals(79, result.pulseRate)
        assertNull(result.spo2)
    }

    @Test
    fun `perfusion index sentinel zero yields null PI without dividing`() {
        // raw[3]=0x00, raw[4]=0x4F -> pulse rate 79 (valid). raw[5]=99 -> valid spo2.
        // raw[6]=0x00, raw[7]=0x00 -> piRaw = toSignedShort(low=0x00, high=0x00) = 0 = INVALID_PI.
        val raw = byteArrayOf(
            0xFE.toByte(), 0x0A, 0x55,
            0x00, 0x4F,
            99.toByte(),
            0x00, 0x00,
            0x00, 0x00
        )
        val result = parser.parse(raw, timestampMs = 4000L) as PulseReading.Valid
        assertEquals(79, result.pulseRate)
        assertNull(result.perfusionIndex)
    }

    @Test
    fun `wave message parses sensor off bit true when bit 1 of byte 4 set`() {
        val raw = byteArrayOf(0xFE.toByte(), 0x08, 0x56, 0x1F, 0x02, 0x05, 0x22, 0xA4.toByte())
        val wave = parser.parseWave(raw)
        assertTrue(wave.sensorOff)
    }

    @Test
    fun `wave message parses sensor off bit false when bit 1 of byte 4 clear`() {
        val raw = hexToBytes("FE 08 56 3A 00 09 13 B4")
        val wave = parser.parseWave(raw)
        assertEquals(false, wave.sensorOff)
    }

    @Test(expected = ParseException::class)
    fun `unknown message type byte throws ParseException`() {
        val raw = byteArrayOf(0xFE.toByte(), 0x08, 0x99.toByte(), 0, 0, 0, 0, 0)
        parser.parse(raw, timestampMs = 5000L)
    }

    @Test(expected = ParseException::class)
    fun `message shorter than minimum length throws ParseException`() {
        val raw = byteArrayOf(0xFE.toByte(), 0x08, 0x56)
        parser.parse(raw, timestampMs = 6000L)
    }
}
