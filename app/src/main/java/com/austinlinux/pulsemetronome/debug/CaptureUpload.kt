package com.austinlinux.pulsemetronome.debug

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Sends a debug-capture zip somewhere the developer can read it. Only debug and watchtest builds have an implementation. */
interface CaptureUploader {
    suspend fun upload(zip: File): UploadResult
}

/** Outcome of one upload attempt. */
sealed interface UploadResult {
    object Done : UploadResult
    object Offline : UploadResult
    data class Failed(val cause: String) : UploadResult
}

/** Storage object path: uploads/{pkg}/{versionName}/{installId}/{yyyyMMdd-HHmmss}.zip, timestamp in UTC. */
fun uploadPath(pkg: String, versionName: String, installId: String, nowMs: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs))
    return "uploads/$pkg/$versionName/$installId/$stamp.zip"
}

/** User-facing status line for an upload result. */
fun uploadMessage(r: UploadResult): String = when (r) {
    UploadResult.Done -> "Capture uploaded. Thank you."
    UploadResult.Offline -> "You're offline. Connect to the internet, then try Upload again."
    is UploadResult.Failed -> "Upload didn't finish."
}

/** Returns the stored random install id, creating and storing one on first use; storage is injected so JVM tests need no Android. */
fun installId(get: () -> String?, put: (String) -> Unit): String =
    get() ?: UUID.randomUUID().toString().also(put)
