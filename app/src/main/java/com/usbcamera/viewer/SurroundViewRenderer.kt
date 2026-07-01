package com.usbcamera.viewer

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SurroundViewRenderer : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "SurroundViewRenderer"

        // Simple vertex shader - applies perspective warping
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // Fragment shader for external camera texture
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // Textured fragment shader for vehicle image
        private const val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    var context: Context? = null  // Made public for setting from Activity
    private var cameraProgram = 0
    private var vehicleProgram = 0
    private var vehicleTextureHandle = 0

    // Camera texture handles
    val textureHandles = IntArray(4)
    val surfaceTextures = arrayOfNulls<SurfaceTexture>(4)
    var onSurfaceTextureReady: ((Int, SurfaceTexture) -> Unit)? = null

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Curved quads for each camera
    private lateinit var leftQuad: CurvedQuad
    private lateinit var rightQuad: CurvedQuad
    private lateinit var reverseRect: SimpleRect  // Flat rectangle for reverse camera
    private lateinit var vehicle: VehicleRect

    // Frame counter for priority rendering
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)  // Disable for proper blending
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Create shader programs
        cameraProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        vehicleProgram = createProgram(VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)

        // Load vehicle texture
        vehicleTextureHandle = loadTexture(context, R.drawable.vehicle_topdown)

        // Create camera textures
        GLES20.glGenTextures(4, textureHandles, 0)

        for (i in 0..3) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureHandles[i])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Create SurfaceTexture for each camera
            surfaceTextures[i] = SurfaceTexture(textureHandles[i])
            onSurfaceTextureReady?.invoke(i, surfaceTextures[i]!!)
        }

        // Create trapezoid quads - sized to form complete rectangle at outer edges
        // Vehicle: 0.8 wide x 1.2 tall
        // Outer rectangle should be 3.0 wide x 3.0 tall
        val vehicleWidth = 0.8f
        val vehicleHeight = 1.2f
        val outerWidth = 3.0f
        val outerHeight = 3.0f

        // Left/right trapezoids (rotated, so dimensions swap)
        val leftRightOuterWidth = outerHeight  // becomes height after rotation
        val leftRightInnerWidth = vehicleHeight
        val leftRightHeight = (outerWidth - vehicleWidth) / 2
        val leftRightRatio = leftRightInnerWidth / leftRightOuterWidth

        // Only create left and right trapezoids (front removed)
        leftQuad = CurvedQuad(leftRightOuterWidth, leftRightHeight, 10, -90, leftRightRatio, true)
        rightQuad = CurvedQuad(leftRightOuterWidth, leftRightHeight, 10, 90, leftRightRatio, true)

        // Simple rectangle for reverse camera (right half of screen)
        reverseRect = SimpleRect(2.8f, 2.8f)

        vehicle = VehicleRect(vehicleWidth, vehicleHeight)

        Log.i(TAG, "OpenGL surface created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)

        val ratio = width.toFloat() / height.toFloat()

        // Set up orthographic projection (flat 2D view)
        Matrix.orthoM(projectionMatrix, 0, -ratio * 1.5f, ratio * 1.5f, -1.5f, 1.5f, -1f, 10f)

        // Set up camera view (looking down Z-axis for 2D XY plane)
        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, 5f,   // Eye position (on Z axis looking toward origin)
            0f, 0f, 0f,   // Look at center
            0f, 1f, 0f)   // Up vector (Y is up)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        frameCount++

        // Update camera textures with priority:
        // - Left (position 1) and Right (position 3): HIGH PRIORITY (every frame)
        // - Reverse (position 2): LOW PRIORITY (every 3rd frame for ~10 FPS)
        surfaceTextures[1]?.updateTexImage()  // Left CVBS 1
        surfaceTextures[3]?.updateTexImage()  // Right CVBS 2

        if (frameCount % 3 == 0) {
            surfaceTextures[2]?.updateTexImage()  // Reverse camera (lower priority)
        }

        // Get current viewport dimensions
        val viewportWidth = GLES20.glGetIntegerv(GLES20.GL_VIEWPORT)?.get(2) ?: 1024
        val viewportHeight = GLES20.glGetIntegerv(GLES20.GL_VIEWPORT)?.get(3) ?: 600

        // ========== LEFT HALF: Trapezoids + Vehicle ==========
        GLES20.glViewport(0, 0, viewportWidth / 2, viewportHeight)

        // Draw camera quads (flat, top-down)
        GLES20.glUseProgram(cameraProgram)

        // Position trapezoids so outer edges form complete rectangle
        val vehicleWidth = 0.8f
        val vehicleHeight = 1.2f
        val leftRightHeight = (3.0f - vehicleWidth) / 2

        // Left camera: center at vehicle_left - half_width
        drawCameraQuad(leftQuad, textureHandles[1], -vehicleWidth/2 - leftRightHeight/2, 0f, 0f, -90f)

        // Right camera: center at vehicle_right + half_width
        drawCameraQuad(rightQuad, textureHandles[3], vehicleWidth/2 + leftRightHeight/2, 0f, 0f, 90f)

        // Draw vehicle in center
        GLES20.glUseProgram(vehicleProgram)
        drawVehicle()

        // ========== RIGHT HALF: Reverse Camera ==========
        GLES20.glViewport(viewportWidth / 2, 0, viewportWidth / 2, viewportHeight)

        GLES20.glUseProgram(cameraProgram)
        drawCameraQuad(reverseRect, textureHandles[2], 0f, 0f, 0f, 180f)  // Reverse camera
    }

    private fun GLES20.glGetIntegerv(pname: Int): IntArray? {
        val result = IntArray(4)
        GLES20.glGetIntegerv(pname, result, 0)
        return result
    }

    private fun drawCameraQuad(quad: Any, textureHandle: Int, x: Float, y: Float, z: Float, rotation: Float) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.rotateM(modelMatrix, 0, rotation, 0f, 0f, 1f)  // Rotate around Z for top-down

        // Combine matrices
        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        // Set uniforms
        val mvpMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        val textureUniform = GLES20.glGetUniformLocation(cameraProgram, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureHandle)
        GLES20.glUniform1i(textureUniform, 0)

        when (quad) {
            is CurvedQuad -> quad.draw(cameraProgram)
            is SimpleRect -> quad.draw(cameraProgram)
        }
    }

    private fun drawVehicle() {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, 0f)

        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(vehicleProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // Bind vehicle texture
        val textureUniform = GLES20.glGetUniformLocation(vehicleProgram, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vehicleTextureHandle)
        GLES20.glUniform1i(textureUniform, 0)

        vehicle.draw(vehicleProgram)
    }

    private fun loadTexture(context: Context?, resourceId: Int): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)

        if (textureHandle[0] != 0 && context != null) {
            val options = BitmapFactory.Options()
            options.inScaled = false

            val bitmap = BitmapFactory.decodeResource(context.resources, resourceId, options)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            bitmap.recycle()
        }

        return textureHandle[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}

