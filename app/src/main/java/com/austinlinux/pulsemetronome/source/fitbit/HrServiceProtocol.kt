package com.austinlinux.pulsemetronome.source.fitbit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Largest request header block accepted. */
const val MAX_HEADER_BYTES = 8 * 1024

/** Largest request body accepted. */
const val MAX_BODY_BYTES = 1024

private const val MAX_BPM = 250
private val BPM_PATTERN = Regex("\"bpm\"\\s*:\\s*(-?\\d+)")

/** What the Fitbit companion's HTTP request amounted to. */
sealed interface HrRequest {
    data class Bpm(val bpm: Int) : HrRequest
    object Ping : HrRequest
    object Invalid : HrRequest
}

/** Reads one HTTP/1.1 request from [input]; anything malformed, oversized or out of range is [HrRequest.Invalid]. */
fun readHrRequest(input: InputStream): HrRequest {
    val head = readHeaderBlock(input) ?: return HrRequest.Invalid
    val lines = head.split("\r\n")
    val requestLine = lines[0].split(" ")
    if (requestLine.size != 3 || requestLine[1] != "/") return HrRequest.Invalid

    when (requestLine[0].uppercase()) {
        "GET" -> return HrRequest.Ping
        "POST" -> Unit
        else -> return HrRequest.Invalid
    }

    val lengthValue = lines.drop(1)
        .firstOrNull { it.substringBefore(':').trim().equals("Content-Length", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
    val body = if (lengthValue == null) {
        readBody(input, MAX_BODY_BYTES + 1)
    } else {
        val length = lengthValue.toIntOrNull()
        if (length == null || length < 0 || length > MAX_BODY_BYTES) return HrRequest.Invalid
        readBody(input, length)?.takeIf { it.size == length } // a truncated body could parse as a wrong BPM
    } ?: return HrRequest.Invalid

    if (body.size > MAX_BODY_BYTES) return HrRequest.Invalid
    val bpm = BPM_PATTERN.find(String(body, Charsets.UTF_8))?.groupValues?.get(1)?.toIntOrNull()
        ?: return HrRequest.Invalid
    return if (bpm in 0..MAX_BPM) HrRequest.Bpm(bpm) else HrRequest.Invalid
}

/** Builds a full HTTP/1.1 response for [req]; the connection is always closed after it. */
fun httpResponseFor(req: HrRequest): ByteArray {
    val (status, body) = when (req) {
        is HrRequest.Bpm -> "200 OK" to """{"bpm":${req.bpm}}"""
        HrRequest.Ping -> "200 OK" to """{"status":"ok"}"""
        HrRequest.Invalid -> "400 Bad Request" to """{"error":"bad request"}"""
    }
    val payload = body.toByteArray(Charsets.UTF_8)
    val head = "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
        "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
    return head.toByteArray(Charsets.US_ASCII) + payload
}

/** Reads up to the blank line ending the headers, one byte at a time so no body byte is consumed; null if malformed or over the cap. */
private fun readHeaderBlock(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    var matched = 0 // how many bytes of "\r\n\r\n" the tail currently matches
    val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    while (out.size() < MAX_HEADER_BYTES) {
        val b = try { input.read() } catch (e: IOException) { return null }
        if (b < 0) return null
        out.write(b)
        matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
        if (matched == terminator.size) return String(out.toByteArray(), 0, out.size() - 4, Charsets.ISO_8859_1)
    }
    return null
}

/** Reads up to [count] bytes (fewer at EOF); null if the stream throws. */
private fun readBody(input: InputStream, count: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(512)
    try {
        while (out.size() < count) {
            val n = input.read(buf, 0, minOf(buf.size, count - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
    } catch (e: IOException) {
        return null
    }
    return out.toByteArray()
}
