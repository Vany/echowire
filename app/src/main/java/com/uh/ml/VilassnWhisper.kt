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
 * Whisper inference using vilassn/whisper_android encoder-decoder split model.
 * 
 * Model Characteristics:
 * - Provider: vilassn (https://github.com/vilassn/whisper_android)
 * - Architecture: Encoder-decoder split (two TFLite files)
 * - Encoder: Mel spectrogram → encoded features
 * - Decoder: Encoded features → token sequence
 * - Size: ~66MB total (encoder + decoder)
 * 
 * Advantages:
 * - Proven working model (language detection works correctly)
 * - More efficient for autoregressive decoding
 * - Better memory usage (encoder runs once, decoder iterates)
 * 
 * Inference Pipeline:
 * 1. Encoder: mel_spectrogram[1,80,3000] → features[1,1500,384]
 * 2. Decoder: features + previous_tokens → next_token
 * 3. Repeat step 2 autoregressively until EOS or max_length
 * 
 * Performance:
 * - Expected similar to monolithic model
 * - Encoder overhead: ~200ms (one-time)
 * - Decoder per-token: ~5-10ms
 * - Total: ~400-800ms for typical utterance
 * 
 * Thread Safety:
 * - Thread-safe inference via ReentrantLock
 * - Two interpreters (encoder + decoder)
 * - Safe to share across threads
 */
class VilassnWhisper(
    private val context: Context,
    private val encoderFile: File,
    private val decoderFile: File
) : WhisperInference {
    
    companion object {
        private const val TAG = "VilassnWhisper"
        
        // Model dimensions
        const val MAX_MEL_FRAMES = 3000    // 30 seconds at 10ms hop
        const val MEL_BINS = 80            // Whisper standard
        const val ENCODED_FRAMES = 1500    // Encoder output frames (3000 / 2)
        const val FEATURE_DIM = 384        // Encoder feature dimension
        const val MAX_TOKEN_SEQUENCE = 448 // Max decoder output
        const val VOCAB_SIZE = 51865       // Whisper multilingual vocab
        
        // Special tokens
        const val TOKEN_START_OF_TRANSCRIPT = 50258
        const val TOKEN_END_OF_TEXT = 50257
        const val TOKEN_NO_TIMESTAMPS = 50363
        
        private const val NUM_THREADS = 4
    }
    
    private var encoderInterpreter: Interpreter? = null
    private var decoderInterpreter: Interpreter? = null
    private val interpreterLock = ReentrantLock()
    
    @Volatile
    private var loaded = false
    
    override fun load() {
        if (loaded) {
            Log.w(TAG, "Model already loaded")
            return
        }
        
        if (!encoderFile.exists()) {
            throw IllegalStateException("Encoder file not found: ${encoderFile.absolutePath}")
        }
        if (!decoderFile.exists()) {
            throw IllegalStateException("Decoder file not found: ${decoderFile.absolutePath}")
        }
        
        Log.i(TAG, "Loading vilassn Whisper encoder-decoder model")
        Log.i(TAG, "Encoder: ${encoderFile.name} (${encoderFile.length() / (1024 * 1024)} MB)")
        Log.i(TAG, "Decoder: ${decoderFile.name} (${decoderFile.length() / (1024 * 1024)} MB)")
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Load encoder
            val encoderBuffer = loadModelFile(encoderFile)
            val encoderOptions = createInterpreterOptions()
            encoderInterpreter = Interpreter(encoderBuffer, encoderOptions)
            
            // Load decoder
            val decoderBuffer = loadModelFile(decoderFile)
            val decoderOptions = createInterpreterOptions()
            decoderInterpreter = Interpreter(decoderBuffer, decoderOptions)
            
            loaded = true
            
            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Models loaded successfully in ${loadTime}ms")
            logTensorInfo()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models", e)
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
            
            // Step 1: Encode mel spectrogram
            val encodedFeatures = runEncoder(melSpectrogram)
            val encodeTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Encoder completed in ${encodeTime}ms")
            
            // Step 2: Decode autoregressively
            val tokenIds = runDecoder(encodedFeatures)
            val decodeTime = System.currentTimeMillis() - startTime - encodeTime
            Log.d(TAG, "Decoder completed in ${decodeTime}ms (${tokenIds.size} tokens)")
            
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Total inference completed in ${inferenceTime}ms")
            Log.d(TAG, "First 20 tokens: ${tokenIds.take(20).joinToString()}")
            
            WhisperInference.InferenceResult(
                tokenIds = tokenIds,
                inferenceTimeMs = inferenceTime,
                audioLengthMs = audioLengthMs
            )
        }
    }
    
    override fun getModelInfo(): WhisperInference.ModelInfo {
        val totalSize = (if (encoderFile.exists()) encoderFile.length() else 0L) +
                       (if (decoderFile.exists()) decoderFile.length() else 0L)
        
        return WhisperInference.ModelInfo(
            name = "whisper-tiny-encoder-decoder",
            provider = "vilassn",
            architecture = "encoder-decoder",
            sizeBytes = totalSize,
            maxAudioSeconds = 30,
            vocabSize = VOCAB_SIZE
        )
    }
    
    override fun isLoaded(): Boolean = loaded
    
    override fun close() {
        interpreterLock.withLock {
            cleanup()
            Log.i(TAG, "Models closed")
        }
    }
    
    /**
     * Run encoder: mel_spectrogram → encoded_features
     * 
     * Input: [1, 80, 3000] mel spectrogram
     * Output: [1, 1500, 384] encoded features
     */
    private fun runEncoder(melSpec: Array<FloatArray>): Array<Array<FloatArray>> {
        val numFrames = melSpec.size
        val numMels = melSpec.getOrNull(0)?.size ?: MEL_BINS
        
        if (numMels != MEL_BINS) {
            throw IllegalArgumentException("Expected $MEL_BINS mel bins, got $numMels")
        }
        
        // Prepare encoder input [1, 80, 3000]
        val encoderInput = Array(1) {
            Array(MEL_BINS) {
                FloatArray(MAX_MEL_FRAMES)
            }
        }
        
        // Copy mel spectrogram (transpose: [frames × mels] → [mels × frames])
        val framesToCopy = minOf(numFrames, MAX_MEL_FRAMES)
        for (frameIdx in 0 until framesToCopy) {
            for (melIdx in 0 until MEL_BINS) {
                encoderInput[0][melIdx][frameIdx] = melSpec[frameIdx][melIdx]
            }
        }
        
        if (numFrames < MAX_MEL_FRAMES) {
            Log.d(TAG, "Audio padded: $numFrames frames → $MAX_MEL_FRAMES frames")
        }
        
        // Prepare encoder output [1, 1500, 384]
        val encoderOutput = Array(1) {
            Array(ENCODED_FRAMES) {
                FloatArray(FEATURE_DIM)
            }
        }
        
        // Run encoder
        encoderInterpreter!!.run(encoderInput, encoderOutput)
        
        return encoderOutput
    }
    
    /**
     * Run decoder autoregressively: features → token_sequence
     * 
     * Decoder runs in a loop:
     * 1. Start with [SOT, language, no_timestamps]
     * 2. Feed features + previous tokens → next token
     * 3. Append next token to sequence
     * 4. Repeat until EOS or max_length
     * 
     * Input: encoded_features[1, 1500, 384] + previous_tokens
     * Output: next_token_logits[1, 51865]
     */
    private fun runDecoder(encodedFeatures: Array<Array<FloatArray>>): IntArray {
        val tokens = mutableListOf<Int>()
        
        // Initialize with start tokens
        // TODO: Proper initialization depends on decoder input signature
        // This is a placeholder - actual implementation requires inspecting
        // vilassn model's decoder input/output tensors
        
        // For now, return placeholder showing structure
        Log.w(TAG, "Decoder autoregressive loop not yet implemented")
        Log.w(TAG, "Requires inspection of vilassn decoder model signature")
        
        // Placeholder: return start tokens only
        tokens.add(TOKEN_START_OF_TRANSCRIPT)
        tokens.add(TOKEN_NO_TIMESTAMPS)
        
        // TODO: Implement autoregressive decoding loop:
        // while (tokens.size < MAX_TOKEN_SEQUENCE) {
        //     val nextTokenLogits = decoderInterpreter.run(encodedFeatures, tokens)
        //     val nextToken = argmax(nextTokenLogits)
        //     tokens.add(nextToken)
        //     if (nextToken == TOKEN_END_OF_TEXT) break
        // }
        
        return tokens.toIntArray()
    }
    
    private fun createInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
        options.setNumThreads(NUM_THREADS)
        options.setUseXNNPACK(true)
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
    
    private fun logTensorInfo() {
        try {
            Log.i(TAG, "=== ENCODER ===")
            val encInput = encoderInterpreter!!.getInputTensor(0)
            val encOutput = encoderInterpreter!!.getOutputTensor(0)
            Log.i(TAG, "Input: ${encInput.shape().contentToString()} ${encInput.dataType()}")
            Log.i(TAG, "Output: ${encOutput.shape().contentToString()} ${encOutput.dataType()}")
            
            Log.i(TAG, "=== DECODER ===")
            val decInputCount = decoderInterpreter!!.inputTensorCount
            for (i in 0 until decInputCount) {
                val tensor = decoderInterpreter!!.getInputTensor(i)
                Log.i(TAG, "Input $i: ${tensor.shape().contentToString()} ${tensor.dataType()}")
            }
            
            val decOutputCount = decoderInterpreter!!.outputTensorCount
            for (i in 0 until decOutputCount) {
                val tensor = decoderInterpreter!!.getOutputTensor(i)
                Log.i(TAG, "Output $i: ${tensor.shape().contentToString()} ${tensor.dataType()}")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log tensor info", e)
        }
    }
    
    private fun checkLoaded() {
        if (!loaded || encoderInterpreter == null || decoderInterpreter == null) {
            throw IllegalStateException("Models not loaded. Call load() first.")
        }
    }
    
    private fun cleanup() {
        encoderInterpreter?.close()
        decoderInterpreter?.close()
        encoderInterpreter = null
        decoderInterpreter = null
        loaded = false
    }
}
