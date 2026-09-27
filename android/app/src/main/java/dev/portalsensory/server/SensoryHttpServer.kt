package dev.portalsensory.server

import android.util.Log
import dev.portalsensory.audio.SensoryAudioEngine
import dev.portalsensory.camera.SensoryCameraEngine
import dev.portalsensory.video.SensoryVideoEngine
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream

class SensoryHttpServer(
    port: Int = 8765,
    private val cameraEngine: SensoryCameraEngine,
    private val audioEngine: SensoryAudioEngine,
    private val videoEngine: SensoryVideoEngine
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "SensoryHttpServer"
    }

    data class OverlayState(
        val message: String = "AI AGENT STANDBY",
        val subtext: String = "Ready for visual / acoustic queries",
        val colorHex: String = "#00E5FF", // Cyan accent
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _overlayState = MutableStateFlow(OverlayState())
    val overlayState: StateFlow<OverlayState> = _overlayState

    private val _requestCount = MutableStateFlow(0)
    val requestCount: StateFlow<Int> = _requestCount

    override fun serve(session: IHTTPSession): Response {
        _requestCount.value += 1
        val uri = session.uri
        val method = session.method
        val params = session.parameters

        Log.d(TAG, "Request: $method $uri")

        val response = try {
            when {
                uri == "/health" || uri == "/status" -> handleStatus()
                uri == "/capture/frame" && method == Method.GET -> handleCaptureFrame(params)
                uri == "/capture/audio" && (method == Method.POST || method == Method.GET) -> handleCaptureAudio(params)
                uri == "/capture/video" && (method == Method.POST || method == Method.GET) -> handleCaptureVideo(params)
                uri == "/display/overlay" && method == Method.POST -> handleDisplayOverlay(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $uri", e)
            val errJson = JSONObject().apply {
                put("status", "error")
                put("message", e.message ?: "Unknown server error")
            }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", errJson.toString())
        }

        // Add CORS headers
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    private fun handleStatus(): Response {
        val bandsArray = org.json.JSONArray()
        for (b in audioEngine.frequencyBands.value) {
            bandsArray.put(String.format("%.2f", b).toDouble())
        }

        val json = JSONObject().apply {
            put("status", "ok")
            put("device", "Meta Portal+ (cipher)")
            put("camera", JSONObject().apply {
                put("active", cameraEngine.isCameraActive.value)
                put("cameraId", cameraEngine.activeCameraId.value)
                put("preview_width", SensoryCameraEngine.PREVIEW_WIDTH)
                put("preview_height", SensoryCameraEngine.PREVIEW_HEIGHT)
                put("available_resolutions", org.json.JSONArray(listOf("small", "full")))
            })
            put("audio", JSONObject().apply {
                put("active", audioEngine.isRecording.value)
                put("sampleRate", SensoryAudioEngine.SAMPLE_RATE)
                put("amplitude", String.format("%.3f", audioEngine.amplitude.value).toDouble())
                put("peakDb", String.format("%.1f", audioEngine.peakDb.value).toDouble())
                put("bands", bandsArray)
            })
            put("overlay", JSONObject().apply {
                put("message", _overlayState.value.message)
                put("subtext", _overlayState.value.subtext)
                put("color", _overlayState.value.colorHex)
            })
            put("requests_served", _requestCount.value)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
    }

    private fun handleCaptureFrame(params: Map<String, List<String>>): Response {
        val resParam = params["res"]?.firstOrNull()?.lowercase() ?: "small"
        val resolution = if (resParam == "full") {
            SensoryCameraEngine.Resolution.FULL
        } else {
            SensoryCameraEngine.Resolution.SMALL
        }

        val jpegBytes = runBlocking {
            cameraEngine.captureFrame(resolution)
        }

        val response = newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(jpegBytes),
            jpegBytes.size.toLong()
        )
        response.addHeader("Content-Disposition", "inline; filename=\"portal_frame_${resParam}.jpg\"")
        response.addHeader("X-Portal-Resolution", resParam)
        return response
    }

    private fun handleCaptureAudio(params: Map<String, List<String>>): Response {
        val durationParam = params["duration"]?.firstOrNull()?.toIntOrNull() ?: 3
        val seconds = durationParam.coerceIn(1, 15)

        val wavBytes = runBlocking {
            audioEngine.recordWav(seconds)
        }

        val response = newFixedLengthResponse(
            Response.Status.OK,
            "audio/wav",
            ByteArrayInputStream(wavBytes),
            wavBytes.size.toLong()
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"portal_audio_${seconds}s.wav\"")
        response.addHeader("X-Audio-Duration", seconds.toString())
        return response
    }

    private fun handleCaptureVideo(params: Map<String, List<String>>): Response {
        val durationParam = params["duration"]?.firstOrNull()?.toIntOrNull() ?: 5
        val seconds = durationParam.coerceIn(1, 15)

        val mp4Bytes = runBlocking {
            videoEngine.recordVideo(seconds)
        }

        val response = newFixedLengthResponse(
            Response.Status.OK,
            "video/mp4",
            ByteArrayInputStream(mp4Bytes),
            mp4Bytes.size.toLong()
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"portal_video_${seconds}s.mp4\"")
        response.addHeader("X-Video-Duration", seconds.toString())
        return response
    }

    private fun handleDisplayOverlay(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val postData = map["postData"]

        var message = "AI AGENT NOTICE"
        var subtext = ""
        var color = "#00E5FF"

        if (!postData.isNullOrBlank()) {
            try {
                val json = JSONObject(postData)
                message = json.optString("message", message)
                subtext = json.optString("subtext", subtext)
                color = json.optString("color", color)
            } catch (e: Exception) {
                message = postData
            }
        }

        _overlayState.value = OverlayState(
            message = message,
            subtext = subtext,
            colorHex = color,
            timestamp = System.currentTimeMillis()
        )

        val reply = JSONObject().apply {
            put("status", "ok")
            put("message", "Overlay updated")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", reply.toString())
    }
}
