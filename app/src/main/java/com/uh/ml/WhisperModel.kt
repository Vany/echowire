package com.uh.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages Whisper TFLite model loading and inference.
 * 
 * Whisper Model Specifications:
 * - Input: Mel spectrogram [1, 80, 3000] (batch, mels, frames)
 *   - 80 mel bins
 *   - 3000 time frames = 30 seconds max at 10ms hop
 * - Output: Token IDs [1, 448] (batch, sequence length)
 *   - 448 tokens max sequence
 *   - Vocabulary size: 51865 tokens
 * 
 * Hardware Acceleration:
 * - GPU Delegate (Mali-G77) - TFLite 2.17.0 testing
 * - XNNPack (ARM NEON CPU) - fallback, 2-3x speedup
 * - 4 threads for parallel operations
 * - Expected: 200-300ms with GPU, 400-600ms with CPU (tiny model)
 * 
 * Thread Safety:
 * - Thread-safe inference via ReentrantLock
 * - One interpreter per WhisperModel instance
 * - Safe to share instance across threads
 */
class WhisperModel(
    private val context: Context,
    private val modelFile: File
) {
    
    companion object {
        private const val TAG = "WhisperModel"
        
        // Model input/output dimensions (Whisper tiny)
        const val MAX_MEL_FRAMES = 3000    // 30 seconds at 10ms hop
        const val MEL_BINS = 80            // Whisper standard
        const val MAX_TOKEN_SEQUENCE = 448 // Max output sequence length
        const val VOCAB_SIZE = 51865       // Whisper vocabulary size
        
        // Number of threads for CPU inference
        private const val NUM_THREADS = 4
    }
    
    private var interpreter: Interpreter? = null
    private val interpreterLock = ReentrantLock()
    
    @Volatile
    var isLoaded = false
        private set
    
    @Volatile
    var isGpuEnabled = false  // Always false, kept for API compatibility
        private set
    
    /**
     * Tensor metadata
     */
    data class TensorInfo(
        val shape: IntArray,
        val dataType: org.tensorflow.lite.DataType
    ) {
        override fun toString(): String {
            return "TensorInfo(shape=${shape.contentToString()}, type=$dataType)"
        }
    }
    
    /**
     * Load TFLite model with GPU acceleration (if available).
     * 
     * @throws IllegalStateException if model file doesn't exist
     * @throws IllegalArgumentException if model loading fails
     */
    fun load() {
        if (isLoaded) {
            Log.w(TAG, "Model already loaded")
            return
        }
        
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.absolutePath}")
        }
        
        Log.i(TAG, "Loading Whisper model from: ${modelFile.absolutePath}")
        Log.i(TAG, "Model size: ${modelFile.length() / (1024 * 1024)} MB")
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Load model into memory
            val modelBuffer = loadModelFile(modelFile)
            
            // Create interpreter options with acceleration
            val options = createInterpreterOptions()
            
            // Create interpreter
            interpreter = Interpreter(modelBuffer, options)
            
            isLoaded = true
            
            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Model loaded successfully in ${loadTime}ms")
            
            // Log tensor info
            logTensorInfo()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            cleanup()
            throw IllegalArgumentException("Model loading failed: ${e.message}", e)
        }
    }
    
    /**
     * Create interpreter options with hardware acceleration
     * 
     * TFLite 2.17.0: Testing GPU delegate - may still have classpath issues
     * Fallback: XNNPack provides 2-3x ARM NEON speedup
     */
    private fun createInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        
        // Set thread count for CPU operations
        options.setNumThreads(NUM_THREADS)
        
        // Try GPU delegate first (Mali-G77)
        try {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                val gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
                
                Log.i(TAG, "GPU delegate enabled (Mali-G77 via TFLite 2.17.0)")
                Log.i(TAG, "Expected inference: 200-300ms for 1s audio (tiny model)")
                isGpuEnabled = true
                
                return options
            } else {
                Log.w(TAG, "GPU delegate not compatible with this device")
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate initialization failed: ${e.message}", e)
            Log.i(TAG, "Falling back to XNNPack CPU acceleration")
        }
        
        // Fallback: Enable XNNPack for ARM CPU optimization
        options.setUseXNNPACK(true)
        
        Log.i(TAG, "Hardware acceleration: XNNPack (ARM NEON) with $NUM_THREADS threads")
        Log.i(TAG, "Expected inference: 400-600ms for 1s audio (tiny model)")
        
        isGpuEnabled = false
        
        return options
    }
    
    /**
     * Load model file into memory-mapped buffer
     */
    private fun loadModelFile(file: File): MappedByteBuffer {
        FileInputStream(file).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                0,
                fileChannel.size()
            )
        }
    }
    
    /**
     * Get input tensor information
     */
    fun getInputTensorInfo(): TensorInfo {
        checkLoaded()
        val inputTensor = interpreter!!.getInputTensor(0)
        return TensorInfo(
            shape = inputTensor.shape(),
            dataType = inputTensor.dataType()
        )
    }
    
    /**
     * Get output tensor information
     */
    fun getOutputTensorInfo(): TensorInfo {
        checkLoaded()
        val outputTensor = interpreter!!.getOutputTensor(0)
        return TensorInfo(
            shape = outputTensor.shape(),
            dataType = outputTensor.dataType()
        )
    }
    
    /**
     * Run inference on mel spectrogram
     * 
     * FIXED: Based on vilassn/whisper_android working implementation
     * Model outputs INT32 token IDs in ByteBuffer (not floats, not logits!)
     * 
     * @param melSpectrogram Input mel spectrogram [numFrames × 80]
     * @return Token IDs [sequenceLength]
     * 
     * Thread-safe: can be called from multiple threads
     */
    fun runInference(melSpectrogram: Array<FloatArray>): IntArray {
        checkLoaded()
        
        return interpreterLock.withLock {
            val startTime = System.currentTimeMillis()
            
            // Prepare input tensor [1, 80, 3000]
            val inputTensor = prepareInputTensor(melSpectrogram)
            
            // Get output tensor information
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val outputDataType = outputTensor.dataType()
            
            Log.d(TAG, "Output shape: ${outputShape.contentToString()}")
            Log.d(TAG, "Output data type: $outputDataType")
            
            // Calculate output buffer size
            val outputSize = outputShape.reduce { a, b -> a * b }
            Log.d(TAG, "Output total elements: $outputSize")
            
            // Allocate output buffer
            // Model outputs token IDs as INT32 in ByteBuffer
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)  // 4 bytes per int32
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // Run inference
            interpreter!!.run(inputTensor, outputBuffer)
            
            // Read token IDs from buffer
            outputBuffer.rewind()
            val maxTokens = minOf(MAX_TOKEN_SEQUENCE, outputSize)
            val tokenIds = IntArray(maxTokens)
            
            for (i in 0 until maxTokens) {
                if (outputBuffer.remaining() >= 4) {
                    tokenIds[i] = outputBuffer.getInt()
                } else {
                    break
                }
            }
            
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inference completed in ${inferenceTime}ms")
            Log.d(TAG, "First 20 tokens: ${tokenIds.take(20).joinToString()}")
            
            // Log some token statistics
            val nonZeroTokens = tokenIds.count { it != 0 }
            val uniqueTokens = tokenIds.filter { it != 0 }.toSet().size
            Log.d(TAG, "Token stats: $nonZeroTokens non-zero, $uniqueTokens unique")
            
            return@withLock tokenIds
        }
    }
    
    /**
     * Prepare input tensor with padding/truncation
     * 
     * Input mel spectrogram can be variable length.
     * This function reshapes to [1, 80, 3000] required by model.
     * 
     * @param melSpec Input [numFrames × 80]
     * @return Tensor [1, 80, 3000]
     */
    private fun prepareInputTensor(melSpec: Array<FloatArray>): Array<Array<FloatArray>> {
        val numFrames = melSpec.size
        val numMels = melSpec.getOrNull(0)?.size ?: MEL_BINS
        
        // Validate mel bins
        if (numMels != MEL_BINS) {
            throw IllegalArgumentException(
                "Expected $MEL_BINS mel bins, got $numMels"
            )
        }
        
        // Create padded/truncated tensor [1, 80, 3000]
        val inputTensor = Array(1) {
            Array(MEL_BINS) {
                FloatArray(MAX_MEL_FRAMES)
            }
        }
        
        // Copy mel spectrogram (transpose: [frames × mels] → [mels × frames])
        val framesToCopy = minOf(numFrames, MAX_MEL_FRAMES)
        for (frameIdx in 0 until framesToCopy) {
            for (melIdx in 0 until MEL_BINS) {
                inputTensor[0][melIdx][frameIdx] = melSpec[frameIdx][melIdx]
            }
        }
        
        // Padding is already zero-initialized (FloatArray default)
        
        if (numFrames > MAX_MEL_FRAMES) {
            Log.w(TAG, "Audio truncated: $numFrames frames → $MAX_MEL_FRAMES frames")
        } else if (numFrames < MAX_MEL_FRAMES) {
            Log.d(TAG, "Audio padded: $numFrames frames → $MAX_MEL_FRAMES frames")
        }
        
        return inputTensor
    }
    
    /**
     * Log tensor information for debugging
     */
    private fun logTensorInfo() {
        try {
            val inputInfo = getInputTensorInfo()
            val outputInfo = getOutputTensorInfo()
            
            Log.i(TAG, "Input tensor: $inputInfo")
            Log.i(TAG, "Output tensor: $outputInfo")
            
            // CRITICAL: Log exact shapes for debugging
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            Log.i(TAG, "Input shape array: ${inputTensor.shape().contentToString()}")
            Log.i(TAG, "Output shape array: ${outputTensor.shape().contentToString()}")
            Log.i(TAG, "Output num elements: ${outputTensor.shape().reduce { a, b -> a * b }}")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log tensor info", e)
        }
    }
    
    /**
     * Check if model is loaded
     */
    private fun checkLoaded() {
        if (!isLoaded || interpreter == null) {
            throw IllegalStateException("Model not loaded. Call load() first.")
        }
    }
    
    /**
     * Release model resources
     * 
     * Should be called when model is no longer needed.
     * After calling close(), load() must be called again to use model.
     */
    fun close() {
        interpreterLock.withLock {
            cleanup()
            Log.i(TAG, "Model closed")
        }
    }
    
    /**
     * Internal cleanup
     */
    private fun cleanup() {
        interpreter?.close()
        interpreter = null
        
        isLoaded = false
        isGpuEnabled = false
    }
}
