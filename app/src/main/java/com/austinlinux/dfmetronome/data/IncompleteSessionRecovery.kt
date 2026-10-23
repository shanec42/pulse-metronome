package com.austinlinux.dfmetronome.data

class IncompleteSessionRecovery(
    private val sessionDao: SessionDao,
    private val clock: () -> Long
) {
    /** Marks unfinished sessions INCOMPLETE and returns their ids. */
    suspend fun markIncompleteSessions(): List<Long> {
        val incomplete = sessionDao.getIncompleteRows()
        val now = clock()
        incomplete.forEach { row ->
            sessionDao.update(row.copy(endTimestamp = now, endReason = EndReason.INCOMPLETE))
        }
        return incomplete.map { it.id }
    }
}
