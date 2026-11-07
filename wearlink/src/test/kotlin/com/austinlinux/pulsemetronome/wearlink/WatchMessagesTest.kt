package com.austinlinux.pulsemetronome.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class WatchMessagesTest {
    @Test
    fun roundTrip_everyMessageType() {
        val messages = listOf(
            WatchMessage.Start("s1"),
            WatchMessage.Ack("s1", true, null),
            WatchMessage.Ack("s1", false, CantStartReason.NO_PERMISSION),
            WatchMessage.Stop("s1"),
            WatchMessage.Sample("s1", 72),
            WatchMessage.Status("s1", WatchStatus.CONTACT_LOST),
            WatchMessage.Heartbeat("s1"),
        )
        for (m in messages) {
            assertEquals(m, decodeWatchMessage(m.path(), m.encode()))
        }
    }

    @Test
    fun decode_unknownPath_null() {
        assertNull(decodeWatchMessage("/pm/nope", WatchMessage.Start("s1").encode()))
    }

    @Test
    fun decode_garbage_null() {
        assertNull(decodeWatchMessage(WearPaths.SAMPLE, ByteArray(0)))
        val rnd = Random(42)
        repeat(50) {
            val bytes = rnd.nextBytes(rnd.nextInt(1, 40))
            for (p in listOf(WearPaths.START, WearPaths.ACK, WearPaths.SAMPLE, WearPaths.STATUS)) {
                // Must not throw; random bytes are not a valid message.
                assertNull(decodeWatchMessage(p, bytes))
            }
        }
    }

    @Test
    fun sample_bpmOutOfRange_null() {
        for (bpm in listOf(19, 251, 0, -5)) {
            assertNull(decodeWatchMessage(WearPaths.SAMPLE, "sid=s1;bpm=$bpm".toByteArray()))
        }
        assertEquals(WatchMessage.Sample("s1", 20), decodeWatchMessage(WearPaths.SAMPLE, "sid=s1;bpm=20".toByteArray()))
        assertEquals(WatchMessage.Sample("s1", 250), decodeWatchMessage(WearPaths.SAMPLE, "sid=s1;bpm=250".toByteArray()))
    }
}
