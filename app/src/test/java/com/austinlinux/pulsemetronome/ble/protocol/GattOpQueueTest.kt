package com.austinlinux.pulsemetronome.ble.protocol

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOpQueueTest {
    private val c = uuid16(0xFF01)

    private object FakeProtocol : OximeterProtocol {
        val cmd1 = byteArrayOf(1); val cmd2 = byteArrayOf(2); val ka = byteArrayOf(9)
        override val id = ProtocolId.FS20F
        override val tested = false
        override val notifyCharacteristic: UUID = uuid16(0xFF02)
        override val writeCharacteristic: UUID? = uuid16(0xFF03)
        override fun matchesAdvertisement(name: String?, serviceUuids: Set<UUID>) = false
        override fun matchesGatt(characteristicUuids: Set<UUID>) = false
        override fun startCommands() = listOf(cmd1, cmd2)
        override val keepAlive: KeepAlive? get() = KeepAlive(ka, 1000L)
        override fun newDecoder() = FrameDecoder { _, _ -> emptyList() }
    }

    @Test fun `first op starts immediately and later ones wait their turn`() {
        val q = GattOpQueue()
        val a = GattOp.EnableNotify(c); val b = GattOp.Write(c, byteArrayOf(1)); val d = GattOp.Write(c, byteArrayOf(2))
        assertSame(a, q.enqueue(a)); assertNull(q.enqueue(b)); assertNull(q.enqueue(d))
        assertSame(b, q.complete()); assertSame(d, q.complete()); assertNull(q.complete()); assertTrue(q.isIdle)
    }
    @Test fun `clear drops queued and in-flight ops`() {
        val q = GattOpQueue()
        q.enqueue(GattOp.EnableNotify(c)); q.enqueue(GattOp.Write(c, byteArrayOf(1)))
        q.clear()
        assertTrue(q.isIdle); assertNull(q.complete())
    }
    @Test fun `startup enables notifications first then start commands in order, and a keep-alive waits behind them`() {
        val ops = startupOps(FakeProtocol)
        assertEquals(3, ops.size)
        assertEquals(GattOp.EnableNotify(FakeProtocol.notifyCharacteristic), ops[0])
        assertSame(FakeProtocol.cmd1, (ops[1] as GattOp.Write).bytes)
        assertSame(FakeProtocol.cmd2, (ops[2] as GattOp.Write).bytes)

        val q = GattOpQueue()
        assertSame(ops[0], q.enqueue(ops[0])); assertNull(q.enqueue(ops[1])); assertNull(q.enqueue(ops[2]))
        val keepAlive = GattOp.Write(FakeProtocol.writeCharacteristic!!, FakeProtocol.ka)
        assertNull(q.enqueue(keepAlive))
        assertSame(ops[1], q.complete())
        assertSame(ops[2], q.complete())
        assertSame(keepAlive, q.complete())
        assertNull(q.complete()); assertTrue(q.isIdle)
    }
    @Test fun `a protocol with start commands but no write characteristic gets only the notify op`() {
        val noWrite = object : OximeterProtocol by FakeProtocol {
            override val writeCharacteristic: UUID? = null
        }
        assertEquals(listOf<GattOp>(GattOp.EnableNotify(FakeProtocol.notifyCharacteristic)), startupOps(noWrite))
    }
}
