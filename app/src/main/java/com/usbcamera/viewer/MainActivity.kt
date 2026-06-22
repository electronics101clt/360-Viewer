package com.usbcamera.viewer

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView

class MainActivity : Activity() {
    companion object {
        private const val TAG = "USBCameraViewer"
    }

    private lateinit var textureView1: TextureView
    private lateinit var textureView2: TextureView
    private lateinit var textureView3: TextureView
    private lateinit var textureView4: TextureView
    private lateinit var label1: TextView
    private lateinit var label2: TextView
    private lateinit var label3: TextView
    private lateinit var label4: TextView
    private lateinit var statusText: TextView

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

        setContentView(R.layout.activity_main)

        textureView1 = findViewById(R.id.textureView1)
        textureView2 = findViewById(R.id.textureView2)
        textureView3 = findViewById(R.id.textureView3)
        textureView4 = findViewById(R.id.textureView4)
        label1 = findViewById(R.id.label1)
        label2 = findViewById(R.id.label2)
        label3 = findViewById(R.id.label3)
        label4 = findViewById(R.id.label4)
        statusText = findViewById(R.id.statusText)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        assignCameras()
        setupTextureListeners()
    }

    private fun assignCameras() {
        try {
            val allCameras = cameraManager.cameraIdList.toList()

            // Separate USB and built-in cameras
            // Camera IDs >= 2 are typically USB cameras on this platform
            // (HAL bug: USB cameras report as BACK instead of EXTERNAL)
            val usbCameras = allCameras.filter { id ->
                id.toIntOrNull()?.let { it >= 2 } ?: false
            }

            val backCamera = allCameras.find { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            val frontCamera = allCameras.find { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            // Assign to 4 positions
            // Left side: Built-in cameras | Right side: USB cameras
            cameraAssignments[0] = frontCamera      // Top-left: Front Camera (built-in)
            cameraAssignments[1] = usbCameras.getOrNull(0)  // Top-right: USB Camera 1
            cameraAssignments[2] = backCamera       // Bottom-left: Rear Camera (built-in)
            cameraAssignments[3] = usbCameras.getOrNull(1)  // Bottom-right: USB Camera 2

            // Update labels
            label1.text = cameraAssignments[0]?.let { "Front Camera" } ?: "No Camera"
            label2.text = cameraAssignments[1]?.let { "USB Camera 1" } ?: "No Camera"
            label3.text = cameraAssignments[2]?.let { "Rear Camera" } ?: "No Camera"
            label4.text = cameraAssignments[3]?.let { "USB Camera 2" } ?: "No Camera"

            val activeCount = cameraAssignments.count { it != null }
            statusText.text = "$activeCount camera(s) available"

            Log.i(TAG, "Camera assignments: ${cameraAssignments.contentToString()}")

        } catch (e: Exception) {
            Log.e(TAG, "Error assigning cameras", e)
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun setupTextureListeners() {
        textureView1.surfaceTextureListener = createListener(0)
        textureView2.surfaceTextureListener = createListener(1)
        textureView3.surfaceTextureListener = createListener(2)
        textureView4.surfaceTextureListener = createListener(3)
    }

    private fun createListener(position: Int) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            cameraAssignments[position]?.let { cameraId ->
                openCamera(position, cameraId)
            }
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera(position: Int, cameraId: String) {
        startBackgroundThread()

        val textureView = when (position) {
            0 -> textureView1
            1 -> textureView2
            2 -> textureView3
            else -> textureView4
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevices[position] = camera
                    Log.i(TAG, "Position $position: Camera $cameraId opened")
                    startPreview(position, textureView, camera, cameraId)
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

                    // Leave blue background visible on error
                    runOnUiThread {
                        val label = when (position) {
                            0 -> label1
                            1 -> label2
                            2 -> label3
                            else -> label4
                        }
                        label.text = "${label.text} (Error $error)"
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Position $position: Failed to open camera $cameraId", e)
        }
    }

    private fun startPreview(position: Int, textureView: TextureView,
                            cameraDevice: CameraDevice, cameraId: String) {
        if (!textureView.isAvailable) {
            return
        }

        try {
            val texture = textureView.surfaceTexture ?: return

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return

            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            val previewSize = sizes[0]

            Log.i(TAG, "Position $position preview: ${previewSize.width}x${previewSize.height}")

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)

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
        // Cameras will open when texture surfaces become available
    }

    override fun onPause() {
        closeAllCameras()
        stopBackgroundThread()
        super.onPause()
    }
}
