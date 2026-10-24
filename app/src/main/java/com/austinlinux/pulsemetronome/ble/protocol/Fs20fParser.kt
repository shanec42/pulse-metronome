package com.austinlinux.pulsemetronome.ble.protocol

class ParseException(message: String) : Exception(message)

internal data class RawWaveMessage(val sensorOff: Boolean)

class Fs20fParser {
    /** Parses one already-framed, length-correct message (MessageFramer output). */
    fun parse(raw: ByteArray, timestampMs: Long): PulseReading? {
        if (raw.size < LEN_PO_WAVE) throw ParseException("message too short: ${raw.size} bytes")
        val type = raw[2].toInt() and 0xFF
        return when (type) {
            TYPE_PO_PARAM -> parseParam(raw, timestampMs)
            TYPE_PO_WAVE -> { parseWave(raw); null }
            else -> throw ParseException("unknown message type: 0x${type.toString(16)}")
        }
    }

    internal fun parseParam(raw: ByteArray, timestampMs: Long): PulseReading {
        val prRaw = toSignedShort(low = raw[4].toInt() and 0xFF, high = raw[3].toInt() and 0xFF)
        val spo2Raw = raw[5].toInt() and 0xFF
        val piRaw = toSignedShort(low = raw[7].toInt() and 0xFF, high = raw[6].toInt() and 0xFF)

        if (prRaw == INVALID_PR) {
            return PulseReading.NoSignal(timestampMs)
        }
        val spo2 = if (spo2Raw == INVALID_SPO2) null else spo2Raw
        val pi = if (piRaw == INVALID_PI) null else piRaw / 1000.0
        return PulseReading.Valid(
            pulseRate = prRaw,
            spo2 = spo2,
            perfusionIndex = pi,
            timestampMs = timestampMs
        )
    }

    internal fun parseWave(raw: ByteArray): RawWaveMessage {
        val sensorOff = ((raw[4].toInt() and 0xFF) shr 1) and 1 == 1
        return RawWaveMessage(sensorOff = sensorOff)
    }
}
