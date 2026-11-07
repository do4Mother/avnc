/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.gl

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.GL_CLAMP_TO_EDGE
import android.opengl.GLES20.GL_LINEAR
import android.opengl.GLES20.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES20.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES20.GL_TEXTURE_WRAP_S
import android.opengl.GLES20.GL_TEXTURE_WRAP_T
import android.opengl.GLES20.glBindTexture
import android.opengl.GLES20.glGenTextures
import android.opengl.GLES20.glTexParameteri

/**
 * The frame quad drawn from decoded H.264 video: an external (OES) texture fed by a
 * [SurfaceTexture], which the video decoder renders into. Must be created on the renderer
 * thread; [onFrameAvailable] is called from the SurfaceTexture's thread.
 */
class VideoFrame(onFrameAvailable: () -> Unit) : Image() {

    private val externalTexture = allocateExternalTexture()
    val surfaceTexture = SurfaceTexture(externalTexture).apply {
        setOnFrameAvailableListener { onFrameAvailable() }
    }
    private val frameRect = RectF()

    init {
        updateTextureRect(RectF(0f, 0f, 1f, 1f))
    }

    private fun allocateExternalTexture(): Int {
        val ids = intArrayOf(0)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, ids[0])
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
        return ids[0]
    }

    /**
     * Same geometry as [Frame]: the quad covers the framebuffer.
     */
    fun updateFbSize(width: Float, height: Float) {
        if (frameRect.width() == width && frameRect.height() == height)
            return

        frameRect.set(0f, 0f, width, height)
        updateDrawRect(frameRect)
    }

    override fun bindTexture() {
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexture)
    }
}
