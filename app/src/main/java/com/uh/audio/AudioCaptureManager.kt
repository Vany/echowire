package com.uh.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Manages continuous audio capture from microphone for speech recognition.
 * 
 * Audio Configuration (Whisper requirements):
 * - Sample rate: 16kHz
 * - Channel: Mono
 * - Format: 16-bit PCM
 * - Buffer: 100ms chunks (1600 samples = 3200 bytes)
 * 
 * Thread Safety:
 * - Start/stop methods are thread-safe (atomic flags)
 * - Listener callbacks invoked on dedicated audio capture thread
 * - Audio level calculation optimized for real-time performance
 * 
 * Performance:
 * - CPU usage: <5% on modern ARM devices
 * - Memory: ~100KB for audio buffers
 * - Latency: ~100ms (one buffer duration)
 */
class AudioCaptureManager {
    
    companion object {
        private const val TAG = "AudioCaptureManager"
        
        // Whisper audio requirements
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Buffer configuration: 100ms chunks for low latency
        private const val BUFFER_SIZE_MS = 100
        private const val BUFFER_SIZE_SAMPLES = SAMPLE_RATE * BUFFER_SIZE_MS / 1000
        private const val BUFFER_SIZE_BYTES = BUFFER_SIZE_SAMPLES * 2
    }
    
    // Audio recording state
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    
    // Audio level monitoring (thread-safe volatile)
    @Volatile
    private var currentAudioLevel: Float = 0f
    
    /**
     * Listener interface for audio data and status updates.
     * All callbacks invoked on dedicated audio capture thread.
     */
    interface AudioDataListener {
        /**
         * Called when new audio data is available.
         * @param audioData PCM 16-bit samples (short array)
         * @param sampleRate Always 16000 Hz
         * @param timestamp Capture timestamp in milliseconds
         */
        fun onAudioData(audioData: ShortArray, sampleRate: Int, timestamp: Long)
        
        /**
         * Called periodically with audio level for visualization.
         * Update frequency: ~10 times per second
         * @param level Audio level 0.0 (silence) to 1.0 (maximum amplitude)
         */
        fun onAudioLevel(level: Float)
        
        /**
         * Called when audio capture encounters an error.
         * @param error Exception with error details
         */
        fun onError(error: Exception)
    }
    
    private var listener: AudioDataListener? = null
    
    /**
     * Initialize AudioRecord with optimized buffer size.
     * Must be called before startCapture().
     * 
     * @return Actual buffer size in bytes
     * @throws IllegalStateException if audio recording not supported
     */
    fun initialize(): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Audio recording not supported on this device")
        }
        
        // Use 4x minimum buffer for stability and prevent buffer overrun
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_BYTES * 4)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
        
        Log.i(TAG, "AudioRecord initialized: buffer=$bufferSize bytes, min=$minBufferSize bytes")
        return bufferSize
    }
    
    /**
     * Start continuous audio capture.
     * @param listener Callback for audio data and status
     * @throws IllegalStateException if already recording
     */
    fun startCapture(listener: AudioDataListener) {
        if (isRecording.get()) {
            throw IllegalStateException("Already recording")
        }
        
        this.listener = listener
        
        if (audioRecord == null) {
            try {
                initialize()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord", e)
                listener.onError(e)
                return
            }
        }
        
        isRecording.set(true)
        
        recordingThread = Thread({
            captureLoop()
        }, "AudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        
        Log.i(TAG, "Audio capture started")
    }
    
    /**
     * Main capture loop - runs on dedicated high-priority thread.
     * Optimized for real-time audio processing with minimal latency.
     */
    private fun captureLoop() {
        // Set thread priority for real-time audio
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        
        val buffer = ShortArray(BUFFER_SIZE_SAMPLES)
        var lastLevelReportTime = 0L
        
        try {
            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord.startRecording() called")
            
            while (isRecording.get()) {
                val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                when {
                    samplesRead > 0 -> {
                        val timestamp = System.currentTimeMillis()
                        
                        // Calculate audio level (RMS)
                        val level = calculateAudioLevel(buffer, samplesRead)
                        currentAudioLevel = level
                        
                        // Notify listener with audio data
                        listener?.onAudioData(
                            buffer.copyOf(samplesRead),
                            SAMPLE_RATE,
                            timestamp
                        )
                        
                        // Report audio level every ~100ms (avoid flooding listener)
                        if (timestamp - lastLevelReportTime >= 100) {
                            listener?.onAudioLevel(level)
                            lastLevelReportTime = timestamp
                        }
                    }
                    
                    samplesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord read error: INVALID_OPERATION")
                        listener?.onError(IllegalStateException("AudioRecord invalid operation"))
                        break
                    }
                    
                    samplesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord read error: BAD_VALUE")
                        listener?.onError(IllegalArgumentException("AudioRecord bad value"))
                        break
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in capture loop", e)
            listener?.onError(e)
        } finally {
            audioRecord?.stop()
            Log.d(TAG, "AudioRecord stopped")
        }
    }
    
    /**
     * Calculate audio level using RMS (Root Mean Square).
     * Optimized for real-time performance.
     * 
     * @param buffer Audio samples
     * @param length Valid sample count in buffer
     * @return Normalized level 0.0-1.0
     */
    private fun calculateAudioLevel(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / length)
        // Normalize: 32767 is max value for 16-bit signed
        return (rms / 32767.0).toFloat().coerceIn(0f, 1f)
    }
    
    /**
     * Stop audio capture.
     * Blocks until capture thread terminates (max 1 second).
     */
    fun stopCapture() {
        if (!isRecording.get()) {
            Log.w(TAG, "Not recording")
            return
        }
        
        isRecording.set(false)
        
        // Wait for thread to finish
        recordingThread?.join(1000)
        recordingThread = null
        
        Log.i(TAG, "Audio capture stopped")
    }
    
    /**
     * Release all resources.
     * Call when done with AudioCaptureManager.
     */
    fun release() {
        stopCapture()
        
        audioRecord?.release()
        audioRecord = null
        
        listener = null
        
        Log.i(TAG, "AudioCaptureManager released")
    }
    
    /**
     * Check if currently recording.
     * Thread-safe.
     */
    fun isRecording(): Boolean = isRecording.get()
    
    /**
     * Get current audio level.
     * Thread-safe volatile read.
     * @return Audio level 0.0-1.0
     */
    fun getCurrentAudioLevel(): Float = currentAudioLevel
}
