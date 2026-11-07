/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.gl

import android.opengl.GLES20.GL_TEXTURE0
import android.opengl.GLES20.glActiveTexture
import android.opengl.GLES20.glGetAttribLocation
import android.opengl.GLES20.glGetUniformLocation
import android.opengl.GLES20.glUniform1i
import android.opengl.GLES20.glUniformMatrix4fv
import android.opengl.GLES20.glUseProgram
import com.gaurav.avnc.BuildConfig

/**
 * Represents the GL program used for Frame rendering.
 */
class Program(vertexShader: String = Shaders.VERTEX_SHADER, fragmentShader: String = Shaders.FRAGMENT_SHADER) {

    companion object {
        // Attribute constants
        const val A_POSITION = "a_Position"
        const val A_TEXTURE_COORDINATES = "a_TextureCoordinates"

        // Uniform constants
        const val U_PROJECTION = "u_Projection"
        const val U_TEXTURE_UNIT = "u_TextureUnit"
        const val U_TEX_MATRIX = "u_TexMatrix"
    }

    val program = ShaderCompiler.buildProgram(vertexShader, fragmentShader)
    val aPositionLocation = glGetAttribLocation(program, A_POSITION)
    val aTextureCoordinatesLocation = glGetAttribLocation(program, A_TEXTURE_COORDINATES)
    private val uProjectionLocation = glGetUniformLocation(program, U_PROJECTION)
    private val uTexUnitLocation = glGetUniformLocation(program, U_TEXTURE_UNIT)
    private val uTexMatrixLocation = glGetUniformLocation(program, U_TEX_MATRIX)
    private var validated = false

    /**
     * Texture coordinate transform (video program only).
     */
    fun setTexMatrix(matrix: FloatArray) {
        if (uTexMatrixLocation >= 0)
            glUniformMatrix4fv(uTexMatrixLocation, 1, false, matrix, 0)
    }


    fun setUniforms(projectionMatrix: FloatArray) {
        glUniformMatrix4fv(uProjectionLocation, 1, false, projectionMatrix, 0)
        glActiveTexture(GL_TEXTURE0)
        glUniform1i(uTexUnitLocation, 0)
    }

    fun validate() {
        if (BuildConfig.DEBUG && !validated) {
            ShaderCompiler.validateProgram(program)
            validated = true
        }
    }

    fun useProgram() {
        glUseProgram(program)
    }
}