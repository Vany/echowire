package com.uh.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Whisper inference using usefulsensors/openai-whisper monolithic TFLite model.
 * 
 * Model Characteristics:
 * - Provider: usefulsensors (https://github.com/usefulsensors/openai-whisper)
 * - Architecture: Monolithic (single TFLite file, not encoder-decoder split)
 * - Input: Mel spectrogram [1, 80, 3000]
 * - Output: Token IDs [1, 448] as INT32
 * - Size: ~66MB (tiny model)
 * 
 * Performance (Samsung Note20, Exynos 990):
 * - Inference: 743-776ms
 * - RTF: 0.09-0.96x (faster than real-time)
 * - Hardware: XNNPack ARM NEON CPU (4 threads)
 * 
 * Known Issues:
 * - Language detection outputs Russian token (50263) for English speech
 * - Text transcription works perfectly despite wrong language token
 * - Vocabulary mapping is correct, model inference issue
 * 
 * Thread Safety:
 * - Thread-safe inference via ReentrantLock
 * - One interpreter per instance
 * - Safe to share across threads
 */
class UsefulSensorsWhisper(
    private val context: Context,
    private val modelFile: File
) : WhisperInference {
    
    companion object {
        private const val TAG = "UsefulSensorsWhisper"
        
        // Model dimensions
        const val MAX_MEL_FRAMES = 3000    // 30 seconds at 10ms hop
        const val MEL_BINS = 80            // Whisper standard
        const val MAX_TOKEN_SEQUENCE = 448 // Max output sequence
        const val VOCAB_SIZE = 51865       // Whisper multilingual vocab
        
        private const val NUM_THREADS = 4
    }
    
    private var interpreter: Interpreter? = null
    private val interpreterLock = ReentrantLock()
    
    @Volatile
    private var loaded = false
    
    override fun load() {
        if (loaded) {
            Log.w(TAG, "Model already loaded")
            return
        }
        
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.absolutePath}")
        }
        
        Log.i(TAG, "Loading usefulsensors Whisper model: ${modelFile.name}")
        Log.i(TAG, "Model size: ${modelFile.length() / (1024 * 1024)} MB")
        
        val startTime = System.currentTimeMillis()
        
        try {
            val modelBuffer = loadModelFile(modelFile)
            val options = createInterpreterOptions()
            interpreter = Interpreter(modelBuffer, options)
            
            loaded = true
            
            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Model loaded successfully in ${loadTime}ms")
            logTensorInfo()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            cleanup()
            throw IllegalArgumentException("Model loading failed: ${e.message}", e)
        }
    }
    
    override fun runInference(
        melSpectrogram: Array<FloatArray>,
        audioLengthMs: Long
    ): WhisperInference.InferenceResult {
        checkLoaded()
        
        return interpreterLock.withLock {
            val startTime = System.currentTimeMillis()
            
            // Prepare input [1, 80, 3000]
            val inputTensor = prepareInputTensor(melSpectrogram)
            
            // Get output info
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val outputSize = outputShape.reduce { a, b -> a * b }
            
            Log.d(TAG, "Output shape: ${outputShape.contentToString()}")
            Log.d(TAG, "Output data type: ${outputTensor.dataType()}")
            
            // Allocate output buffer (INT32)
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // Run inference
            interpreter!!.run(inputTensor, outputBuffer)
            
            // Read token IDs
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
            
            val nonZeroTokens = tokenIds.count { it != 0 }
            val uniqueTokens = tokenIds.filter { it != 0 }.toSet().size
            Log.d(TAG, "Token stats: $nonZeroTokens non-zero, $uniqueTokens unique")
            
            WhisperInference.InferenceResult(
                tokenIds = tokenIds,
                inferenceTimeMs = inferenceTime,
                audioLengthMs = audioLengthMs
            )
        }
    }
    
    override fun getModelInfo(): WhisperInference.ModelInfo {
        return WhisperInference.ModelInfo(
            name = "whisper-tiny",
            provider = "usefulsensors",
            architecture = "monolithic",
            sizeBytes = if (modelFile.exists()) modelFile.length() else 0L,
            maxAudioSeconds = 30,
            vocabSize = VOCAB_SIZE
        )
    }
    
    override fun isLoaded(): Boolean = loaded
    
    override fun close() {
        interpreterLock.withLock {
            cleanup()
            Log.i(TAG, "Model closed")
        }
    }
    
    private fun createInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        options.setNumThreads(NUM_THREADS)
        options.setUseXNNPACK(true)
        
        Log.i(TAG, "Hardware acceleration: XNNPack (ARM NEON) with $NUM_THREADS threads")
        
        return options
    }
    
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
    
    private fun prepareInputTensor(melSpec: Array<FloatArray>): Array<Array<FloatArray>> {
        val numFrames = melSpec.size
        val numMels = melSpec.getOrNull(0)?.size ?: MEL_BINS
        
        if (numMels != MEL_BINS) {
            throw IllegalArgumentException("Expected $MEL_BINS mel bins, got $numMels")
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
        
        if (numFrames > MAX_MEL_FRAMES) {
            Log.w(TAG, "Audio truncated: $numFrames frames → $MAX_MEL_FRAMES frames")
        } else if (numFrames < MAX_MEL_FRAMES) {
            Log.d(TAG, "Audio padded: $numFrames frames → $MAX_MEL_FRAMES frames")
        }
        
        return inputTensor
    }
    
    private fun logTensorInfo() {
        try {
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            
            Log.i(TAG, "Input: ${inputTensor.shape().contentToString()} ${inputTensor.dataType()}")
            Log.i(TAG, "Output: ${outputTensor.shape().contentToString()} ${outputTensor.dataType()}")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log tensor info", e)
        }
    }
    
    private fun checkLoaded() {
        if (!loaded || interpreter == null) {
            throw IllegalStateException("Model not loaded. Call load() first.")
        }
    }
    
    private fun cleanup() {
        interpreter?.close()
        interpreter = null
        loaded = false
    }
}
