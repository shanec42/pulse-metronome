package com.austinlinux.dfmetronome.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.austinlinux.dfmetronome.debug.DebugCapture
import java.util.concurrent.atomic.AtomicReference

private const val SAMPLE_RATE_HZ = 44100

/**
 * AudioTrack-based metronome click scheduler. Not unit-testable (real AudioTrack/AudioManager
 * APIs) - manual on-device verification only (Task 12 Step 5). Timing is paced by frame
 * count via blocking AudioTrack.write() calls (click + exact silence padding per beat), not
 * by a wall-clock poll loop - see start()'s scheduler thread for why.
 */
class AudioEngine(
    private val context: Context,
    private val clickSampleProvider: () -> ShortArray
) {
    private val currentBpm = AtomicReference(60.0)
    private var schedulerThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    // Monitor mode "Stop the metronome": beats still fill with silence so timing continues.
    @Volatile private var muted = false

    /** Silences (or restores) clicks without stopping the beat clock. */
    fun setMuted(value: Boolean) { muted = value }

    // Reusable zero-filled chunk the scheduler writes repeatedly to pad out each beat after
    // the click - keeps the AudioTrack continuously fed so it never underruns (see start()'s
    // scheduler loop comment for why an underrun is what caused the doubled-click bug).
    // Sized to 50ms stereo at 44100Hz; the loop writes as many of these as a beat needs.
    private val silenceBuffer = ShortArray(SAMPLE_RATE_HZ / 20 * 2)

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // Output device changed (e.g. headphones unplugged, Bluetooth audio dropped) -
            // pause rather than let the click jump out to the speaker unexpectedly. Resumes
            // on the next scheduled beat once a call to setBpm/start keeps the loop running -
            // the scheduling loop itself just skips writing samples while paused.
            DebugCapture.event("AUDIO") { "becoming noisy (output changed), paused" }
            paused = true
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        DebugCapture.event("AUDIO") {
            val name = when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
                AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
                else -> focusChange.toString()
            }
            "focus=$name"
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> paused = true
            AudioManager.AUDIOFOCUS_GAIN -> paused = false
        }
    }

    fun setBpm(bpm: Double) {
        // Spec: BPM is rounded to the nearest integer before use in audio scheduling, not
        // passed through as a raw fractional value.
        currentBpm.set(Math.round(bpm).toDouble())
    }

    fun start() {
        if (running) return
        running = true
        paused = false

        // Stereo, matching click.wav's actual format (Task 13) - the metronome only ever
        // plays one sound at a time, so stereo vs mono has no functional downside here, and
        // this avoids downmixing/altering the authored sound.
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        val focusResult = audioManager.requestAudioFocus(
            focusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        DebugCapture.event("AUDIO") { "start focusResult=$focusResult" }

        schedulerThread = Thread {
            val click = clickSampleProvider()
            // Root cause of the "tic tic" doubled-click bug (found via a dedicated debugging
            // pass): the old loop wrote one short (~67ms) burst per beat via a polling
            // System.currentTimeMillis() clock, then wrote NOTHING for the rest of the beat
            // interval (routinely 500-1000ms). That starves the AudioTrack's internal buffer
            // between clicks - a genuine underrun on every single beat, from the very first
            // one. On this device/output path, an underrun AudioTrack replays its last
            // buffered content instead of going silent, which is exactly what was heard: the
            // same click twice, back-to-back, every beat. This explains why the repeat count
            // tracked buffer size in earlier fix attempts (min buffer -> 2x, 4x-click buffer
            // -> 4x) rather than actually going away - neither attempt closed the silent gap
            // that causes the underrun in the first place.
            //
            // Fix: never let the stream underrun. Every beat is written as the click followed
            // by exactly enough silence to fill out the beat interval, all via AudioTrack's
            // default BLOCKING write() - which paces this thread by how much audio has
            // actually been consumed, replacing the old polling-clock/sleep(1) scheduler
            // entirely. BeatScheduler.kt's pure interval formula is no longer used here (its
            // own unit test still passes and documents the interval math independently).
            while (running) {
                val activeTrack = audioTrack ?: break
                // BPM (and pause state) is read once per beat, at the top - a change here
                // takes effect starting from the NEXT beat, not retroactively (spec).
                val bpm = currentBpm.get()
                val beatFrames = Math.round(SAMPLE_RATE_HZ * 60.0 / bpm)
                val beatShorts = (beatFrames * 2).toInt()  // stereo: 2 shorts/frame

                val head = if (paused || muted) null else click
                var written = 0
                if (head != null) {
                    val clickShorts = minOf(head.size.toLong(), beatShorts.toLong()).toInt()
                    while (written < clickShorts && running) {
                        val n = activeTrack.write(head, written, clickShorts - written)
                        if (n <= 0) { running = false; break }
                        written += n
                    }
                }
                var remaining = beatShorts - written
                while (remaining > 0 && running) {
                    val n = activeTrack.write(silenceBuffer, 0, minOf(remaining, silenceBuffer.size))
                    if (n <= 0) { running = false; break }
                    remaining -= n
                }
            }
        }.apply { start() }
    }

    fun stop() {
        DebugCapture.event("AUDIO") { "stop" }
        running = false
        schedulerThread?.join(1000)
        schedulerThread = null
        try {
            context.unregisterReceiver(noisyReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was never registered (stop() called without a matching start()) - ignore.
        }
        audioManager.abandonAudioFocus(focusChangeListener)
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    /** Derived from AudioTrack.playbackHeadPosition + known output latency, for the Live
     *  Session screen's beat indicator to stay synced with actually-audible playback rather
     *  than the scheduling loop's intended timestamps. */
    fun playbackHeadPositionMs(): Long {
        val track = audioTrack ?: return 0L
        val frames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (frames * 1000L) / SAMPLE_RATE_HZ
    }
}
