package com.austinlinux.pulsemetronome.service

import com.austinlinux.pulsemetronome.alarm.AlarmType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateBusTest {
    @After fun reset() { SessionStateBus.clearEndedSessionId(); SessionStateBus.clearAlarm() }

    @Test fun `clearing the alarm keeps the ended session id`() {
        SessionStateBus.publishEndedSessionId(7L)
        SessionStateBus.publishAlarm(AlarmType.SOUND)
        SessionStateBus.clearAlarm()
        assertEquals(7L, SessionStateBus.endedSessionId.value)
        assertNull(SessionStateBus.alarm.value)
    }

    @Test fun `consuming the ended id keeps the alarm ringing`() {
        SessionStateBus.publishEndedSessionId(7L)
        SessionStateBus.publishAlarm(AlarmType.VIBRATE_ONLY)
        SessionStateBus.clearEndedSessionId()
        assertNull(SessionStateBus.endedSessionId.value)
        assertEquals(AlarmType.VIBRATE_ONLY, SessionStateBus.alarm.value)
    }
}
