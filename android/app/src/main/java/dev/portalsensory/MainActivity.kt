package dev.portalsensory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.portalsensory.audio.SensoryAudioEngine
import dev.portalsensory.camera.SensoryCameraEngine
import dev.portalsensory.server.SensoryHttpServer
import dev.portalsensory.ui.AudioHistogramView
import dev.portalsensory.ui.CameraPreviewView
import dev.portalsensory.video.SensoryVideoEngine
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PortalSensoryActivity"
        private const val SERVER_PORT = 8765
    }

    private lateinit var cameraEngine: SensoryCameraEngine
    private lateinit var audioEngine: SensoryAudioEngine
    private lateinit var videoEngine: SensoryVideoEngine
    private lateinit var httpServer: SensoryHttpServer

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        Log.i(TAG, "Permissions: camera=$cameraGranted, audio=$audioGranted")
        if (audioGranted) {
            audioEngine.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // WakeLock & Multicast
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portalsensory:wakelock").apply {
                acquire(12 * 60 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("portalsensory:mcast").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire locks", e)
        }

        // Initialize Engines
        cameraEngine = SensoryCameraEngine(applicationContext)
        audioEngine = SensoryAudioEngine()
        videoEngine = SensoryVideoEngine(applicationContext)

        // Start HTTP Server
        httpServer = SensoryHttpServer(
            port = SERVER_PORT,
            cameraEngine = cameraEngine,
            audioEngine = audioEngine,
            videoEngine = videoEngine
        )
        try {
            httpServer.start()
            Log.i(TAG, "HTTP Server listening on port $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }

        // Request Permissions
        checkAndRequestPermissions()

        val deviceIp = getDeviceIpAddress()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    SensoryMainScreen(
                        cameraEngine = cameraEngine,
                        audioEngine = audioEngine,
                        videoEngine = videoEngine,
                        httpServer = httpServer,
                        deviceIp = deviceIp
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            audioEngine.start()
        }
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting IP", e)
        }
        return "127.0.0.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            httpServer.stop()
            audioEngine.stop()
            cameraEngine.release()
            videoEngine.release()
            wakeLock?.let { if (it.isHeld) it.release() }
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}

@Composable
fun SensoryMainScreen(
    cameraEngine: SensoryCameraEngine,
    audioEngine: SensoryAudioEngine,
    videoEngine: SensoryVideoEngine,
    httpServer: SensoryHttpServer,
    deviceIp: String
) {
    val frequencyBands by audioEngine.frequencyBands.collectAsStateWithLifecycle()
    val peakDb by audioEngine.peakDb.collectAsStateWithLifecycle()
    val amplitude by audioEngine.amplitude.collectAsStateWithLifecycle()
    val isRecordingAudio by audioEngine.isRecording.collectAsStateWithLifecycle()
    val isCameraActive by cameraEngine.isCameraActive.collectAsStateWithLifecycle()
    val overlayState by httpServer.overlayState.collectAsStateWithLifecycle()
    val requestCount by httpServer.requestCount.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live Camera Preview (Fullscreen Background)
        CameraPreviewView(
            cameraEngine = cameraEngine,
            videoEngine = videoEngine,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Bar HUD
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand & Status Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC0A0E17))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isCameraActive) Color(0xFF00E676) else Color(0xFFFF5252))
                )
                Text(
                    text = "PORTAL SENSORY",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "API :8765",
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Connection & ADB forward badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC0A0E17))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "IP: $deviceIp",
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "adb forward tcp:8765 tcp:8765",
                    color = Color(0xFFFFD740),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Req: $requestCount",
                    color = Color(0xFF1DE9B6),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 3. Center Agent HUD Banner (Displays active agent instructions/status)
        AnimatedVisibility(
            visible = overlayState.message.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val accentColor = try {
                Color(android.graphics.Color.parseColor(overlayState.colorHex))
            } catch (e: Exception) {
                Color(0xFF00E5FF)
            }

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xDD0A0E17))
                    .border(1.dp, accentColor, RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = overlayState.message.uppercase(),
                    color = accentColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                if (overlayState.subtext.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = overlayState.subtext,
                        color = Color(0xFFCFD8DC),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 4. Bottom Strip: Audio Spectrum Histogram & Quick Indicators
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AudioHistogramView(
                frequencyBands = frequencyBands,
                peakDb = peakDb,
                amplitude = amplitude,
                modifier = Modifier.weight(1f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.width(180.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC0A0E17))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "RESOLUTIONS",
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• small (640×360)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "• full (1280×720)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
