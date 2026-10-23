package com.austinlinux.dfmetronome.ui.setup

/**
 * Counts user-started scans in a row that gave no choice (auto-connected to the only meter heard,
 * or found none): [needed] of them within [windowMs] means the wanted meter may need a restart.
 * A scan that shows the picker resets the count; firing also resets it.
 */
class NoChoiceStreak(private val needed: Int = 2, private val windowMs: Long = 60_000L) {
    private val times = ArrayDeque<Long>()

    fun record(noChoice: Boolean, nowMs: Long): Boolean {
        if (!noChoice) { times.clear(); return false }
        times.addLast(nowMs)
        while (times.size > needed) times.removeFirst()
        if (times.size == needed && nowMs - times.first() <= windowMs) {
            times.clear()
            return true
        }
        return false
    }
}
