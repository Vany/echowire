package com.uh.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages ML model loading from bundled assets.
 * Thread-safe singleton for app-wide model access.
 * 
 * Models bundled in APK:
 * - Whisper tiny TFLite model (~66MB) for speech recognition (multilingual)
 * - all-MiniLM-L6-v2 ONNX model (~86MB) for text embeddings
 * - Tokenizer vocabulary (~455KB) for embeddings
 * 
 * Models are extracted from assets to internal storage on first run.
 */
class ModelManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        
        // Asset paths (bundled in APK)
        private const val WHISPER_ASSET_PATH = "models/whisper_tiny.tflite"
        private const val WHISPER_VOCAB_ASSET_PATH = "models/whisper_vocab.json"
        private const val EMBEDDING_ASSET_PATH = "models/embedding.onnx"
        private const val TOKENIZER_ASSET_PATH = "models/tokenizer.json"
        
        @Volatile
        private var instance: ModelManager? = null
        
        fun getInstance(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Model file references in internal storage
    private val modelsDir: File = File(context.filesDir, MODELS_DIR)
    val whisperModelFile: File = File(modelsDir, "whisper_tiny.tflite")
    val whisperVocabFile: File = File(modelsDir, "whisper_vocab.json")
    val embeddingModelFile: File = File(modelsDir, "embedding.onnx")
    val tokenizerFile: File = File(modelsDir, "tokenizer.json")
    
    // Loading state
    @Volatile
    var isWhisperLoaded = false
        private set
    
    @Volatile
    var isEmbeddingLoaded = false
        private set
    
    /**
     * Progress listener for model extraction
     */
    interface ExtractionListener {
        fun onProgress(modelName: String, progress: Float, extracted: Long, total: Long)
        fun onComplete(modelName: String, file: File)
        fun onError(modelName: String, error: Exception)
    }
    
    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
            Log.d(TAG, "Created models directory: ${modelsDir.absolutePath}")
        }
    }
    
    /**
     * Check if all required models are extracted to internal storage
     */
    fun areModelsExtracted(): Boolean {
        return whisperModelFile.exists() && 
               whisperVocabFile.exists() &&
               embeddingModelFile.exists() && 
               tokenizerFile.exists()
    }
    
    /**
     * Get total size of extracted models in bytes
     */
    fun getTotalModelSize(): Long {
        var size = 0L
        if (whisperModelFile.exists()) size += whisperModelFile.length()
        if (whisperVocabFile.exists()) size += whisperVocabFile.length()
        if (embeddingModelFile.exists()) size += embeddingModelFile.length()
        if (tokenizerFile.exists()) size += tokenizerFile.length()
        return size
    }
    
    /**
     * Extract all bundled models from assets to internal storage.
     * Suspending function for coroutine usage.
     * Skips files that already exist.
     */
    suspend fun extractAllModels(listener: ExtractionListener?) = withContext(Dispatchers.IO) {
        try {
            // Extract Whisper model if not exists
            if (!whisperModelFile.exists()) {
                Log.i(TAG, "Extracting Whisper tiny model from assets...")
                extractModelFromAssets(
                    assetPath = WHISPER_ASSET_PATH,
                    destination = whisperModelFile,
                    modelName = "Whisper Tiny",
                    listener = listener
                )
            } else {
                Log.d(TAG, "Whisper model already exists: ${whisperModelFile.length()} bytes")
            }
            
            // Extract Whisper vocabulary if not exists or wrong size
            // Expected size: ~1.1MB for correct format, ~800KB for old inverted format
            val expectedVocabMinSize = 1000000L  // 1MB minimum
            val needsVocabExtraction = !whisperVocabFile.exists() || 
                                       whisperVocabFile.length() < expectedVocabMinSize
            
            if (needsVocabExtraction) {
                if (whisperVocabFile.exists()) {
                    Log.w(TAG, "Whisper vocabulary wrong size (${whisperVocabFile.length()} bytes), re-extracting...")
                    whisperVocabFile.delete()
                } else {
                    Log.i(TAG, "Extracting Whisper vocabulary from assets...")
                }
                extractModelFromAssets(
                    assetPath = WHISPER_VOCAB_ASSET_PATH,
                    destination = whisperVocabFile,
                    modelName = "Whisper Vocabulary",
                    listener = listener
                )
            } else {
                Log.d(TAG, "Whisper vocabulary already exists: ${whisperVocabFile.length()} bytes")
            }
            
            // Extract embedding model if not exists
            if (!embeddingModelFile.exists()) {
                Log.i(TAG, "Extracting embedding model from assets...")
                extractModelFromAssets(
                    assetPath = EMBEDDING_ASSET_PATH,
                    destination = embeddingModelFile,
                    modelName = "Embedding Model",
                    listener = listener
                )
            } else {
                Log.d(TAG, "Embedding model already exists: ${embeddingModelFile.length()} bytes")
            }
            
            // Extract tokenizer if not exists
            if (!tokenizerFile.exists()) {
                Log.i(TAG, "Extracting tokenizer from assets...")
                extractModelFromAssets(
                    assetPath = TOKENIZER_ASSET_PATH,
                    destination = tokenizerFile,
                    modelName = "Tokenizer",
                    listener = listener
                )
            } else {
                Log.d(TAG, "Tokenizer already exists: ${tokenizerFile.length()} bytes")
            }
            
            Log.i(TAG, "All models ready - total size: ${getTotalModelSize() / (1024 * 1024)} MB")
            
        } catch (e: Exception) {
            Log.e(TAG, "Model extraction failed", e)
            throw e
        }
    }
    
    /**
     * Extract single model from assets with progress tracking
     */
    private suspend fun extractModelFromAssets(
        assetPath: String,
        destination: File,
        modelName: String,
        listener: ExtractionListener?
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Extracting $modelName from $assetPath")
        
        try {
            val assetManager = context.assets
            val input = assetManager.open(assetPath)
            
            // Try to get asset file size for progress reporting
            // This may fail if the file is compressed in the APK
            val totalBytes = try {
                val assetFileDescriptor = assetManager.openFd(assetPath)
                val size = assetFileDescriptor.length
                assetFileDescriptor.close()
                size
            } catch (e: Exception) {
                // File is compressed, cannot get size
                Log.d(TAG, "$modelName is compressed, extracting without size reporting")
                -1L
            }
            
            val output = FileOutputStream(destination)
            
            val buffer = ByteArray(8192)
            var extracted = 0L
            var read: Int
            var lastProgressPercent = -1  // Track last reported percentage milestone
            
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                extracted += read
                
                // Report progress at 25%, 50%, 75%, 100% milestones
                if (totalBytes > 0) {
                    // Known file size - report percentage progress
                    val progress = extracted.toFloat() / totalBytes
                    val currentPercent = (progress * 100).toInt()
                    val milestone = (currentPercent / 25) * 25  // Round down to nearest 25%
                    
                    if (milestone > lastProgressPercent || extracted >= totalBytes) {
                        withContext(Dispatchers.Main) {
                            listener?.onProgress(modelName, progress, extracted, totalBytes)
                        }
                        lastProgressPercent = milestone
                    }
                } else {
                    // Compressed file - report every 5MB
                    val megabytes = extracted / (1024 * 1024)
                    val milestone = (megabytes / 5) * 5
                    
                    if (milestone > lastProgressPercent) {
                        withContext(Dispatchers.Main) {
                            listener?.onProgress(modelName, 0.0f, extracted, -1L)
                        }
                        lastProgressPercent = milestone.toInt()
                    }
                }
            }
            
            output.flush()
            output.close()
            input.close()
            
            Log.i(TAG, "Extracted $modelName: ${destination.length()} bytes")
            
            withContext(Dispatchers.Main) {
                listener?.onComplete(modelName, destination)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $modelName", e)
            // Clean up partial extraction
            if (destination.exists()) {
                destination.delete()
            }
            withContext(Dispatchers.Main) {
                listener?.onError(modelName, e)
            }
            throw e
        }
    }
    
    /**
     * Delete all extracted models (for cleanup or reset)
     * This does NOT delete bundled assets, only extracted files
     */
    fun deleteAllModels() {
        whisperModelFile.delete()
        whisperVocabFile.delete()
        embeddingModelFile.delete()
        tokenizerFile.delete()
        isWhisperLoaded = false
        isEmbeddingLoaded = false
        Log.i(TAG, "All extracted models deleted")
    }
    
    /**
     * Mark Whisper model as loaded
     */
    fun setWhisperLoaded(loaded: Boolean) {
        isWhisperLoaded = loaded
        Log.d(TAG, "Whisper model loaded: $loaded")
    }
    
    /**
     * Mark embedding model as loaded
     */
    fun setEmbeddingLoaded(loaded: Boolean) {
        isEmbeddingLoaded = loaded
        Log.d(TAG, "Embedding model loaded: $loaded")
    }
}
