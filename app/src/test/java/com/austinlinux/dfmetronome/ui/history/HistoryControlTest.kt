package com.austinlinux.dfmetronome.ui.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryControlTest {
    @Test fun `visible list drops pending and hidden ids and keeps order`() =
        assertEquals(listOf(5L, 2L), visibleSessions(listOf(5L, 4L, 3L, 2L), { it }, pending = setOf(4L), hidden = setOf(3L)))

    @Test fun `visible list unchanged when nothing pending or hidden`() =
        assertEquals(listOf(3L, 1L), visibleSessions(listOf(3L, 1L), { it }, emptySet(), emptySet()))

    @Test fun `no swiping while a swipe delete is pending`() {
        assertTrue(canSwipe(null))
        assertFalse(canSwipe(7L))
    }

    @Test fun `delete on leave only for a pending session arriving from a session`() {
        assertTrue(shouldDeleteOnLeave(fromSession = true, sessionId = 9L, pending = setOf(9L)))
        assertFalse(shouldDeleteOnLeave(fromSession = false, sessionId = 9L, pending = setOf(9L)))
        assertFalse(shouldDeleteOnLeave(fromSession = true, sessionId = 9L, pending = setOf(8L)))
    }

    @Test fun `launch clean-up deletes exactly the pending ids then clears`() = runBlocking {
        val deleted = mutableListOf<Long>(); var cleared = false
        cleanUpPendingDeletes(setOf(3L, 5L), { deleted += it }, { cleared = true })
        assertEquals(setOf(3L, 5L), deleted.toSet()); assertTrue(cleared)
    }

    @Test fun `pending id set add and remove are idempotent string-set operations`() {
        assertEquals(setOf("4", "9"), withPendingId(setOf("4"), 9L))
        assertEquals(setOf("4"), withPendingId(setOf("4"), 4L))
        assertEquals(setOf("9"), withoutPendingId(setOf("4", "9"), 4L))
        assertEquals(emptySet<String>(), withoutPendingId(emptySet(), 4L))
    }

    @Test fun `launch clean-up with nothing pending does nothing`() = runBlocking {
        var calls = 0
        cleanUpPendingDeletes(emptySet(), { calls++ }, { calls++ })
        assertEquals(0, calls)
    }
}
