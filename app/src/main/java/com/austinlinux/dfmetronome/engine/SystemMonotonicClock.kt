package com.austinlinux.dfmetronome.engine

import android.os.SystemClock

class SystemMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
