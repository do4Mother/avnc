/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.vnc

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the server's H.264 stream (Open H.264 encoding) with the device's hardware decoder
 * straight onto a [Surface], which the renderer samples as an external texture.
 *
 * Everything runs on one worker thread: frames are queued with [submit] from the VNC thread,
 * MediaCodec callbacks arrive on the same worker, so no locking is needed. A stream starts
 * (or restarts after the server's reset flag, a size change, or a decoder error) only on an
 * IDR frame; until one comes, frames are dropped and [requestKeyFrame] asks the server for a
 * full update, which makes it send an IDR.
 */
class VideoDecoder(private val requestKeyFrame: () -> Unit) {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME = "video/avc"
        private const val MAX_INPUT_SIZE = 4 * 1024 * 1024
        private const val KEY_REQUEST_INTERVAL_MS = 500L
        private const val MAX_QUEUED_FRAMES = 30

        private const val NAL_IDR = 5
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
    }

    private val thread = HandlerThread("VideoDecoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var needKey = true
    private var lastKeyRequest = 0L
    private var pts = 0L
    private val pending = ArrayDeque<ByteArray>()
    private val freeInputs = ArrayDeque<Int>()
    private var released = false

    /**
     * Queues one access unit. [flags] bit 0: the server started a new stream.
     */
    fun submit(data: ByteArray, flags: Int, width: Int, height: Int) {
        handler.post { handleFrame(data, flags, width, height) }
    }

    /**
     * Where decoded frames go. A new surface replaces the old one on the running decoder.
     */
    fun setSurface(surface: Surface?) {
        handler.post {
            this.surface = surface
            val c = codec ?: return@post
            if (surface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    c.setOutputSurface(surface)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not switch the output surface, restarting the decoder", e)
                    closeCodec()
                    askForKeyFrame()
                }
            } else {
                closeCodec()
                if (surface != null) askForKeyFrame()
            }
        }
    }

    fun release() {
        handler.post {
            released = true
            closeCodec()
            thread.quitSafely()
        }
    }

    private fun handleFrame(data: ByteArray, flags: Int, w: Int, h: Int) {
        if (released) return
        val key = hasNal(data, NAL_IDR)
        val restart = flags and 1 != 0 || w != width || h != height || codec == null
        if (restart) {
            if (!key) {
                askForKeyFrame()
                return
            }
            closeCodec()
            width = w
            height = h
            if (surface == null) {
                // The renderer has not given us a surface yet; try again with the next IDR.
                askForKeyFrame()
                return
            }
            if (!openCodec(data)) {
                askForKeyFrame()
                return
            }
        } else if (needKey) {
            if (!key) {
                askForKeyFrame()
                return
            }
        }
        needKey = false
        if (pending.size >= MAX_QUEUED_FRAMES) {
            // The decoder cannot keep up; start afresh from the next IDR rather than lag.
            Log.w(TAG, "Dropping ${pending.size} queued frames")
            pending.clear()
            needKey = true
            askForKeyFrame()
            return
        }
        pending.addLast(data)
        feed()
    }

    private fun openCodec(firstFrame: ByteArray): Boolean {
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        nal(firstFrame, NAL_SPS)?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        nal(firstFrame, NAL_PPS)?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // The server writes no colour description into the stream; this is what it encodes.
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

        for (lowLatency in listOf(true, false)) {
            if (!lowLatency) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) break
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 0)
            }
            val c = try {
                MediaCodec.createDecoderByType(MIME)
            } catch (e: Exception) {
                Log.e(TAG, "No H.264 decoder", e)
                return false
            }
            try {
                c.setCallback(callback, handler)
                c.configure(format, surface, null, 0)
                c.start()
                codec = c
                freeInputs.clear()
                pts = 0
                Log.i(TAG, "Decoder started for ${width}x${height} (low latency: $lowLatency)")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Decoder configuration failed (low latency: $lowLatency)", e)
                c.release()
            }
        }
        return false
    }

    private fun closeCodec() {
        val c = codec ?: return
        codec = null
        pending.clear()
        freeInputs.clear()
        needKey = true
        try {
            c.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stopping the decoder failed", e)
        }
        c.release()
    }

    private fun feed() {
        val c = codec ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val frame = pending.removeFirst()
            val buffer = c.getInputBuffer(index)
            if (buffer == null || buffer.capacity() < frame.size) {
                Log.w(TAG, "Input buffer too small for a ${frame.size} byte frame")
                freeInputs.addFirst(index)
                needKey = true
                askForKeyFrame()
                return
            }
            buffer.clear()
            buffer.put(frame)
            c.queueInputBuffer(index, 0, frame.size, pts, 0)
            pts += 1000
        }
    }

    private fun askForKeyFrame() {
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyRequest < KEY_REQUEST_INTERVAL_MS) return
        lastKeyRequest = now
        requestKeyFrame()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== this@VideoDecoder.codec) return
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== this@VideoDecoder.codec) return
            // Rendered straight away: the stream is live, timestamps mean nothing.
            codec.releaseOutputBuffer(index, true)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== this@VideoDecoder.codec) return
            Log.e(TAG, "Decoder error", e)
            closeCodec()
            askForKeyFrame()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    /**
     * Whether an Annex-B access unit contains a NAL unit of [type].
     */
    private fun hasNal(data: ByteArray, type: Int): Boolean = nal(data, type) != null

    /**
     * The first NAL unit of [type] in [data], with its start code, or null.
     */
    private fun nal(data: ByteArray, type: Int): ByteArray? {
        var i = 0
        var start = -1
        var startType = -1
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                // A start code may have a leading zero byte; include it in the NAL unit.
                val codeStart = if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
                if (startType == type)
                    return data.copyOfRange(start, codeStart)
                if (i + 3 < data.size) {
                    start = codeStart
                    startType = data[i + 3].toInt() and 0x1F
                }
                i += 3
            } else {
                i += 1
            }
        }
        return if (startType == type) data.copyOfRange(start, data.size) else null
    }
}
