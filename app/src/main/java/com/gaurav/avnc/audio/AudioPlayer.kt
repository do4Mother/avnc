/*
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */

package com.gaurav.avnc.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Plays raw PCM (S16LE, stereo, 48 kHz) delivered by the VNC thread.
 *
 * [write] never blocks; all AudioTrack I/O happens on a private thread.
 * A [DriftGate] keeps the device buffer near [TARGET_MS].
 */
class AudioPlayer {

    private companion object {
        const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 48000
        const val BYTES_PER_FRAME = 4 // 16-bit stereo
        const val QUEUE_CAPACITY = 16
        const val MASK32 = 0xFFFFFFFFL
    }

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val gate = DriftGate()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    @Synchronized
    fun start() {
        if (running) return
        val t = try {
            createTrack()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create AudioTrack", e)
            return
        }
        track = t
        queue.clear()
        running = true
        t.play()
        thread = Thread({ playLoop(t) }, "AudioPlayer").also { it.start() }
    }

    /** Non-blocking. Drops the chunk if the player is behind. */
    fun write(pcm: ByteArray) {
        if (running) queue.offer(pcm)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val t = track
        val th = thread
        th?.interrupt()
        try { t?.pause(); t?.flush() } catch (_: Exception) {}   // unblocks a pending write()
        try { th?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { t?.stop() } catch (_: Exception) {}
        t?.release()
        track = null
        thread = null
        queue.clear()
    }

    private fun playLoop(track: AudioTrack) {
        var framesWritten = 0L
        while (running) {
            val chunk = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue

            // playbackHeadPosition is a 32-bit frame counter that wraps; subtract modulo 2^32.
            val head = track.playbackHeadPosition.toLong() and MASK32
            val bufferedFrames = (framesWritten - head) and MASK32
            val bufferedMs = if (bufferedFrames > SAMPLE_RATE) 1000 // sanity clamp: 1 s
                             else (bufferedFrames * 1000 / SAMPLE_RATE).toInt()

            try {
                when (val d = gate.decide(bufferedMs)) {
                    is Decision.Drop -> {}
                    is Decision.Prefill -> {
                        val silence = ByteArray(d.silenceMs * SAMPLE_RATE / 1000 * BYTES_PER_FRAME)
                        framesWritten += writeFully(track, silence) / BYTES_PER_FRAME
                        framesWritten += writeFully(track, chunk) / BYTES_PER_FRAME
                    }
                    is Decision.Write -> framesWritten += writeFully(track, chunk) / BYTES_PER_FRAME
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Playback failed", e)
                break
            }
        }
    }

    /** Blocking write on the player thread. Returns bytes written. */
    private fun writeFully(track: AudioTrack, data: ByteArray): Int {
        var off = 0
        while (off < data.size && running) {
            val n = track.write(data, off, data.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    @Suppress("DEPRECATION")
    private fun createTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val wanted = 2 * HIGH_MS * SAMPLE_RATE / 1000 * BYTES_PER_FRAME
        val bufBytes = max(minBuf, wanted)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                              AudioFormat.ENCODING_PCM_16BIT, bufBytes, AudioTrack.MODE_STREAM)
        }

        val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        val b = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return b.build()
    }
}
