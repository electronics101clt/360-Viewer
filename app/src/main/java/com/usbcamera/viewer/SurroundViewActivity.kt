package com.usbcamera.viewer

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.View

class SurroundViewActivity : Activity() {
    companion object {
        private const val TAG = "SurroundViewActivity"
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: SurroundViewRenderer
    private lateinit var cameraManager: CameraManager

    private val cameraDevices = arrayOfNulls<CameraDevice>(4)
    private val captureSessions = arrayOfNulls<CameraCaptureSession>(4)
    private val cameraAssignments = arrayOfNulls<String>(4)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide system UI for fullscreen
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        // Create GLSurfaceView
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(2)

        // Create renderer with context
        renderer = SurroundViewRenderer()
        renderer.context = this
        renderer.onSurfaceTextureReady = { position, surfaceTexture ->
            // When SurfaceTexture is ready, open the corresponding camera
            runOnUiThread {
                cameraAssignments[position]?.let { cameraId ->
                    openCamera(position, cameraId, surfaceTexture)
                }
            }
        }

        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        setContentView(glSurfaceView)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Assign cameras
        assignCameras()
    }

    private fun assignCameras() {
        try {
            val allCameras = cameraManager.cameraIdList.toList()

            Log.i(TAG, "All detected cameras: $allCameras")

            // CVBS inputs - typically show up as camera IDs
            // User needs to verify which IDs correspond to:
            // - CVBS 1 (left blind spot camera)
            // - CVBS 2 (right blind spot camera)
            // - Reverse camera input

            // TODO: Replace these with actual CVBS camera IDs from your device
            // Check logcat for "All detected cameras" to find the correct IDs
            val cvbs1CameraId = allCameras.getOrNull(0)  // CVBS 1 - Left camera
            val cvbs2CameraId = allCameras.getOrNull(1)  // CVBS 2 - Right camera
            val reverseCameraId = allCameras.getOrNull(2) // Reverse camera input

            // Assign to positions:
            // Position 0 (front) = REMOVED (not used)
            // Position 1 (left)  = CVBS 1 (left blind spot)
            // Position 2 (rear)  = Reverse camera (lower priority, right half of screen)
            // Position 3 (right) = CVBS 2 (right blind spot)
            cameraAssignments[0] = null  // Front removed
            cameraAssignments[1] = cvbs1CameraId   // CVBS 1 - Left
            cameraAssignments[2] = reverseCameraId // Reverse camera
            cameraAssignments[3] = cvbs2CameraId   // CVBS 2 - Right

            Log.i(TAG, "Camera assignments - Left: $cvbs1CameraId, Right: $cvbs2CameraId, Reverse: $reverseCameraId")

        } catch (e: Exception) {
            Log.e(TAG, "Error assigning cameras", e)
        }
    }

    private fun openCamera(position: Int, cameraId: String, surfaceTexture: SurfaceTexture) {
        startBackgroundThread()

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevices[position] = camera
                    Log.i(TAG, "Position $position: Camera $cameraId opened")
                    startPreview(position, surfaceTexture, camera, cameraId)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevices[position] = null
                    Log.w(TAG, "Position $position: Camera $cameraId disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevices[position] = null
                    Log.e(TAG, "Position $position: Camera $cameraId error: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Position $position: Failed to open camera $cameraId", e)
        }
    }

    private fun startPreview(position: Int, surfaceTexture: SurfaceTexture,
                             cameraDevice: CameraDevice, cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return

            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            val previewSize = sizes[0]

            Log.i(TAG, "Position $position preview: ${previewSize.width}x${previewSize.height}")

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture)

            val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)

            cameraDevice.createCaptureSession(listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSessions[position] = session

                        try {
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )

                            val previewRequest = previewRequestBuilder.build()
                            session.setRepeatingRequest(previewRequest, null, backgroundHandler)

                            Log.i(TAG, "Position $position streaming")
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Position $position preview error", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Position $position config failed")
                    }
                }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Position $position preview error", e)
        }
    }

    private fun closeAllCameras() {
        for (i in 0..3) {
            captureSessions[i]?.close()
            captureSessions[i] = null
            cameraDevices[i]?.close()
            cameraDevices[i] = null
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        closeAllCameras()
        stopBackgroundThread()
        glSurfaceView.onPause()
        super.onPause()
    }
}
