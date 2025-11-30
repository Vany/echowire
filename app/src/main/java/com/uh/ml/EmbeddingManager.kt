package com.uh.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages text embedding generation using ONNX Runtime with all-MiniLM-L6-v2 model.
 * Converts text to 384-dimensional semantic embeddings for similarity search.
 * 
 * Model: all-MiniLM-L6-v2 (sentence-transformers)
 * Output: 384-dimensional float vector
 * Normalization: L2 normalized (unit length) for cosine similarity
 * 
 * Thread-safe: Use separate instances per thread or lock for concurrent access.
 */
class EmbeddingManager(
    private val context: Context,
    private val modelFile: File,
    private val vocabFile: File
) {
    companion object {
        private const val TAG = "EmbeddingManager"
        private const val EMBEDDING_DIM = 384
        private const val MAX_SEQUENCE_LENGTH = 256  // all-MiniLM-L6-v2 max length
    }
    
    // ONNX Runtime environment and session
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    
    // Tokenizer vocabulary and special tokens
    private var tokenToId: Map<String, Long> = emptyMap()
    private var clsTokenId: Long = 101  // [CLS] token
    private var sepTokenId: Long = 102  // [SEP] token
    private var padTokenId: Long = 0    // [PAD] token
    private var unkTokenId: Long = 100  // [UNK] token
    
    // Thread safety for inference
    private val sessionLock = ReentrantLock()
    
    @Volatile
    var isLoaded = false
        private set
    
    /**
     * Load ONNX model and tokenizer vocabulary.
     * Call on background thread (takes 100-200ms).
     */
    fun load() {
        if (isLoaded) {
            Log.w(TAG, "EmbeddingManager already loaded")
            return
        }
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Initialize ONNX Runtime environment
            ortEnvironment = OrtEnvironment.getEnvironment()
            Log.d(TAG, "ONNX Runtime environment initialized")
            
            // Create session with CPU execution provider
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(2)  // 2 threads for embedding generation
            sessionOptions.setInterOpNumThreads(2)
            
            ortSession = ortEnvironment!!.createSession(
                modelFile.readBytes(),
                sessionOptions
            )
            Log.d(TAG, "ONNX session created from ${modelFile.name}")
            
            // Log input/output info
            ortSession?.let { session ->
                session.inputInfo.forEach { (name, info) ->
                    Log.d(TAG, "Input: $name -> ${info.info}")
                }
                session.outputInfo.forEach { (name, info) ->
                    Log.d(TAG, "Output: $name -> ${info.info}")
                }
            }
            
            // Load tokenizer vocabulary
            loadVocabulary()
            
            isLoaded = true
            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "EmbeddingManager loaded in ${loadTime}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load EmbeddingManager", e)
            close()
            throw IllegalStateException("Failed to load embedding model: ${e.message}", e)
        }
    }
    
    /**
     * Load vocabulary from tokenizer JSON file.
     * Format: {"vocab": {"[PAD]": 0, "token": id, ...}}
     */
    private fun loadVocabulary() {
        try {
            val vocabJson = JSONObject(vocabFile.readText())
            val vocab = vocabJson.getJSONObject("vocab")
            
            tokenToId = mutableMapOf<String, Long>().apply {
                vocab.keys().forEach { token ->
                    this[token] = vocab.getLong(token)
                }
            }
            
            // Extract special token IDs
            clsTokenId = tokenToId["[CLS]"] ?: 101L
            sepTokenId = tokenToId["[SEP]"] ?: 102L
            padTokenId = tokenToId["[PAD]"] ?: 0L
            unkTokenId = tokenToId["[UNK]"] ?: 100L
            
            Log.i(TAG, "Vocabulary loaded: ${tokenToId.size} tokens")
            Log.d(TAG, "Special tokens - CLS:$clsTokenId SEP:$sepTokenId PAD:$padTokenId UNK:$unkTokenId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocabulary", e)
            throw IllegalStateException("Failed to load tokenizer vocabulary: ${e.message}", e)
        }
    }
    
    /**
     * Generate embedding for input text.
     * 
     * @param text Input text to encode
     * @return 384-dimensional embedding (L2 normalized)
     */
    fun encode(text: String): FloatArray {
        if (!isLoaded) {
            throw IllegalStateException("EmbeddingManager not loaded. Call load() first.")
        }
        
        sessionLock.withLock {
            val startTime = System.currentTimeMillis()
            
            try {
                // 1. Tokenize text
                val tokens = tokenize(text)
                
                // 2. Create input tensors (input_ids, attention_mask)
                val inputIds = LongArray(MAX_SEQUENCE_LENGTH) { padTokenId }
                val attentionMask = LongArray(MAX_SEQUENCE_LENGTH) { 0L }
                
                // Add [CLS] token at start
                inputIds[0] = clsTokenId
                attentionMask[0] = 1L
                
                // Copy tokens (up to MAX_SEQUENCE_LENGTH - 2 for CLS/SEP)
                val tokenLimit = minOf(tokens.size, MAX_SEQUENCE_LENGTH - 2)
                for (i in 0 until tokenLimit) {
                    inputIds[i + 1] = tokens[i]
                    attentionMask[i + 1] = 1L
                }
                
                // Add [SEP] token at end
                val sepIndex = minOf(tokenLimit + 1, MAX_SEQUENCE_LENGTH - 1)
                inputIds[sepIndex] = sepTokenId
                attentionMask[sepIndex] = 1L
                
                // 3. Create ONNX tensors
                val env = ortEnvironment!!
                val inputShape = longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong())
                
                val inputIdsTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(inputIds),
                    inputShape
                )
                
                val attentionMaskTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(attentionMask),
                    inputShape
                )
                
                // 4. Run inference
                val inputs = mapOf(
                    "input_ids" to inputIdsTensor,
                    "attention_mask" to attentionMaskTensor
                )
                
                val outputs = ortSession!!.run(inputs)
                
                // 5. Extract sentence embedding (output name may be "last_hidden_state" or "sentence_embedding")
                // For all-MiniLM-L6-v2, we need to do mean pooling over token embeddings
                val outputTensor = outputs[0].value as Array<Array<FloatArray>>
                
                // Mean pooling: average all token embeddings (weighted by attention mask)
                val embedding = meanPooling(outputTensor[0], attentionMask)
                
                // 6. L2 normalize for cosine similarity
                val normalizedEmbedding = l2Normalize(embedding)
                
                // Cleanup
                inputIdsTensor.close()
                attentionMaskTensor.close()
                outputs.close()
                
                val encodeTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Encoding completed in ${encodeTime}ms (text length: ${text.length})")
                
                return normalizedEmbedding
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode text", e)
                throw RuntimeException("Failed to generate embedding: ${e.message}", e)
            }
        }
    }
    
    /**
     * Simple word tokenization with vocabulary lookup.
     * Splits on whitespace and punctuation, converts to lowercase.
     * 
     * TODO: Replace with proper WordPiece tokenization for better accuracy.
     */
    private fun tokenize(text: String): LongArray {
        val normalized = text.lowercase()
        val words = normalized.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.isNotEmpty() }
        
        return words.map { word ->
            tokenToId[word] ?: unkTokenId
        }.toLongArray()
    }
    
    /**
     * Mean pooling: average token embeddings weighted by attention mask.
     * 
     * @param tokenEmbeddings Token embeddings [seq_len, hidden_size]
     * @param attentionMask Attention mask [seq_len]
     * @return Mean pooled embedding [hidden_size]
     */
    private fun meanPooling(
        tokenEmbeddings: Array<FloatArray>,
        attentionMask: LongArray
    ): FloatArray {
        val hiddenSize = tokenEmbeddings[0].size
        val pooled = FloatArray(hiddenSize) { 0f }
        var maskSum = 0f
        
        for (i in tokenEmbeddings.indices) {
            val mask = attentionMask[i].toFloat()
            maskSum += mask
            for (j in 0 until hiddenSize) {
                pooled[j] += tokenEmbeddings[i][j] * mask
            }
        }
        
        // Avoid division by zero
        if (maskSum > 0f) {
            for (j in pooled.indices) {
                pooled[j] /= maskSum
            }
        }
        
        return pooled
    }
    
    /**
     * L2 normalization (unit length vector) for cosine similarity.
     * 
     * @param embedding Input embedding
     * @return Normalized embedding (length = 1.0)
     */
    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var norm = 0f
        for (value in embedding) {
            norm += value * value
        }
        norm = kotlin.math.sqrt(norm)
        
        // Avoid division by zero
        if (norm == 0f) {
            return embedding
        }
        
        return FloatArray(embedding.size) { i ->
            embedding[i] / norm
        }
    }
    
    /**
     * Compute cosine similarity between two embeddings.
     * Both embeddings must be L2 normalized (from encode()).
     * 
     * @return Similarity score in [-1, 1] where 1 = identical, -1 = opposite
     */
    fun cosineSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) {
            throw IllegalArgumentException("Embeddings must have same dimension")
        }
        
        var dotProduct = 0f
        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
        }
        
        return dotProduct  // Already normalized, so no need to divide by norms
    }
    
    /**
     * Release ONNX Runtime resources.
     * Call when done with EmbeddingManager.
     */
    fun close() {
        sessionLock.withLock {
            try {
                ortSession?.close()
                ortSession = null
                
                ortEnvironment = null  // Don't close environment (shared singleton)
                
                tokenToId = emptyMap()
                isLoaded = false
                
                Log.i(TAG, "EmbeddingManager closed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error closing EmbeddingManager", e)
            }
        }
    }
}
