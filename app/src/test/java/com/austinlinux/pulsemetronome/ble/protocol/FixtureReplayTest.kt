package com.austinlinux.pulsemetronome.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureReplayTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing fixture $path" }.bufferedReader().readText()

    private fun render(e: OximeterEvent): String = when (e) {
        is OximeterEvent.Reading -> "Reading pulse=${e.pulse ?: "-"} spo2=${e.spo2 ?: "-"} pi=${e.perfusionIndex ?: "-"}"
        is OximeterEvent.SensorOff -> "SensorOff ${e.off}"
        is OximeterEvent.Battery -> "Battery ${e.percent}"
    }

    private fun replay(id: ProtocolId, dir: String, hexFile: String) {
        val decoder = ProtocolRegistry.byId(id).newDecoder()
        val lines = resource("/oximeter-fixtures/$dir/$hexFile").lines().filter { it.isNotBlank() }
        val rendered = lines.flatMap { line ->
            val bytes = line.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
            decoder.decode(bytes, 0L)
        }.map(::render)
        assertEquals(resource("/oximeter-fixtures/$dir/expected.txt").trim(), rendered.joinToString("\n"))
    }

    @Test fun `FS20F stream replays to the expected events`() = replay(ProtocolId.FS20F, "FS20F", "stream.hex")
    @Test fun `KS60FW frames replay to the expected events`() = replay(ProtocolId.KS60FW, "KS60FW", "frames.hex")
    // 60 notifications from the user's KS-60FW (2026-10-10), plus a no-finger frame and one
    // with a corrupted SpO2 byte that the checksum must reject.
    @Test fun `KS60FW captured session replays to the expected events`() = replay(ProtocolId.KS60FW, "KS60FW-session", "stream.hex")
    // Synthetic Berry packets derived from the BCI layout (not captured from a device).
    @Test fun `BERRY stream replays to the expected events`() = replay(ProtocolId.BERRY, "BERRY", "stream.hex")
    // Synthetic PLX record derived from the Bluetooth SIG spec (not captured from a device).
    @Test fun `PLX stream replays to the expected events`() = replay(ProtocolId.PLX, "PLX", "stream.hex")
    // Synthetic EMAY frame, split across two notifications, derived from the emay-sleepo2 reference (not captured).
    @Test fun `EMAY stream replays to the expected events`() = replay(ProtocolId.EMAY, "EMAY", "stream.hex")
    // Synthetic Viatom live-values response, split across two notifications (not captured from a device).
    @Test fun `VIATOM stream replays to the expected events`() = replay(ProtocolId.VIATOM, "VIATOM", "stream.hex")
}
