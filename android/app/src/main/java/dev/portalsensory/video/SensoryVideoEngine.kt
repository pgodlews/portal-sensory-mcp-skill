package dev.portalsensory.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class SensoryVideoEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "SensoryVideoEngine"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val BITRATE = 2_000_000 // 2 Mbps
        const val FPS = 15
        const val I_FRAME_INTERVAL = 1 // Keyframe every 1s
    }

    private var activeTextureView: TextureView? = null

    fun setTextureView(textureView: TextureView) {
        activeTextureView = textureView
    }

    /**
     * Records a video clip of the specified duration (1..15s) using MediaCodec + MediaMuxer
     */
    suspend fun recordVideo(durationSeconds: Int): ByteArray = withContext(Dispatchers.IO) {
        val seconds = durationSeconds.coerceIn(1, 15)
        val outputFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val texture = activeTextureView ?: throw IllegalStateException("Preview TextureView not attached")

        val totalFrames = seconds * FPS
        val frameIntervalMs = 1000L / FPS

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val yuvBuffer = ByteArray(WIDTH * HEIGHT * 3 / 2)
            val argbPixels = IntArray(WIDTH * HEIGHT)

            Log.i(TAG, "Starting video recording: $seconds s ($totalFrames frames) -> ${outputFile.name}")

            var frameCount = 0
            val startTimeUs = System.nanoTime() / 1000L

            while (frameCount < totalFrames) {
                val frameStartMs = System.currentTimeMillis()

                // Grab frame from TextureView on Main thread
                val bitmap = withContext(Dispatchers.Main) {
                    texture.getBitmap(WIDTH, HEIGHT)
                }

                if (bitmap != null) {
                    bitmap.getPixels(argbPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    bitmap.recycle()
                    argbToYuv420SemiPlanar(argbPixels, yuvBuffer, WIDTH, HEIGHT)

                    // Feed to encoder
                    val inputBufferIndex = encoder.dequeueInputBuffer(10_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(yuvBuffer)
                        val presentationTimeUs = (System.nanoTime() / 1000L) - startTimeUs
                        encoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            yuvBuffer.size,
                            presentationTimeUs,
                            if (frameCount == totalFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                        frameCount++
                    }
                }

                // Drain encoder
                var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 5_000L)
                while (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0 && encodedData != null) {
                        if (!muxerStarted) {
                            val newFormat = encoder.outputFormat
                            trackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }

                    encoder.releaseOutputBuffer(outIndex, false)
                    outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                }

                val elapsed = System.currentTimeMillis() - frameStartMs
                val waitMs = frameIntervalMs - elapsed
                if (waitMs > 0) {
                    delay(waitMs)
                }
            }

            // Drain remaining EOS
            var eos = false
            var drainTries = 0
            while (!eos && drainTries < 10) {
                drainTries++
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (bufferInfo.size != 0 && encodedData != null && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eos = true
                    }
                } else {
                    delay(10)
                }
            }

            Log.i(TAG, "Finished video encoding: $frameCount frames recorded")
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping encoder", e)
            }
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping muxer", e)
            }
        }

        val bytes = outputFile.readBytes()
        outputFile.delete()
        return@withContext bytes
    }

    private fun argbToYuv420SemiPlanar(argb: IntArray, yuv: ByteArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = (y.coerceIn(0, 255)).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = (u.coerceIn(0, 255)).toByte()
                    yuv[uvIndex++] = (v.coerceIn(0, 255)).toByte()
                }
            }
        }
    }

    fun release() {
        activeTextureView = null
    }
}
