package com.austinlinux.pulsemetronome.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceChoiceTest {
    private fun store(initial: String?): Pair<KeyValueSourceChoiceStore, () -> String?> {
        var stored = initial
        return KeyValueSourceChoiceStore({ stored }, { stored = it }) to { stored }
    }

    @Test
    fun `unknownStoredValue defaultsToOximeter`() {
        assertEquals(PulseSourceKind.OXIMETER, store("GARBAGE").first.choice.value)
        assertEquals(PulseSourceKind.OXIMETER, store(null).first.choice.value)
    }

    @Test
    fun `storedWatch isRead and set persists`() {
        assertEquals(PulseSourceKind.WATCH, store("WATCH").first.choice.value)
        val (s, read) = store(null)
        s.set(PulseSourceKind.WATCH)
        assertEquals("WATCH", read())
        assertEquals(PulseSourceKind.WATCH, s.choice.value)
    }
}
