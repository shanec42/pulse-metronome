package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID

sealed class GattOp {
    data class EnableNotify(val characteristic: UUID) : GattOp()
    class Write(val characteristic: UUID, val bytes: ByteArray) : GattOp()
}

/** Android allows one outstanding GATT operation; this hands them out one at a time. */
class GattOpQueue {
    private val pending = ArrayDeque<GattOp>()
    var inFlight: GattOp? = null; private set
    /** Adds [op]; returns it if it should start now (nothing in flight), else null. */
    fun enqueue(op: GattOp): GattOp? { pending.addLast(op); return if (inFlight == null) next() else null }
    /** The in-flight op finished (successfully or not); returns the next to start, if any. */
    fun complete(): GattOp? { inFlight = null; return next() }
    /** Drops everything (disconnect/close). */
    fun clear() { pending.clear(); inFlight = null }
    val isIdle: Boolean get() = inFlight == null && pending.isEmpty()
    private fun next(): GattOp? = pending.removeFirstOrNull()?.also { inFlight = it }
}

/** The ops to run after service discovery: enable notifications, then the protocol's start commands in order.
 *  Start commands are dropped when the protocol declares no write characteristic (a misdeclared protocol, not a crash). */
fun startupOps(protocol: OximeterProtocol): List<GattOp> {
    val writeChar = protocol.writeCharacteristic
    val starts = if (writeChar == null) emptyList() else protocol.startCommands().map { GattOp.Write(writeChar, it) }
    return listOf<GattOp>(GattOp.EnableNotify(protocol.notifyCharacteristic)) + starts
}
