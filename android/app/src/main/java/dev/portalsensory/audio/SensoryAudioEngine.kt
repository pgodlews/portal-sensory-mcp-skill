package dev.portalsensory.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SensoryAudioEngine {

    companion object {
        private const val TAG = "SensoryAudioEngine"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 50ms chunk = 800 samples = 1600 bytes
        const val CHUNK_SAMPLES = 800
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
        // Ring buffer holds 15 seconds of audio
        const val RING_BUFFER_SECONDS = 15
        const val RING_BUFFER_BYTES = SAMPLE_RATE * 2 * RING_BUFFER_SECONDS
        const val NUM_FREQUENCY_BANDS = 8
    }

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    private val _peakDb = MutableStateFlow(-60f)
    val peakDb: StateFlow<Float> = _peakDb

    // 8 frequency bands: Sub (60-250), Low-Mid (250-500), Mid (500-1k), High-Mid (1k-2k), Presence (2k-4k), High (4k-6k), Brilliance (6k-8k), Air
    private val _frequencyBands = MutableStateFlow(FloatArray(NUM_FREQUENCY_BANDS) { 0f })
    val frequencyBands: StateFlow<FloatArray> = _frequencyBands

    // Circular ring buffer for recent audio
    private val ringBuffer = ByteArray(RING_BUFFER_BYTES)
    private var writeHead = 0
    private var totalBytesWritten = 0L
    private val ringLock = Any()

    @SuppressLint("MissingPermission")
    fun start() {
        if (_isRecording.value) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(CHUNK_BYTES * 4)

        try {
            // Prefer VOICE_COMMUNICATION for Qualcomm beamforming/AEC, fallback to MIC
            var record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord VOICE_COMMUNICATION failed, falling back to MIC")
                record.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not be initialized")
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()
            _isRecording.value = true

            recordJob = scope.launch {
                val buffer = ByteArray(CHUNK_BYTES)
                val sampleBuffer = ShortArray(CHUNK_SAMPLES)

                while (isActive && _isRecording.value) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        // Write to ring buffer
                        synchronized(ringLock) {
                            for (i in 0 until readBytes) {
                                ringBuffer[writeHead] = buffer[i]
                                writeHead = (writeHead + 1) % RING_BUFFER_BYTES
                            }
                            totalBytesWritten += readBytes
                        }

                        // Convert bytes to shorts
                        val samplesRead = readBytes / 2
                        ByteBuffer.wrap(buffer, 0, readBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(sampleBuffer, 0, samplesRead)

                        // Compute RMS & dB
                        var sumSq = 0.0
                        var maxPeak = 0
                        for (i in 0 until samplesRead) {
                            val sample = sampleBuffer[i].toInt()
                            sumSq += sample * sample
                            val abs = kotlin.math.abs(sample)
                            if (abs > maxPeak) maxPeak = abs
                        }

                        val rms = sqrt(sumSq / samplesRead)
                        val normAmp = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        _amplitude.value = normAmp

                        val db = if (rms > 1.0) {
                            (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-60f, 0f)
                        } else {
                            -60f
                        }
                        _peakDb.value = db

                        // Compute 8 frequency bands
                        val bands = computeFrequencyBands(sampleBuffer, samplesRead)
                        _frequencyBands.value = bands
                    } else if (readBytes < 0) {
                        delay(20)
                    }
                }
            }
            Log.i(TAG, "Audio recording active at ${SAMPLE_RATE}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio engine", e)
            stop()
        }
    }

    private fun computeFrequencyBands(samples: ShortArray, length: Int): FloatArray {
        val bands = FloatArray(NUM_FREQUENCY_BANDS)
        if (length < 64) return bands

        // Target center frequencies for the 8 bands (Hz)
        val centerFreqs = doubleArrayOf(120.0, 300.0, 600.0, 1200.0, 2000.0, 3500.0, 5000.0, 7000.0)

        for (b in 0 until NUM_FREQUENCY_BANDS) {
            val freq = centerFreqs[b]
            val k = (freq * length / SAMPLE_RATE).toInt().coerceIn(1, length / 2 - 1)
            val omega = 2.0 * Math.PI * k / length
            var real = 0.0
            var imag = 0.0
            for (n in 0 until length) {
                // Apply Hann window
                val window = 0.5 * (1 - cos(2.0 * Math.PI * n / (length - 1)))
                val valWindowed = samples[n] * window
                real += valWindowed * cos(omega * n)
                imag -= valWindowed * sin(omega * n)
            }
            val mag = sqrt(real * real + imag * imag) / (length / 2)
            // Normalize to 0..1 range with log scaling
            val normMag = (mag / 8000.0).toFloat().coerceIn(0f, 1f)
            bands[b] = normMag
        }
        return bands
    }

    /**
     * Record audio for the specified duration or extract from ring buffer.
     * Returns standard WAV encoded byte array.
     */
    suspend fun recordWav(durationSeconds: Int): ByteArray = withContext(Dispatchers.IO) {
        val targetSeconds = durationSeconds.coerceIn(1, 15)
        val targetBytes = targetSeconds * SAMPLE_RATE * 2

        val pcmData = ByteArray(targetBytes)
        var bytesCaptured = 0

        // If ring buffer already has enough recent audio, we can capture a fresh buffer
        // Let's record fresh live audio for the requested duration:
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (targetSeconds * 1000L)
        val outStream = ByteArrayOutputStream()

        while (System.currentTimeMillis() < endTime && bytesCaptured < targetBytes) {
            val chunkSize = CHUNK_BYTES.coerceAtMost(targetBytes - bytesCaptured)
            val temp = ByteArray(chunkSize)

            // Read directly from audio stream if possible, or from ring buffer
            delay(40)
            synchronized(ringLock) {
                val available = totalBytesWritten.coerceAtMost(RING_BUFFER_BYTES.toLong()).toInt()
                if (available > 0) {
                    val toCopy = chunkSize.coerceAtMost(available)
                    var readIdx = (writeHead - toCopy + RING_BUFFER_BYTES) % RING_BUFFER_BYTES
                    for (i in 0 until toCopy) {
                        temp[i] = ringBuffer[readIdx]
                        readIdx = (readIdx + 1) % RING_BUFFER_BYTES
                    }
                    outStream.write(temp, 0, toCopy)
                    bytesCaptured += toCopy
                }
            }
        }

        val rawPcm = outStream.toByteArray()
        return@withContext pcmToWav(rawPcm, SAMPLE_RATE, 1, 16)
    }

    /**
     * Wrap raw 16-bit mono PCM into a standard 44-byte RIFF/WAV format
     */
    fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = dataSize + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // Subchunk1Size (16 for PCM)
            putShort(1.toShort()) // AudioFormat (1 for PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val wavStream = ByteArrayOutputStream(header.size + pcmData.size)
        wavStream.write(header)
        wavStream.write(pcmData)
        return wavStream.toByteArray()
    }

    fun stop() {
        _isRecording.value = false
        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord", e)
        }
        audioRecord = null
        _amplitude.value = 0f
        _peakDb.value = -60f
        _frequencyBands.value = FloatArray(NUM_FREQUENCY_BANDS) { 0f }
    }
}
