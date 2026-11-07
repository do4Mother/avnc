/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20.GL_BLEND
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_RGBA
import android.opengl.GLES20.GL_TEXTURE_2D
import android.opengl.GLES20.GL_UNSIGNED_BYTE
import android.opengl.GLES20.glTexImage2D
import android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA
import android.opengl.GLES20.GL_SRC_ALPHA
import android.opengl.GLES20.glBlendFunc
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glDisable
import android.opengl.GLES20.glEnable
import android.opengl.GLES20.glViewport
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.vnc.CursorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Frame renderer.
 */
class Renderer(val viewModel: VncViewModel) : GLSurfaceView.Renderer {

    companion object {
        private const val ARROW_W = 24
        private const val ARROW_H = 32

        /**
         * A white arrow with a black outline, as premultiplied RGBA bytes (the shader swaps
         * red and blue, which does not matter for black and white).
         */
        private fun renderArrow(): ByteBuffer {
            val bitmap = Bitmap.createBitmap(ARROW_W, ARROW_H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val path = Path().apply {
                moveTo(1f, 1f)
                lineTo(1f, 23f)
                lineTo(7f, 18f)
                lineTo(11f, 28f)
                lineTo(15f, 26f)
                lineTo(11f, 17f)
                lineTo(19f, 17f)
                close()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
            val buffer = ByteBuffer.allocateDirect(ARROW_W * ARROW_H * 4).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            bitmap.recycle()
            buffer.rewind()
            return buffer
        }
    }

    private val projectionMatrix = FloatArray(16)
    /** The pointer this app draws ("local" to the user; "remote" is the one inside the picture). */
    private val drawCursor = !viewModel.pref.input.hideLocalCursor
    private lateinit var program: Program
    private lateinit var frame: Frame
    private lateinit var cursor: Cursor

    /**
     * Decoded H.264 video arrives on [videoSurface] (through [VideoFrame]'s SurfaceTexture)
     * and is drawn with [videoProgram] instead of the framebuffer texture.
     */
    private lateinit var videoProgram: Program
    private lateinit var video: VideoFrame
    private var videoSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    /**
     * The pointer drawn when the server sends no cursor picture (it streams video, which
     * carries no cursor): a plain arrow.
     */
    private val arrowInfo = CursorInfo(ARROW_W, ARROW_H, 1, 1)
    private val arrowPixels: ByteBuffer by lazy { renderArrow() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(0f, 0f, 0f, 1f)

        frame = Frame()
        cursor = Cursor()
        program = Program()

        videoProgram = Program(Shaders.VIDEO_VERTEX_SHADER, Shaders.VIDEO_FRAGMENT_SHADER)
        video = VideoFrame { viewModel.onVideoFrameAvailable() }
        videoSurface?.release()
        videoSurface = Surface(video.surfaceTexture).also { viewModel.onVideoSurface(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
    }

    /**
     * Draws frame on screen according to current state.
     *
     * Y-axis of coordinate system used by OpenGL is in opposite direction (upwards)
     * relative to Y-axis in screen coordinates (downwards).
     *
     * To compensate for this, we invert the Y-coordinates of drawn frame. This is
     * achieved by:
     *              1. Using clipping region [-height, 0] instead of [0, height] for Y-axis
     *              2. Inverting sign of Y-axis position
     *              3. Inverting sign of Y-axis scale (to flip the frame)
     *
     * So the frame is drawn as follows in OpenGL:
     *
     *              +Y ^
     *                 |
     *                 |[0,0]            [width,0]
     *        -X <-----|---------------------+-------------> +X
     *                 |                     |
     *                 |                     |
     *                 |       Frame         |
     *                 |                     |
     *                 |                     |
     *                 +---------------------+
     *                 |[0,-height]    [width,-height]
     *                 |
     *              -Y v
     *
     * Doing this inversion here allows rest of the code to not worry about difference
     * in Y-axis direction.
     *
     */
    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT)
        glDisable(GL_BLEND)

        val client = viewModel.client ?: return
        if (!client.connected || client.frameBufferUpdatesPaused.get())
            return

        val state = viewModel.frameState.getSnapshot()
        if (state.vpWidth == 0f || state.vpHeight == 0f)
            return

        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.orthoM(projectionMatrix, 0, 0f, state.vpWidth, -state.vpHeight, 0f, -1f, 1f)
        Matrix.translateM(projectionMatrix, 0, state.frameX, -state.frameY, 0f)
        Matrix.scaleM(projectionMatrix, 0, state.scale, -state.scale, 1f)

        program.useProgram()
        program.setUniforms(projectionMatrix)

        if (client.videoMode && viewModel.videoFramesDecoded > 0) {
            video.surfaceTexture.updateTexImage()
            video.surfaceTexture.getTransformMatrix(texMatrix)
            videoProgram.useProgram()
            videoProgram.setUniforms(projectionMatrix)
            videoProgram.setTexMatrix(texMatrix)
            video.updateFbSize(state.fbWidth, state.fbHeight)
            video.bind(videoProgram)
            video.draw()
            videoProgram.validate()
            // Back to the plain program for the cursor.
            program.useProgram()
            program.setUniforms(projectionMatrix)
        } else {
            frame.updateFbSize(state.fbWidth, state.fbHeight)
            frame.bind(program)
            client.uploadFrameTexture()
            frame.draw()
            program.validate()
        }

        if (drawCursor) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            val fromServer = client.cursorShapeReceived
            val ci = if (fromServer) client.cursorInfo else arrowInfo

            frame.updateFbSize(state.fbWidth, state.fbHeight) // the clip rectangle, in video mode too
            cursor.update(client.pointerX.toFloat(), client.pointerY.toFloat(), ci, frame)
            cursor.bind(program)
            if (fromServer) {
                client.uploadCursorTexture()
            } else {
                arrowPixels.rewind()
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ARROW_W, ARROW_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, arrowPixels)
            }
            cursor.draw()

            glDisable(GL_BLEND)
        }
    }
}