package com.austinlinux.dfmetronome.ui.history

/** Sessions History should list: everything except unsaved (pending) and just-swiped (hidden) ids. */
fun <T> visibleSessions(all: List<T>, idOf: (T) -> Long, pending: Set<Long>, hidden: Set<Long>): List<T> =
    all.filter { idOf(it) !in pending && idOf(it) !in hidden }

/** Only one swipe-delete at a time: no swiping while an Undo countdown is running. */
fun canSwipe(pendingSwipeDeleteId: Long?): Boolean = pendingSwipeDeleteId == null

/** A Summary reached straight from a session recorded with History off deletes that session when left. */
fun shouldDeleteOnLeave(fromSession: Boolean, sessionId: Long, pending: Set<Long>): Boolean =
    fromSession && sessionId in pending

/** Deletes sessions left over from a previous launch (History off, app killed before the Summary was left). */
suspend fun cleanUpPendingDeletes(pending: Set<Long>, deleteById: suspend (Long) -> Unit, clear: suspend () -> Unit) {
    if (pending.isEmpty()) return
    pending.forEach { deleteById(it) }
    clear()
}

/** Adds an id to the persisted string-set of pending deletes (idempotent). */
fun withPendingId(stored: Set<String>, id: Long): Set<String> = stored + id.toString()

/** Removes an id from the persisted string-set of pending deletes (idempotent). */
fun withoutPendingId(stored: Set<String>, id: Long): Set<String> = stored - id.toString()
