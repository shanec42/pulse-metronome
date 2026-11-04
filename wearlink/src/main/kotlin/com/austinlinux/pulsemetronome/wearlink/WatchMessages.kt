package com.austinlinux.pulsemetronome.wearlink

object WearPaths {
    const val START = "/pm/start"
    const val ACK = "/pm/ack"
    const val STOP = "/pm/stop"
    const val SAMPLE = "/pm/sample"
    const val STATUS = "/pm/status"
    const val HEARTBEAT = "/pm/heartbeat"
    const val LOG_CHANNEL = "/pm/log"
    const val CAPABILITY = "pulse_metronome_watch"
}

/** Why a session could not start; NOT_REACHABLE and APP_MISSING are decided on the phone only. */
enum class CantStartReason { NOT_REACHABLE, APP_MISSING, NO_PERMISSION }

enum class WatchStatus { FINDING_PULSE, SENSING, CONTACT_LOST }

/** Phone/watch messages; the phone sends Heartbeat every 20 s to keep the watch safety timer alive. */
sealed interface WatchMessage {
    val sessionId: String

    data class Start(override val sessionId: String) : WatchMessage
    data class Ack(override val sessionId: String, val ok: Boolean, val reason: CantStartReason?) : WatchMessage
    data class Stop(override val sessionId: String) : WatchMessage
    data class Sample(override val sessionId: String, val bpm: Int) : WatchMessage
    data class Status(override val sessionId: String, val status: WatchStatus) : WatchMessage
    data class Heartbeat(override val sessionId: String) : WatchMessage
}

private const val MIN_BPM = 20
private const val MAX_BPM = 250

/** Data Layer path for this message. */
fun WatchMessage.path(): String = when (this) {
    is WatchMessage.Start -> WearPaths.START
    is WatchMessage.Ack -> WearPaths.ACK
    is WatchMessage.Stop -> WearPaths.STOP
    is WatchMessage.Sample -> WearPaths.SAMPLE
    is WatchMessage.Status -> WearPaths.STATUS
    is WatchMessage.Heartbeat -> WearPaths.HEARTBEAT
}

/** Encodes as UTF-8 `key=value;key=value`. */
fun WatchMessage.encode(): ByteArray {
    val fields = mutableListOf("sid" to escape(sessionId))
    when (this) {
        is WatchMessage.Ack -> {
            fields += "ok" to ok.toString()
            if (reason != null) fields += "reason" to reason.name
        }
        is WatchMessage.Sample -> fields += "bpm" to bpm.toString()
        is WatchMessage.Status -> fields += "status" to status.name
        is WatchMessage.Start, is WatchMessage.Stop, is WatchMessage.Heartbeat -> Unit
    }
    return fields.joinToString(";") { "${it.first}=${it.second}" }.toByteArray(Charsets.UTF_8)
}

/** Decodes a message; returns null for an unknown path or any malformed payload, never throws. */
fun decodeWatchMessage(path: String, bytes: ByteArray): WatchMessage? = try {
    decodeOrNull(path, bytes)
} catch (e: Exception) {
    null
}

private fun decodeOrNull(path: String, bytes: ByteArray): WatchMessage? {
    if (bytes.isEmpty()) return null
    val fields = parse(bytes) ?: return null
    val sid = fields["sid"]?.let(::unescape)?.takeIf { it.isNotEmpty() } ?: return null
    return when (path) {
        WearPaths.START -> WatchMessage.Start(sid)
        WearPaths.STOP -> WatchMessage.Stop(sid)
        WearPaths.HEARTBEAT -> WatchMessage.Heartbeat(sid)
        WearPaths.ACK -> {
            val ok = when (fields["ok"]) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val reasonName = fields["reason"]
            val reason = if (reasonName == null) null else enumOrNull<CantStartReason>(reasonName) ?: return null
            WatchMessage.Ack(sid, ok, reason)
        }
        WearPaths.SAMPLE -> {
            val bpm = fields["bpm"]?.toIntOrNull() ?: return null
            if (bpm < MIN_BPM || bpm > MAX_BPM) return null
            WatchMessage.Sample(sid, bpm)
        }
        WearPaths.STATUS -> WatchMessage.Status(sid, enumOrNull<WatchStatus>(fields["status"]) ?: return null)
        else -> null
    }
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }

/** Strict UTF-8 decode and split; null on invalid UTF-8, a field without `=`, or a duplicate key. */
private fun parse(bytes: ByteArray): Map<String, String>? {
    val text = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        return null
    }
    val map = HashMap<String, String>()
    for (part in text.split(';')) {
        val eq = part.indexOf('=')
        if (eq <= 0) return null
        if (map.put(part.substring(0, eq), part.substring(eq + 1)) != null) return null
    }
    return map
}

// Session ids are caller-supplied, so ';', '=' and '%' are percent-escaped to keep the format unambiguous.
private fun escape(s: String): String =
    s.replace("%", "%25").replace(";", "%3B").replace("=", "%3D")

private fun unescape(s: String): String =
    s.replace("%3D", "=").replace("%3B", ";").replace("%25", "%")
