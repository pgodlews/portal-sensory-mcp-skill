package dev.portalsensory.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
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

    // Rotation management (Meta Portal Go is mounted at 270 deg, Portal+ is 0 deg)
    val deviceDefaultRotation: Int
        get() {
            val device = Build.DEVICE.lowercase()
            val model = Build.MODEL.lowercase()
            return when {
                device == "terry" || model.contains("go") -> 270
                device == "cipher" -> 0
                else -> 0
            }
        }

    private val _rotationDegrees = MutableStateFlow(deviceDefaultRotation)
    val rotationDegrees: StateFlow<Int> = _rotationDegrees

    fun setRotation(degrees: Int) {
        _rotationDegrees.value = degrees
        activeTextureView?.let { tv ->
            if (tv.isAvailable) {
                tv.post {
                    configureTransform(tv.width, tv.height)
                }
            }
        }
    }

    fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val tv = activeTextureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val rotation = _rotationDegrees.value
        val matrix = Matrix()

        if (rotation == 90 || rotation == 270) {
            val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = RectF(0f, 0f, PREVIEW_HEIGHT.toFloat(), PREVIEW_WIDTH.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()

            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

            val scale = maxOf(
                viewWidth.toFloat() / PREVIEW_HEIGHT,
                viewHeight.toFloat() / PREVIEW_WIDTH
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(rotation.toFloat(), centerX, centerY)
        } else if (rotation == 180) {
            matrix.postRotate(180f, viewWidth / 2f, viewHeight / 2f)
        }

        tv.setTransform(matrix)
        Log.i(TAG, "Configured TextureView transform: rotation=$rotation, view=${viewWidth}x${viewHeight}")
    }

    @SuppressLint("MissingPermission")
    fun startCamera(textureView: TextureView, cameraId: String = CAMERA_ID_DEFAULT) {
        activeTextureView = textureView
        _activeCameraId.value = cameraId

        if (textureView.isAvailable) {
            configureTransform(textureView.width, textureView.height)
            openCameraWithTexture(textureView.surfaceTexture!!, cameraId)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                    openCameraWithTexture(surface, _activeCameraId.value)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                }

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
                                val bm = tv.bitmap
                                if (bm != null) {
                                    val matrix = Matrix()
                                    tv.getTransform(matrix)
                                    val transformed = if (!matrix.isIdentity) {
                                        val rot = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, matrix, true)
                                        if (rot != bm) bm.recycle()
                                        rot
                                    } else {
                                        bm
                                    }
                                    val scaledW = 640
                                    val scaledH = (640f * transformed.height / transformed.width).toInt()
                                    val scaled = Bitmap.createScaledBitmap(transformed, scaledW, scaledH, true)
                                    val out = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
                                    if (scaled != transformed) transformed.recycle()
                                    scaled.recycle()
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
     * - SMALL: 640x400 (or scaled), JPEG Q70 (~25-40 KB, low tokens)
     * - FULL: full native resolution, JPEG Q95 (~200-350 KB, high detail)
     */
    suspend fun captureFrame(resolution: Resolution): ByteArray = withContext(Dispatchers.IO) {
        val texture = activeTextureView

        if (texture != null && texture.isAvailable) {
            return@withContext withContext(Dispatchers.Main) {
                val quality = if (resolution == Resolution.SMALL) 70 else 95

                val rawBitmap = texture.bitmap
                    ?: throw IllegalStateException("Failed to grab bitmap from TextureView")

                val matrix = Matrix()
                texture.getTransform(matrix)

                val transformedBitmap = if (!matrix.isIdentity) {
                    val rot = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                    if (rot != rawBitmap) rawBitmap.recycle()
                    rot
                } else {
                    rawBitmap
                }

                val finalBitmap = if (resolution == Resolution.SMALL) {
                    val scaleFactor = 0.5f
                    val scaledW = (transformedBitmap.width * scaleFactor).toInt()
                    val scaledH = (transformedBitmap.height * scaleFactor).toInt()
                    val scaled = Bitmap.createScaledBitmap(transformedBitmap, scaledW, scaledH, true)
                    if (scaled != transformedBitmap) {
                        transformedBitmap.recycle()
                    }
                    scaled
                } else {
                    transformedBitmap
                }

                val stream = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                finalBitmap.recycle()
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
