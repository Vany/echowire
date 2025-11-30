package com.uh.ml

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Factory for creating Whisper inference implementations.
 * 
 * Supports multiple model providers and architectures:
 * - UsefulSensors: Monolithic TFLite model
 * - Vilassn: Encoder-decoder split TFLite models
 * 
 * Usage:
 * ```
 * val inference = WhisperInferenceFactory.create(
 *     context,
 *     WhisperInferenceFactory.ModelType.USEFUL_SENSORS_TINY,
 *     modelsDir
 * )
 * inference.load()
 * val result = inference.runInference(melSpec, audioLengthMs)
 * ```
 */
object WhisperInferenceFactory {
    
    private const val TAG = "WhisperInferenceFactory"
    
    /**
     * Available model types
     */
    enum class ModelType(
        val displayName: String,
        val provider: String,
        val architecture: String
    ) {
        /**
         * UsefulSensors monolithic tiny model
         * - File: whisper_tiny.tflite
         * - Size: ~66MB
         * - Performance: 743-776ms, RTF 0.09-0.96x
         * - Known issue: Language detection outputs Russian for English
         */
        USEFUL_SENSORS_TINY(
            displayName = "Tiny (UsefulSensors)",
            provider = "usefulsensors",
            architecture = "monolithic"
        ),
        
        /**
         * Vilassn encoder-decoder tiny model
         * - Files: whisper-tiny-encoder.tflite, whisper-tiny-decoder.tflite
         * - Size: ~66MB total
         * - Performance: Similar to monolithic
         * - Advantage: Correct language detection
         */
        VILASSN_TINY_ENCODER_DECODER(
            displayName = "Tiny Encoder-Decoder (Vilassn)",
            provider = "vilassn",
            architecture = "encoder-decoder"
        );
        
        companion object {
            /**
             * Get model type from string name
             */
            fun fromString(name: String): ModelType? {
                return values().find { 
                    it.name.equals(name, ignoreCase = true) ||
                    it.displayName.equals(name, ignoreCase = true)
                }
            }
        }
    }
    
    /**
     * Create inference implementation for specified model type.
     * 
     * @param context Android context
     * @param modelType Which model implementation to use
     * @param modelsDir Directory containing model files
     * @return WhisperInference implementation
     * @throws IllegalArgumentException if model files not found
     */
    fun create(
        context: Context,
        modelType: ModelType,
        modelsDir: File
    ): WhisperInference {
        
        if (!modelsDir.exists()) {
            throw IllegalArgumentException("Models directory not found: ${modelsDir.absolutePath}")
        }
        
        Log.i(TAG, "Creating inference: ${modelType.displayName}")
        Log.i(TAG, "Provider: ${modelType.provider}, Architecture: ${modelType.architecture}")
        
        return when (modelType) {
            ModelType.USEFUL_SENSORS_TINY -> {
                val modelFile = File(modelsDir, "whisper_tiny.tflite")
                if (!modelFile.exists()) {
                    throw IllegalArgumentException(
                        "UsefulSensors model not found: ${modelFile.absolutePath}\\n" +
                        "Expected file: whisper_tiny.tflite"
                    )
                }
                UsefulSensorsWhisper(context, modelFile)
            }
            
            ModelType.VILASSN_TINY_ENCODER_DECODER -> {
                val encoderFile = File(modelsDir, "whisper-tiny-encoder.tflite")
                val decoderFile = File(modelsDir, "whisper-tiny-decoder.tflite")
                
                if (!encoderFile.exists()) {
                    throw IllegalArgumentException(
                        "Vilassn encoder not found: ${encoderFile.absolutePath}\\n" +
                        "Expected file: whisper-tiny-encoder.tflite\\n" +
                        "Download from: https://github.com/vilassn/whisper_android"
                    )
                }
                
                if (!decoderFile.exists()) {
                    throw IllegalArgumentException(
                        "Vilassn decoder not found: ${decoderFile.absolutePath}\\n" +
                        "Expected file: whisper-tiny-decoder.tflite\\n" +
                        "Download from: https://github.com/vilassn/whisper_android"
                    )
                }
                
                VilassnWhisper(context, encoderFile, decoderFile)
            }
        }
    }
    
    /**
     * Create default inference (UsefulSensors tiny).
     * Convenience method for backward compatibility.
     */
    fun createDefault(context: Context, modelsDir: File): WhisperInference {
        return create(context, ModelType.USEFUL_SENSORS_TINY, modelsDir)
    }
    
    /**
     * List available models in directory.
     * Scans directory for recognized model files.
     */
    fun listAvailableModels(modelsDir: File): List<ModelType> {
        if (!modelsDir.exists()) {
            return emptyList()
        }
        
        val available = mutableListOf<ModelType>()
        
        // Check UsefulSensors
        if (File(modelsDir, "whisper_tiny.tflite").exists()) {
            available.add(ModelType.USEFUL_SENSORS_TINY)
        }
        
        // Check Vilassn
        if (File(modelsDir, "whisper-tiny-encoder.tflite").exists() &&
            File(modelsDir, "whisper-tiny-decoder.tflite").exists()) {
            available.add(ModelType.VILASSN_TINY_ENCODER_DECODER)
        }
        
        Log.i(TAG, "Available models in ${modelsDir.absolutePath}: ${available.map { it.displayName }}")
        
        return available
    }
}
