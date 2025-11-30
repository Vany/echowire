package com.uh.ml

/**
 * Common interface for all Whisper model implementations.
 * 
 * Allows swapping between different model architectures:
 * - Monolithic models (single TFLite file)
 * - Encoder-decoder split models (two TFLite files)
 * - Different model providers (usefulsensors, vilassn, etc.)
 * 
 * All implementations must support:
 * - Mel spectrogram input [numFrames × 80]
 * - Token ID array output [sequenceLength]
 * - Thread-safe inference
 * - Clean resource lifecycle
 */
interface WhisperInference {
    
    /**
     * Inference result with metadata
     */
    data class InferenceResult(
        val tokenIds: IntArray,
        val inferenceTimeMs: Long,
        val audioLengthMs: Long
    ) {
        /**
         * Real-time factor: inference_time / audio_length
         * < 1.0 = faster than real-time
         */
        val realTimeFactor: Double
            get() = inferenceTimeMs.toDouble() / audioLengthMs.toDouble()
    }
    
    /**
     * Model metadata
     */
    data class ModelInfo(
        val name: String,
        val provider: String,
        val architecture: String,
        val sizeBytes: Long,
        val maxAudioSeconds: Int,
        val vocabSize: Int
    )
    
    /**
     * Load model into memory and prepare for inference.
     * 
     * @throws IllegalStateException if model files don't exist
     * @throws IllegalArgumentException if model loading fails
     */
    fun load()
    
    /**
     * Run inference on mel spectrogram.
     * 
     * @param melSpectrogram Input [numFrames × 80]
     * @param audioLengthMs Original audio duration for RTF calculation
     * @return Inference result with token IDs and timing
     * 
     * Thread-safe: can be called from multiple threads
     */
    fun runInference(melSpectrogram: Array<FloatArray>, audioLengthMs: Long): InferenceResult
    
    /**
     * Get model information
     */
    fun getModelInfo(): ModelInfo
    
    /**
     * Check if model is loaded and ready
     */
    fun isLoaded(): Boolean
    
    /**
     * Release all model resources.
     * Must call load() again before using.
     */
    fun close()
}
