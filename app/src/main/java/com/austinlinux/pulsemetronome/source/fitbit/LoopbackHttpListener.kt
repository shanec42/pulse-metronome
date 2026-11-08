package com.austinlinux.pulsemetronome.source.fitbit

import com.austinlinux.pulsemetronome.debug.DebugCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Port the HR Service companion POSTs to. */
const val HR_SERVICE_PORT = 12345

private const val CLIENT_TIMEOUT_MS = 2_000
private const val LOG_LIMIT = 200

/** Outcome of [FitbitListener.start]. */
enum class StartResult { STARTED, ALREADY_RUNNING, PORT_BUSY }

/** Something that can be started and stopped to receive the Fitbit companion's requests. */
interface FitbitListener {
    fun start(): StartResult
    fun stop()
    val isRunning: Boolean
}

/** Loopback-only HTTP listener: serves one client at a time and hands each parsed BPM to [onRequest]. */
class LoopbackHttpListener(
    private val scope: CoroutineScope,
    private val port: Int = HR_SERVICE_PORT,
    private val onRequest: (HrRequest) -> Unit,
) : FitbitListener {
    private val lock = Any()
    private var server: ServerSocket? = null

    override val isRunning: Boolean get() = synchronized(lock) { server != null }

    internal val boundAddress: InetAddress? get() = synchronized(lock) { server?.inetAddress }
    internal val boundPort: Int get() = synchronized(lock) { server?.localPort ?: -1 }

    override fun start(): StartResult = synchronized(lock) {
        if (server != null) return StartResult.ALREADY_RUNNING
        val socket = try {
            ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: BindException) {
            DebugCapture.event("FITBIT") { "port $port busy: ${e.message}" }
            return StartResult.PORT_BUSY
        }
        server = socket
        // Also fires when the scope is already cancelled and the loop never runs.
        scope.launch(Dispatchers.IO) { acceptLoop(socket) }.invokeOnCompletion { loopEnded(socket) }
        StartResult.STARTED
    }

    /** Frees [socket] and clears state if the loop ended without [stop] having done so. */
    private fun loopEnded(socket: ServerSocket) {
        val unexpected = synchronized(lock) {
            (server === socket).also { if (it) server = null }
        }
        runCatching { socket.close() }
        if (unexpected) DebugCapture.event("FITBIT") { "listener loop ended unexpectedly" }
    }

    override fun stop() {
        val socket = synchronized(lock) { server.also { server = null } }
        runCatching { socket?.close() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: java.io.IOException) {
                break // closed by stop()
            }
            try {
                client.use { handle(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bad request or a throwing callback must not take the listener down.
                DebugCapture.event("FITBIT") { "handler failed: $e" }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = CLIENT_TIMEOUT_MS
            val recorder = RecordingInputStream(client.getInputStream())
            val request = readHrRequest(recorder)
            when (request) {
                is HrRequest.Bpm -> onRequest(request)
                HrRequest.Ping -> DebugCapture.event("FITBIT") { "ping" }
                HrRequest.Invalid -> DebugCapture.event("FITBIT") { "invalid request: ${recorder.preview()}" }
            }
            client.getOutputStream().apply {
                write(httpResponseFor(request))
                flush()
            }
        } catch (e: java.io.IOException) {
            DebugCapture.event("FITBIT") { "connection error: ${e.message}" }
        }
    }

    /** Keeps the first bytes read so a rejected request can be logged. */
    private class RecordingInputStream(input: InputStream) : FilterInputStream(input) {
        private val head = ByteArrayOutputStream()

        override fun read(): Int = super.read().also { if (it >= 0) keep(it) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { n -> for (i in 0 until n) keep(b[off + i].toInt() and 0xFF) }

        private fun keep(byte: Int) {
            if (head.size() < LOG_LIMIT) head.write(byte)
        }

        fun preview(): String = head.toString(Charsets.ISO_8859_1).replace("\r\n", " | ").take(LOG_LIMIT)
    }
}
