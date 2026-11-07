/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.gl

/**
 * Shaders used for rendering framebuffer
 */
object Shaders {
    //language=GLSL
    const val VERTEX_SHADER = """
            uniform mat4 u_Projection;
            attribute vec2 a_Position;
            attribute vec2 a_TextureCoordinates;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               v_TextureCoordinates = a_TextureCoordinates;
               gl_Position = u_Projection * vec4(a_Position, 0, 1);
            }"""

    /**
     * For the decoded video: a SurfaceTexture sample. Its transform matrix maps texture
     * coordinates in GL convention (t = 0 at the bottom), while the frame quad puts t = 0 at
     * the top like the RGB texture, so t is flipped before the matrix.
     */
    //language=GLSL
    const val VIDEO_VERTEX_SHADER = """
            uniform mat4 u_Projection;
            uniform mat4 u_TexMatrix;
            attribute vec2 a_Position;
            attribute vec2 a_TextureCoordinates;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               vec2 flipped = vec2(a_TextureCoordinates.x, 1.0 - a_TextureCoordinates.y);
               v_TextureCoordinates = (u_TexMatrix * vec4(flipped, 0.0, 1.0)).xy;
               gl_Position = u_Projection * vec4(a_Position, 0, 1);
            }"""

    //language=GLSL
    const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_TextureUnit;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               gl_FragColor = texture2D(u_TextureUnit, v_TextureCoordinates);
            }"""

    //language=GLSL
    const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_TextureUnit;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               gl_FragColor = texture2D(u_TextureUnit, v_TextureCoordinates).bgra;
            }"""
}