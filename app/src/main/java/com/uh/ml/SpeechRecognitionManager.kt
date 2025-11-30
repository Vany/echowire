package com.uh.ml

import android.content.Context
import android.util.Log
import com.uh.audio.AudioPreprocessor
import com.uh.audio.CircularAudioBuffer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level manager for continuous speech recognition with embeddings
 * Orchestrates audio buffering, preprocessing, inference, decoding, and embedding generation
 * Thread-safe, handles lifecycle and error recovery
 */
class SpeechRecognitionManager(
    private val context: Context,
    private val modelFile: File,
    private val vocabFile: File,
    private val embeddingModelFile: File,
    private val embeddingVocabFile: File
) {
    
    companion object {
        private const val TAG = "SpeechRecognitionManager"
        
        // Recognition parameters
        private const val MIN_AUDIO_DURATION_SEC = 1.0f  // Minimum 1 second of audio
        private const val MAX_AUDIO_DURATION_SEC = 30.0f // Maximum 30 seconds (Whisper limit)
        private const val BUFFER_CAPACITY_SEC = 30.0f    // Buffer size in seconds
        
        // Sample rate (must match AudioCaptureManager)
        private const val SAMPLE_RATE = 16000
    }
    
    // Components
    private val audioPreprocessor = AudioPreprocessor()
    private lateinit var whisperInference: WhisperInference
    private lateinit var tokenizer: WhisperTokenizer
    private lateinit var embeddingManager: EmbeddingManager
    
    // Audio buffering
    private val audioBuffer = CircularAudioBuffer(
        capacitySamples = (BUFFER_CAPACITY_SEC * SAMPLE_RATE).toInt()
    )
    
    // Inference thread
    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SpeechInferenceThread").apply {
            priority = Thread.NORM_PRIORITY
        }
    }
    
    // State
    private val isInitialized = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    
    // Statistics
    @Volatile
    private var totalInferences = 0L
    @Volatile
    private var totalInferenceTimeMs = 0L
    
    /**
     * Listener interface for recognition results
     */
    interface RecognitionListener {
        /**
         * Called when transcription is complete with embedding
         * 
         * @param text Recognized text
         * @param embedding 384-dimensional semantic embedding (L2 normalized)
         * @param language Detected language code (e.g., "en", "ru")
         * @param startTime Audio start timestamp (milliseconds)
         * @param endTime Audio end timestamp (milliseconds)
         * @param processingTimeMs Time taken to process (milliseconds)
         */
        fun onTranscription(
            text: String,
            embedding: FloatArray,
            language: String?,
            startTime: Long,
            endTime: Long,
            processingTimeMs: Long
        )
        
        /**
         * Called when processing starts
         */
        fun onProcessingStarted()
        
        /**
         * Called when an error occurs
         */
        fun onError(error: Exception)
    }
    
    private var listener: RecognitionListener? = null
    
    /**
     * Initialize models (must call before processing)
     * Blocks until models are loaded
     * 
     * @throws Exception if initialization fails
     */
    fun initialize() {
        if (isInitialized.get()) {
            Log.w(TAG, "Already initialized")
            return
        }
        
        Log.i(TAG, "Initializing SpeechRecognitionManager...")
        val startTime = System.currentTimeMillis()
        
        try {
            // Load Whisper model (use factory with default model)
            Log.d(TAG, "Loading Whisper model from: ${modelFile.absolutePath}")
            val modelsDir = modelFile.parentFile
            whisperInference = WhisperInferenceFactory.createDefault(context, modelsDir!!)
            whisperInference.load()
            
            val modelInfo = whisperInference.getModelInfo()
            Log.i(TAG, "✓ Whisper model loaded: ${modelInfo.name} (${modelInfo.provider})")
            Log.i(TAG, "  Architecture: ${modelInfo.architecture}")
            Log.i(TAG, "  Size: ${modelInfo.sizeBytes / (1024 * 1024)} MB")
            
            // Load tokenizer
            Log.d(TAG, "Loading tokenizer from: ${vocabFile.absolutePath}")
            tokenizer = WhisperTokenizer(vocabFile)
            Log.i(TAG, "✓ Tokenizer loaded: isLoaded=${tokenizer.isLoaded}")
            
            // Load embedding model
            Log.d(TAG, "Loading embedding model from: ${embeddingModelFile.absolutePath}")
            embeddingManager = EmbeddingManager(context, embeddingModelFile, embeddingVocabFile)
            embeddingManager.load()
            Log.i(TAG, "✓ Embedding model loaded")
            
            isInitialized.set(true)
            
            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Initialization complete in ${loadTime}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            e.printStackTrace()
            throw e
        }
    }
    
    /**
     * Set recognition listener
     */
    fun setListener(listener: RecognitionListener?) {
        this.listener = listener
    }
    
    /**
     * Process incoming audio data
     * Called from audio capture thread
     * 
     * @param samples PCM audio samples (16-bit, 16kHz, mono)
     * @param timestamp Timestamp of audio chunk (milliseconds)
     * @param isSpeech True if VAD detected speech in this chunk
     */
    fun onAudioData(samples: ShortArray, timestamp: Long, isSpeech: Boolean) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Not initialized, ignoring audio data")
            return
        }
        
        // Add to buffer
        audioBuffer.add(samples, timestamp)
        
        // Trigger inference if conditions met
        if (shouldTriggerInference(isSpeech)) {
            triggerInference()
        }
    }
    
    /**
     * Determine if we should trigger inference
     * Conditions:
     * - Speech is detected
     * - Buffer has minimum audio duration
     * - Not currently processing
     */
    private fun shouldTriggerInference(isSpeech: Boolean): Boolean {
        if (!isSpeech) return false
        if (isProcessing.get()) return false
        
        val duration = audioBuffer.durationSeconds()
        if (duration < MIN_AUDIO_DURATION_SEC) return false
        
        return true
    }
    
    /**
     * Trigger inference on background thread
     */
    private fun triggerInference() {
        // Set processing flag (prevents duplicate inference)
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }
        
        inferenceExecutor.execute {
            try {
                processBuffer()
            } finally {
                isProcessing.set(false)
            }
        }
    }
    
    /**
     * Main processing pipeline
     * Runs on inference thread
     */
    private fun processBuffer() {
        val pipelineStart = System.currentTimeMillis()
        
        try {
            listener?.onProcessingStarted()
            
            // 1. Get audio from buffer
            val audioSamples = audioBuffer.getAll()
            if (audioSamples.isEmpty()) {
                Log.w(TAG, "Buffer is empty, skipping inference")
                return
            }
            
            val startTime = audioBuffer.startTimestamp
            val endTime = audioBuffer.endTimestamp
            val durationSec = audioSamples.size / SAMPLE_RATE.toFloat()
            
            Log.d(TAG, "Processing audio: ${audioSamples.size} samples, ${durationSec}s")
            
            // 2. Preprocessing - PCM to mel spectrogram
            val preprocessStart = System.currentTimeMillis()
            val melSpectrogram = audioPreprocessor.pcmToMelSpectrogram(audioSamples)
            val preprocessTime = System.currentTimeMillis() - preprocessStart
            Log.d(TAG, "Preprocessing: ${melSpectrogram.size} frames in ${preprocessTime}ms")
            
            // 3. Inference - mel spectrogram to token IDs
            val inferenceStart = System.currentTimeMillis()
            val audioLengthMs = (audioSamples.size * 1000L) / SAMPLE_RATE
            val result = whisperInference.runInference(melSpectrogram, audioLengthMs)
            val inferenceTime = result.inferenceTimeMs
            val tokenIds = result.tokenIds
            Log.d(TAG, "Inference: ${tokenIds.size} tokens in ${inferenceTime}ms (RTF: ${"%.2f".format(result.realTimeFactor)})")
            
            // 4. Decoding - token IDs to text
            val decodeStart = System.currentTimeMillis()
            try {
                val (text, stats) = tokenizer.decodeWithStats(tokenIds)
                val decodeTime = System.currentTimeMillis() - decodeStart
                Log.d(TAG, "Decoding: \"$text\" in ${decodeTime}ms")
                
                // 5. Language detection
                val language = stats.language
                Log.d(TAG, "Detected language: $language")
                
                // 5.1. Language filtering - accept only Russian and English
                val allowedLanguages = setOf("ru", "en")
                if (language !in allowedLanguages) {
                    Log.w(TAG, "LANGUAGE FILTER: Detected '$language' but only accepting 'ru' or 'en'. Skipping transcription: \"$text\"")
                    Log.i(TAG, "Buffer cleared. Waiting for Russian or English speech...")
                    // Clear buffer and return without notifying listener
                    audioBuffer.clear()
                    return
                }
                
                Log.i(TAG, "Language accepted: $language - \"$text\"")
                
                // 6. Generate embedding for recognized text
                val embeddingStart = System.currentTimeMillis()
                val embedding = if (text.isNotBlank()) {
                    embeddingManager.encode(text.trim())
                } else {
                    FloatArray(384) { 0f }  // Zero embedding for empty text
                }
                val embeddingTime = System.currentTimeMillis() - embeddingStart
                Log.d(TAG, "Embedding: ${embedding.size} dims in ${embeddingTime}ms")
                
                // Total processing time
                val totalTime = System.currentTimeMillis() - pipelineStart
                
                // Update statistics
                totalInferences++
                totalInferenceTimeMs += totalTime
                
                Log.i(TAG, "Pipeline complete: ${totalTime}ms " +
                        "(preprocess=${preprocessTime}ms, inference=${inferenceTime}ms, " +
                        "decode=${decodeTime}ms, embedding=${embeddingTime}ms)")
                
                // 7. Notify listener
                if (text.isNotBlank()) {
                    listener?.onTranscription(
                        text = text.trim(),
                        embedding = embedding,
                        language = language,
                        startTime = startTime,
                        endTime = endTime,
                        processingTimeMs = totalTime
                    )
                } else {
                    Log.d(TAG, "Empty transcription, skipping callback")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "DECODING FAILED!", e)
                e.printStackTrace()
                
                // Fallback: return token IDs as string for debugging
                val fallbackText = tokenIds.joinToString("")
                Log.w(TAG, "Fallback: returning concatenated token IDs: $fallbackText")
                
                listener?.onTranscription(
                    text = fallbackText,
                    embedding = FloatArray(384) { 0f },
                    language = "fr",  // Default
                    startTime = startTime,
                    endTime = endTime,
                    processingTimeMs = System.currentTimeMillis() - pipelineStart
                )
            }
            
            // 8. Clear buffer for next segment
            audioBuffer.clear()
            
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed", e)
            listener?.onError(e)
            
            // Clear buffer to recover
            audioBuffer.clear()
        }
    }
    
    /**
     * Force process current buffer (for testing or manual trigger)
     * Non-blocking, returns immediately
     */
    fun forceProcess() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Not initialized, cannot force process")
            return
        }
        
        triggerInference()
    }
    
    /**
     * Clear audio buffer
     */
    fun clearBuffer() {
        audioBuffer.clear()
    }
    
    /**
     * Get current buffer duration in seconds
     */
    fun getBufferDuration(): Float = audioBuffer.durationSeconds()
    
    /**
     * Get average processing time per inference
     */
    fun getAverageProcessingTime(): Long {
        return if (totalInferences > 0) {
            totalInferenceTimeMs / totalInferences
        } else {
            0L
        }
    }
    
    /**
     * Release resources
     * Call when done with manager
     */
    fun release() {
        Log.i(TAG, "Releasing SpeechRecognitionManager...")
        
        // Shutdown executor
        inferenceExecutor.shutdown()
        
        // Close models
        if (isInitialized.get()) {
            whisperInference.close()
            embeddingManager.close()
        }
        
        // Clear buffer
        audioBuffer.clear()
        
        // Clear listener
        listener = null
        
        isInitialized.set(false)
        
        Log.i(TAG, "Released. Total inferences: $totalInferences, " +
                "avg time: ${getAverageProcessingTime()}ms")
    }
    
    /**
     * Check if manager is ready to process audio
     */
    fun isReady(): Boolean = isInitialized.get()
    
    /**
     * Check if currently processing
     */
    fun isProcessing(): Boolean = isProcessing.get()
}
