package dev.portalsensory.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SensoryCameraEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "SensoryCameraEngine"
        const val CAMERA_ID_DEFAULT = "0" // 720p dewarped smart camera
        const val CAMERA_ID_FULL_SENSOR = "1" // 12.2MP raw sensor if available
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
    }

    enum class Resolution {
        SMALL,
        FULL
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var activeTextureView: TextureView? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive

    private val _activeCameraId = MutableStateFlow(CAMERA_ID_DEFAULT)
    val activeCameraId: StateFlow<String> = _activeCameraId

    // Cached recent frame for fast snapshot fallback
    @Volatile
    var latestPreviewJpeg: ByteArray? = null
        private set

    private var lastMjpegStreamTime = 0L

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("SensoryCameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCamera(textureView: TextureView, cameraId: String = CAMERA_ID_DEFAULT) {
        activeTextureView = textureView
        _activeCameraId.value = cameraId

        if (textureView.isAvailable) {
            openCameraWithTexture(textureView.surfaceTexture!!, cameraId)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCameraWithTexture(surface, _activeCameraId.value)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    val now = System.currentTimeMillis()
                    if (now - lastMjpegStreamTime >= 100) {
                        lastMjpegStreamTime = now
                        activeTextureView?.let { tv ->
                            try {
                                val bm = tv.getBitmap(640, 360)
                                if (bm != null) {
                                    val out = ByteArrayOutputStream()
                                    bm.compress(Bitmap.CompressFormat.JPEG, 65, out)
                                    bm.recycle()
                                    latestPreviewJpeg = out.toByteArray()
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraWithTexture(surfaceTexture: SurfaceTexture, cameraId: String) {
        surfaceTexture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        previewSurface = Surface(surfaceTexture)

        try {
            Log.i(TAG, "Opening camera ID: $cameraId")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera $cameraId opened successfully")
                    cameraDevice = camera
                    _isCameraActive.value = true
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera $cameraId disconnected")
                    camera.close()
                    cameraDevice = null
                    _isCameraActive.value = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera $cameraId error: $error")
                    camera.close()
                    cameraDevice = null
                    _isCameraActive.value = false
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception opening camera $cameraId", e)
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        try {
            val surfaces = listOf(preview)
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        }
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                        Log.i(TAG, "Camera preview session running on TextureView")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed starting repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating capture session", e)
        }
    }

    /**
     * Capture frame at requested resolution:
     * - SMALL: 640x360, JPEG Q70 (~25-40 KB, low tokens)
     * - FULL: 1280x720, JPEG Q95 (~200-350 KB, high detail)
     */
    suspend fun captureFrame(resolution: Resolution): ByteArray = withContext(Dispatchers.IO) {
        val texture = activeTextureView

        if (texture != null && texture.isAvailable) {
            return@withContext withContext(Dispatchers.Main) {
                val targetW = if (resolution == Resolution.SMALL) 640 else PREVIEW_WIDTH
                val targetH = if (resolution == Resolution.SMALL) 360 else PREVIEW_HEIGHT
                val quality = if (resolution == Resolution.SMALL) 70 else 95

                val bitmap = texture.getBitmap(targetW, targetH)
                    ?: throw IllegalStateException("Failed to grab bitmap from TextureView")

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                bitmap.recycle()
                return@withContext stream.toByteArray()
            }
        }

        val cached = latestPreviewJpeg
        if (cached != null) {
            return@withContext cached
        }

        throw IllegalStateException("Camera preview not currently ready for capture")
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            previewSurface?.release()
            previewSurface = null
            _isCameraActive.value = false
            Log.i(TAG, "Camera closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    fun release() {
        closeCamera()
        stopBackgroundThread()
    }
}
