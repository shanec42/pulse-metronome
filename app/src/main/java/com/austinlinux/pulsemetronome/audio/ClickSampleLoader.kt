package com.austinlinux.pulsemetronome.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the raw PCM data chunk out of the given raw resource (click.wav, Task 13) into a
 * ShortArray ready to feed AudioEngine's clickSampleProvider. Uses MediaExtractor rather than
 * hand-parsing the WAV RIFF header, since MediaExtractor already handles the format correctly
 * regardless of any header quirks in the specific file chosen.
 */
fun loadClickSample(context: Context, rawResId: Int): ShortArray {
    val extractor = MediaExtractor()
    val afd = context.resources.openRawResourceFd(rawResId)
    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
    afd.close()

    if (extractor.trackCount == 0) return ShortArray(0)
    val format = extractor.getTrackFormat(0)
    extractor.selectTrack(0)

    val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
    } else {
        1 shl 20
    }
    val buffer = ByteBuffer.allocate(maxInputSize).order(ByteOrder.LITTLE_ENDIAN)
    val chunks = mutableListOf<ByteArray>()
    while (true) {
        buffer.clear()
        val bytesRead = extractor.readSampleData(buffer, 0)
        if (bytesRead < 0) break
        val chunk = ByteArray(bytesRead)
        buffer.get(chunk)
        chunks.add(chunk)
        extractor.advance()
    }
    extractor.release()

    val totalBytes = chunks.sumOf { it.size }
    val pcm = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
    chunks.forEach { pcm.put(it) }
    pcm.rewind()

    val shorts = ShortArray(totalBytes / 2)
    val shortBuffer = pcm.asShortBuffer()
    shortBuffer.get(shorts)
    return shorts
}
