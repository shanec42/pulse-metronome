package com.austinlinux.dfmetronome.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFramerTest {
    // Transcribed from RAG/FS20F/sample_raw_data.hex (2 wave messages + 1 param message).
    private val wave1 = hexToBytes("FE 08 56 3A 00 09 13 B4")
    private val wave2 = hexToBytes("FE 08 56 35 00 08 14 AF")
    private val param1 = hexToBytes("FE 0A 55 00 4F 63 14 BF 39 1D")

    private fun hexToBytes(hex: String): ByteArray =
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `single complete message in one append returns one message`() {
        val framer = MessageFramer()
        framer.append(wave1)
        val messages = framer.extractMessages()
        assertEquals(1, messages.size)
        assertArrayEquals(wave1, messages[0])
    }

    @Test
    fun `message split across two notification chunks reassembles correctly`() {
        val framer = MessageFramer()
        framer.append(param1.copyOfRange(0, 4))
        assertTrue(framer.extractMessages().isEmpty())
        framer.append(param1.copyOfRange(4, param1.size))
        val messages = framer.extractMessages()
        assertEquals(1, messages.size)
        assertArrayEquals(param1, messages[0])
    }

    @Test
    fun `multiple messages concatenated in one notification all extracted in order`() {
        val framer = MessageFramer()
        framer.append(wave1 + wave2 + param1)
        val messages = framer.extractMessages()
        assertEquals(3, messages.size)
        assertArrayEquals(wave1, messages[0])
        assertArrayEquals(wave2, messages[1])
        assertArrayEquals(param1, messages[2])
    }

    @Test
    fun `garbage bytes before a valid start token are discarded on resync`() {
        val framer = MessageFramer()
        framer.append(byteArrayOf(0x01, 0x02, 0x03) + wave1)
        val messages = framer.extractMessages()
        assertEquals(1, messages.size)
        assertArrayEquals(wave1, messages[0])
    }

    @Test
    fun `buffer with no valid start token anywhere is cleared entirely`() {
        val framer = MessageFramer()
        framer.append(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        assertTrue(framer.extractMessages().isEmpty())
        framer.append(wave1)
        val messages = framer.extractMessages()
        assertEquals(1, messages.size)
    }

    @Test
    fun `byte stream split at arbitrary single-byte boundaries still reassembles all messages`() {
        val framer = MessageFramer()
        val fullStream = wave1 + param1 + wave2
        for (b in fullStream) framer.append(byteArrayOf(b))
        val messages = framer.extractMessages()
        assertEquals(3, messages.size)
        assertArrayEquals(wave1, messages[0])
        assertArrayEquals(param1, messages[1])
        assertArrayEquals(wave2, messages[2])
    }

    @Test
    fun `token byte appearing inside wave payload data does not cause false resync`() {
        val waveWithEmbeddedFe = byteArrayOf(
            0xFE.toByte(), 0x08, 0x56, 0xFE.toByte(), 0x00, 0x09, 0x13, 0xB4.toByte()
        )
        val framer = MessageFramer()
        framer.append(waveWithEmbeddedFe + param1)
        val messages = framer.extractMessages()
        assertEquals(2, messages.size)
        assertArrayEquals(waveWithEmbeddedFe, messages[0])
        assertArrayEquals(param1, messages[1])
    }

    @Test
    fun `a notification chunk ending in a lone start token is not lost when extracted eagerly`() {
        // Simulates BLE notification chunk boundaries: extractMessages() is called after
        // EVERY append (as BleModule does per-notification in Task 11), not only once at
        // the end - a buffer ending in a lone TOKEN_START byte (no length byte received
        // yet) must be preserved across that call, not wiped.
        val framer = MessageFramer()
        framer.append(wave1)
        assertEquals(1, framer.extractMessages().size)

        // First chunk of param1 ends exactly on the start token with nothing after it yet.
        framer.append(byteArrayOf(param1[0]))  // just 0xFE, buffer now holds only [0xFE]
        assertTrue(framer.extractMessages().isEmpty())  // must NOT clear the lone 0xFE

        // Rest of the message arrives in a later chunk.
        framer.append(param1.copyOfRange(1, param1.size))
        val messages = framer.extractMessages()
        assertEquals(1, messages.size)
        assertArrayEquals(param1, messages[0])
    }
}
