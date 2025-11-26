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
 * Hardware Acceleration (in priority order):
 * 1. GPU Delegate (Mali-G77 MP11) - Best performance
 * 2. XNNPack (ARM NEON CPU) - Fallback if GPU unavailable
 * 3. Default CPU - Last resort
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
    private var gpuDelegate: GpuDelegate? = null
    private val interpreterLock = ReentrantLock()
    
    @Volatile
    var isLoaded = false
        private set
    
    @Volatile
    var isGpuEnabled = false
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
     * Create interpreter options with GPU/CPU acceleration
     */
    private fun createInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        
        // Set thread count for CPU operations
        options.setNumThreads(NUM_THREADS)
        
        // Try GPU delegate first (Mali-G77)
        if (tryEnableGpu(options)) {
            Log.i(TAG, "Hardware acceleration: GPU (Mali-G77) with FP16")
            isGpuEnabled = true
        } else {
            Log.i(TAG, "Hardware acceleration: CPU with XNNPack")
            isGpuEnabled = false
        }
        
        // Enable XNNPack for ARM CPU optimization (always beneficial)
        options.setUseXNNPACK(true)
        
        return options
    }
    
    /**
     * Try to enable GPU delegate with device-specific optimization
     * 
     * Uses CompatibilityList to check device support and get optimized settings.
     * Mali-G77 MP11 (Exynos 990) supports GPU acceleration with FP16 precision.
     * 
     * @return true if GPU delegate successfully added
     */
    private fun tryEnableGpu(options: Interpreter.Options): Boolean {
        return try {
            val compatibilityList = CompatibilityList()
            
            if (compatibilityList.isDelegateSupportedOnThisDevice) {
                // Get best GPU delegate options for this device (Mali-G77)
                val delegateOptions = compatibilityList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
                
                Log.i(TAG, "GPU delegate enabled (Mali-G77 with FP16 precision)")
                true
            } else {
                Log.w(TAG, "GPU delegate not supported on this device")
                gpuDelegate = null
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable GPU delegate: ${e.message}", e)
            gpuDelegate = null
            false
        }
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
            
            // Allocate output tensor [1, 448]
            val outputTensor = Array(1) { IntArray(MAX_TOKEN_SEQUENCE) }
            
            // Run inference
            interpreter!!.run(inputTensor, outputTensor)
            
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inference completed in ${inferenceTime}ms")
            
            // Extract token IDs (remove batch dimension)
            outputTensor[0]
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
        
        gpuDelegate?.close()
        gpuDelegate = null
        
        isLoaded = false
        isGpuEnabled = false
    }
}
