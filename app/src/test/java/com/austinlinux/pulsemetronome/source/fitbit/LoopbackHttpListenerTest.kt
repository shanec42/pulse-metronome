package com.austinlinux.pulsemetronome.source.fitbit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LoopbackHttpListenerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = LinkedBlockingQueue<HrRequest>()
    private val sockets = mutableListOf<Socket>()
    private val listeners = mutableListOf<LoopbackHttpListener>()
    private var blocker: ServerSocket? = null

    @After
    fun tearDown() {
        listeners.forEach { it.stop() }
        sockets.forEach { runCatching { it.close() } }
        blocker?.close()
        scope.cancel()
    }

    private fun newListener(port: Int = 0) =
        LoopbackHttpListener(scope, port) { received.put(it) }.also { listeners += it }

    /** Sends a POST with [json] to the listener and returns the response's first line. */
    private fun post(listener: LoopbackHttpListener, json: String): String {
        val socket = Socket(loopback, listener.boundPort).also { sockets += it; it.soTimeout = 4_000 }
        val body = json.toByteArray()
        socket.getOutputStream().apply {
            write("POST / HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
        return socket.getInputStream().bufferedReader().readLine()
    }

    @Test
    fun start_bindsLoopbackOnly() {
        val listener = newListener()
        assertEquals(StartResult.STARTED, listener.start())
        assertTrue(listener.isRunning)
        assertTrue(listener.boundAddress!!.isLoopbackAddress)
    }

    @Test
    fun post_deliversBpm() {
        val listener = newListener()
        listener.start()
        val statusLine = post(listener, """{"bpm":64}""")
        assertEquals(HrRequest.Bpm(64), received.poll(2, TimeUnit.SECONDS))
        assertTrue(statusLine.startsWith("HTTP/1.1 200"))
    }

    @Test
    fun start_portInUse_reportsBusy() {
        val taken = ServerSocket(0, 50, loopback).also { blocker = it }
        val listener = newListener(taken.localPort)
        assertEquals(StartResult.PORT_BUSY, listener.start())
        assertFalse(listener.isRunning)
    }

    @Test
    fun stalledClient_doesNotBlockNextPost() {
        val listener = newListener()
        listener.start()
        sockets += Socket(loopback, listener.boundPort) // sends nothing
        post(listener, """{"bpm":70}""")
        assertEquals(HrRequest.Bpm(70), received.poll(4, TimeUnit.SECONDS))
    }

    @Test
    fun throwingCallback_doesNotStopNextPost() {
        var first = true
        val listener = LoopbackHttpListener(scope, 0) {
            if (first) { first = false; throw IllegalStateException("boom") }
            received.put(it)
        }.also { listeners += it }
        listener.start()
        runCatching { post(listener, """{"bpm":60}""") }
        post(listener, """{"bpm":61}""")
        assertEquals(HrRequest.Bpm(61), received.poll(2, TimeUnit.SECONDS))
        assertTrue(listener.isRunning)
    }

    @Test
    fun cancelledScope_leavesListenerStoppedAndPortFree() {
        val free = ServerSocket(0, 50, loopback).use { it.localPort }
        val dead = CoroutineScope(SupervisorJob()).also { it.cancel() }
        val listener = LoopbackHttpListener(dead, free) { }.also { listeners += it }
        listener.start()
        // The cancelled job completes on an IO thread, so wait (bounded) for the cleanup.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (listener.isRunning && System.nanoTime() < deadline) Thread.yield()
        assertFalse(listener.isRunning)
        ServerSocket(free, 50, loopback).close() // port was released
    }

    @Test
    fun stop_thenStartAgain_works() {
        val listener = newListener()
        assertEquals(StartResult.STARTED, listener.start())
        listener.stop()
        assertFalse(listener.isRunning)
        assertEquals(StartResult.STARTED, listener.start())
        post(listener, """{"bpm":88}""")
        assertEquals(HrRequest.Bpm(88), received.poll(2, TimeUnit.SECONDS))
    }
}