// Curved quad mesh for camera feed (supports trapezoid perspective)
class CurvedQuad(private val width: Float, private val height: Float, private val segments: Int, private val texRotation: Int = 0, private val trapezoidRatio: Float = 1.0f, private val reverseTrapezoid: Boolean = false) {
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer
    private val vertexCount: Int

    init {
        val vertices = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()

        // Create trapezoid mesh
        // trapezoidRatio: 0.5 = inner edge half width of outer edge
        for (i in 0..segments) {
            val t = i.toFloat() / segments

            // Trapezoid direction depends on reverseTrapezoid flag
            val topWidth: Float
            val bottomWidth: Float

            if (reverseTrapezoid) {
                // Reversed: narrow at top, wide at bottom (for rotated sides)
                topWidth = width * trapezoidRatio
                bottomWidth = width
            } else {
                // Normal: wide at top, narrow at bottom
                topWidth = width
                bottomWidth = width * trapezoidRatio
            }

            val xTop = (t - 0.5f) * topWidth
            val xBottom = (t - 0.5f) * bottomWidth
            val z = 0f  // Flat (no curve)

            // Top vertex
            vertices.add(xTop)
            vertices.add(height / 2)
            vertices.add(z)

            // Bottom vertex
            vertices.add(xBottom)
            vertices.add(-height / 2)
            vertices.add(z)

            // Texture coordinates (rotated based on texRotation)
            when (texRotation) {
                90 -> {
                    // Rotate 90° clockwise: (u,v) -> (v, 1-u)
                    texCoords.add(0f)
                    texCoords.add(1f - t)
                    texCoords.add(1f)
                    texCoords.add(1f - t)
                }
                -90 -> {
                    // Rotate 90° counterclockwise: (u,v) -> (1-v, u)
                    texCoords.add(1f)
                    texCoords.add(t)
                    texCoords.add(0f)
                    texCoords.add(t)
                }
                180 -> {
                    // Rotate 180°: (u,v) -> (1-u, 1-v)
                    texCoords.add(1f - t)
                    texCoords.add(1f)
                    texCoords.add(1f - t)
                    texCoords.add(0f)
                }
                else -> {
                    // No rotation (0°)
                    texCoords.add(t)
                    texCoords.add(0f)
                    texCoords.add(t)
                    texCoords.add(1f)
                }
            }
        }

        vertexCount = vertices.size / 3

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices.toFloatArray())
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords.toFloatArray())
        texCoordBuffer.position(0)
    }

    fun draw(program: Int) {
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }
}

// Simple top-down car shape with texture
class VehicleRect(private val width: Float, private val height: Float) {
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        // Top-down car outline in XY plane (Z=0 for 2D view)
        val w = width / 2
        val h = height / 2

        val vertices = floatArrayOf(
            // Main body (rectangle) - now in XY plane
            -w, -h, 0f,      // Front-left
            w, -h, 0f,       // Front-right
            w, h, 0f,        // Rear-right
            -w, h, 0f        // Rear-left
        )

        val texCoords = floatArrayOf(
            0f, 1f,    // Front-left (flipped vertically)
            1f, 1f,    // Front-right (flipped vertically)
            1f, 0f,    // Rear-right (flipped vertically)
            0f, 0f     // Rear-left (flipped vertically)
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer.position(0)
    }

    fun draw(program: Int) {
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // Draw filled car body
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }
}

// Simple flat rectangle for reverse camera display (no trapezoid warping)
class SimpleRect(private val width: Float, private val height: Float) {
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        val w = width / 2
        val h = height / 2

        val vertices = floatArrayOf(
            // Simple rectangle in XY plane
            -w, -h, 0f,      // Bottom-left
            w, -h, 0f,       // Bottom-right
            w, h, 0f,        // Top-right
            -w, h, 0f        // Top-left
        )

        val texCoords = floatArrayOf(
            0f, 1f,    // Bottom-left
            1f, 1f,    // Bottom-right
            1f, 0f,    // Top-right
            0f, 0f     // Top-left
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer.position(0)
    }

    fun draw(program: Int) {
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }
}
