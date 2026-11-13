package com.austinlinux.pulsemetronome.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun fitbitChoice_roundTrips() {
        assertEquals(PulseSourceKind.FITBIT, store("FITBIT").first.choice.value)
        val (s, read) = store(null)
        s.set(PulseSourceKind.FITBIT)
        assertEquals("FITBIT", read())
        assertEquals(PulseSourceKind.FITBIT, s.choice.value)
    }

    @Test
    fun needsPhoneBluetooth_onlyOximeter() {
        assertTrue(PulseSourceKind.OXIMETER.needsPhoneBluetooth)
        assertFalse(PulseSourceKind.WATCH.needsPhoneBluetooth)
        assertFalse(PulseSourceKind.FITBIT.needsPhoneBluetooth)
    }

    @Test
    fun stalenessMs_fitbitTenSeconds_othersFour() {
        assertEquals(10_000L, PulseSourceKind.FITBIT.stalenessMs)
        assertEquals(4_000L, PulseSourceKind.OXIMETER.stalenessMs)
        assertEquals(4_000L, PulseSourceKind.WATCH.stalenessMs)
    }
}
