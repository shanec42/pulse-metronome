package com.austinlinux.pulsemetronome.ble.protocol

/**
 * Buffers incoming BLE notification bytes and extracts complete FS20F messages,
 * resyncing on TOKEN_START (0xFE) + valid length byte, per fs20f_logger.py's
 * process_buffer. Not thread-safe — caller must confine calls to one thread.
 */
class MessageFramer {
    private val buffer = ArrayDeque<Byte>()

    fun append(bytes: ByteArray) {
        bytes.forEach { buffer.addLast(it) }
    }

    fun extractMessages(): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        while (true) {
            val msg = extractOne() ?: break
            result.add(msg)
        }
        return result
    }

    private fun extractOne(): ByteArray? {
        var syncIndex = -1
        var msgLen = 0
        // Tracks the earliest index that COULD still become a valid start token once more
        // bytes arrive (a lone trailing TOKEN_START with no length byte yet) - that index
        // must survive a "no message found yet" clear, otherwise a message split exactly at
        // that boundary loses its start token and is silently dropped. Without this, a
        // buffer ending in a lone 0xFE (e.g. a 20-byte BLE notification chunk boundary
        // landing mid-header) gets wiped entirely, along with the next message it starts.
        var possibleFutureSyncIndex = -1
        for (i in buffer.indices) {
            val len = calcMsgLen(i)
            if (len > 0) {
                syncIndex = i
                msgLen = len
                break
            }
            if (possibleFutureSyncIndex == -1 &&
                (buffer[i].toInt() and 0xFF) == TOKEN_START &&
                buffer.size - i < 2
            ) {
                possibleFutureSyncIndex = i
            }
        }
        if (syncIndex == -1) {
            if (possibleFutureSyncIndex >= 0) {
                repeat(possibleFutureSyncIndex) { buffer.removeFirst() }
            } else {
                buffer.clear()
            }
            return null
        }
        repeat(syncIndex) { buffer.removeFirst() }
        if (buffer.size < msgLen) return null
        val msg = ByteArray(msgLen) { buffer[it] }
        repeat(msgLen) { buffer.removeFirst() }
        return msg
    }

    private fun calcMsgLen(from: Int): Int {
        if (buffer.size - from < 2) return 0
        val b0 = buffer[from].toInt() and 0xFF
        val b1 = buffer[from + 1].toInt() and 0xFF
        if (b0 == TOKEN_START && (b1 == LEN_PO_PARAM || b1 == LEN_PO_WAVE)) return b1
        return 0
    }
}
