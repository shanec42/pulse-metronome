package com.austinlinux.pulsemetronome.source.fitbit

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HrServiceProtocolTest {
    private fun parse(raw: String) = readHrRequest(ByteArrayInputStream(raw.toByteArray()))

    private fun post(body: String, contentLength: Int? = body.length, path: String = "/"): String {
        val cl = if (contentLength != null) "Content-Length: $contentLength\r\n" else ""
        return "POST $path HTTP/1.1\r\nHost: 127.0.0.1:12345\r\nContent-Type: application/json\r\n$cl\r\n$body"
    }

    /** Serves [head], then fails the test on any further read. */
    private class HeadOnlyStream(head: String) : InputStream() {
        private val inner = ByteArrayInputStream(head.toByteArray())
        override fun read(): Int = if (inner.available() > 0) inner.read() else throw AssertionError("body was read")
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (inner.available() > 0) inner.read(b, off, len) else throw AssertionError("body was read")
    }

    @Test fun parse_hrServicePost_returnsBpm() {
        assertEquals(HrRequest.Bpm(64), parse(post("""{"bpm":64}""", 10)))
    }

    @Test fun parse_spacedJson_returnsBpm() {
        assertEquals(HrRequest.Bpm(72), parse(post("""{ "bpm" : 72 }""")))
    }

    @Test fun parse_zero_returnsBpmZero() {
        assertEquals(HrRequest.Bpm(0), parse(post("""{"bpm":0}""")))
    }

    @Test fun parse_get_returnsPing() {
        assertEquals(HrRequest.Ping, parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n"))
    }

    @Test fun parse_negativeOrHuge_rejected() {
        assertEquals(HrRequest.Invalid, parse(post("""{"bpm":-5}""")))
        assertEquals(HrRequest.Invalid, parse(post("""{"bpm":400}""")))
    }

    @Test fun parse_oversizedBody_rejected() {
        val head = "POST / HTTP/1.1\r\nContent-Length: 5000\r\n\r\n"
        assertEquals(HrRequest.Invalid, readHrRequest(HeadOnlyStream(head)))
    }

    @Test fun parse_noContentLength_readsToEof() {
        assertEquals(HrRequest.Bpm(64), parse(post("""{"bpm":64}""", null)))
        assertEquals(HrRequest.Invalid, parse(post("x".repeat(MAX_BODY_BYTES + 1), null)))
    }

    @Test fun parse_bodyShorterThanContentLength_rejected() {
        assertEquals(HrRequest.Invalid, parse(post("""{"bpm":6""", 10)))
    }

    @Test fun parse_garbage_rejected() {
        assertEquals(HrRequest.Invalid, parse("hello\r\n\r\n"))
        assertEquals(HrRequest.Invalid, parse(""))
    }

    @Test fun parse_wrongPath_rejected() {
        assertEquals(HrRequest.Invalid, parse(post("""{"bpm":64}""", path = "/x")))
    }

    @Test fun parse_lowercaseMethodAndHeader_accepted() {
        assertEquals(HrRequest.Bpm(64), parse("post / HTTP/1.1\r\ncontent-length: 10\r\n\r\n{\"bpm\":64}"))
    }

    @Test fun response_bpm_is200Json() {
        val text = String(httpResponseFor(HrRequest.Bpm(64)))
        assertTrue(text.startsWith("HTTP/1.1 200"))
        assertTrue(text.contains("Content-Type: application/json"))
        assertTrue(text.contains("Connection: close"))
        assertTrue(text.endsWith("""{"bpm":64}"""))
    }

    @Test fun response_invalid_is400() {
        assertTrue(String(httpResponseFor(HrRequest.Invalid)).startsWith("HTTP/1.1 400"))
    }
}
