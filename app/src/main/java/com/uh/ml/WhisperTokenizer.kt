package com.uh.ml

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Decodes Whisper token IDs to text.
 * 
 * Whisper uses a GPT-2 style BPE (Byte Pair Encoding) tokenizer with:
 * - 51865 total tokens (multilingual vocabulary)
 * - Special tokens for transcription control
 * - Language-specific tokens (99 languages)
 * - Timestamp tokens for alignment
 * 
 * Vocabulary File Format:
 * - JSON object mapping token IDs (as strings) to text tokens
 * - Example: {"0": "!", "1": "\"", "2": "#", ...}
 * - Special tokens at end: 50257-51864
 * 
 * Note: Whisper vocabulary file must be downloaded separately
 * from the Whisper repository or extracted from model metadata.
 * This is NOT the same as the SBERT embedding tokenizer.
 * 
 * Thread-Safety: Vocabulary loaded eagerly during construction (not lazy).
 */
class WhisperTokenizer(private val vocabFile: File) {
    
    companion object {
        private const val TAG = "WhisperTokenizer"
        
        // Whisper special tokens (multilingual model)
        const val TOKEN_START_OF_TRANSCRIPT = 50258
        const val TOKEN_END_OF_TEXT = 50257
        const val TOKEN_LANGUAGE_EN = 50259  // English
        const val TOKEN_LANGUAGE_RU = 50263  // Russian (FIXED: was 50304 which is Azerbaijani)
        const val TOKEN_NO_TIMESTAMPS = 50363
        const val TOKEN_TRANSLATE = 50358
        const val TOKEN_TRANSCRIBE = 50359
        
        // Special token ranges
        private const val SPECIAL_TOKEN_START = 50257
        private const val SPECIAL_TOKEN_END = 51864
        
        // Language token range (99 languages)
        private const val LANGUAGE_TOKEN_START = 50259
        private const val LANGUAGE_TOKEN_END = 50357
    }
    
    // Token ID → Text mapping (loaded eagerly in init{})
    private var tokenToText: Map<Int, String> = emptyMap()
    
    // Language code mapping (subset of most common)
    // Full list: https://github.com/openai/whisper/blob/main/whisper/tokenizer.py
    private val languageTokens = mapOf(
        TOKEN_LANGUAGE_EN to "en",  // 50259
        TOKEN_LANGUAGE_RU to "ru",  // 50263 (FIXED)
        50260 to "zh",  // Chinese
        50261 to "de",  // German
        50262 to "es",  // Spanish
        50263 to "ru",  // Russian (duplicate for safety)
        50264 to "ko",  // Korean
        50265 to "fr",  // French
        50266 to "ja",  // Japanese
        50267 to "pt",  // Portuguese
        50268 to "tr",  // Turkish
        50269 to "pl",  // Polish
        50270 to "it",  // Italian
        50271 to "nl",  // Dutch
        50272 to "ar",  // Arabic
    )
    
    @Volatile
    var isLoaded = false
        private set
    
    init {
        // Load vocabulary eagerly during construction
        tokenToText = loadVocabulary()
    }
    
    /**
     * Load vocabulary from JSON file
     * 
     * Expected format:
     * {
     *   "0": "!",
     *   "1": "\"",
     *   "2": "#",
     *   ...
     *   "50257": "<|endoftext|>",
     *   "50258": "<|startoftranscript|>",
     *   ...
     * }
     */
    private fun loadVocabulary(): Map<Int, String> {
        if (!vocabFile.exists()) {
            throw IllegalStateException("Vocabulary file not found: ${vocabFile.absolutePath}")
        }
        
        Log.i(TAG, "Loading Whisper vocabulary from: ${vocabFile.absolutePath}")
        
        try {
            val vocabJson = vocabFile.readText()
            val vocabObject = JSONObject(vocabJson)
            
            val vocab = mutableMapOf<Int, String>()
            
            // Parse JSON: {"0": "!", "1": "\"", ...}
            // CRITICAL: Must iterate properly over ALL keys
            val keys = vocabObject.keys()
            var keyCount = 0
            
            while (keys.hasNext()) {
                val key = keys.next()
                keyCount++
                
                val tokenId = key.toIntOrNull()
                if (tokenId != null) {
                    val tokenText = vocabObject.getString(key)
                    vocab[tokenId] = tokenText
                } else {
                    Log.w(TAG, "Skipping non-integer key: $key")
                }
            }
            
            Log.i(TAG, "Processed $keyCount keys from JSON")
            Log.i(TAG, "Loaded ${vocab.size} tokens from vocabulary")
            
            if (vocab.size < 50000) {
                Log.e(TAG, "WARNING: Only loaded ${vocab.size} tokens, expected ~51865!")
                Log.e(TAG, "Vocabulary may be incomplete or corrupted!")
            }
            
            isLoaded = true
            
            return vocab.toMap()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocabulary", e)
            e.printStackTrace()
            throw IllegalArgumentException("Vocabulary loading failed: ${e.message}", e)
        }
    }
    
    /**
     * Decode token IDs to text
     * 
     * @param tokenIds Sequence of token IDs from Whisper inference
     * @param skipSpecialTokens If true, skip control tokens (default: true)
     * @return Decoded text string
     */
    fun decode(tokenIds: IntArray, skipSpecialTokens: Boolean = true): String {
        if (!isLoaded || tokenToText.isEmpty()) {
            val error = "Vocabulary not loaded. Tokenizer not initialized."
            Log.e(TAG, error)
            throw IllegalStateException(error)
        }
        
        Log.d(TAG, "Decoding ${tokenIds.size} tokens (skip_special=$skipSpecialTokens)")
        Log.d(TAG, "Vocabulary size: ${tokenToText.size}")
        Log.d(TAG, "First 10 input tokens: ${tokenIds.take(10).joinToString()}")
        
        val textParts = mutableListOf<String>()
        var skippedTokens = 0
        var unknownTokens = 0
        
        for ((index, tokenId) in tokenIds.withIndex()) {
            // Skip special tokens if requested
            if (skipSpecialTokens && isSpecialToken(tokenId)) {
                skippedTokens++
                // Stop at end-of-text token
                if (tokenId == TOKEN_END_OF_TEXT) {
                    Log.d(TAG, "Found end-of-text at position $index")
                    break
                }
                continue
            }
            
            // Get text for token
            val tokenText = tokenToText[tokenId]
            if (tokenText != null) {
                textParts.add(tokenText)
                Log.v(TAG, "Token $tokenId → \"$tokenText\"")
            } else {
                unknownTokens++
                Log.w(TAG, "Unknown token ID: $tokenId at position $index")
            }
        }
        
        Log.d(TAG, "Decode stats: ${textParts.size} text parts, $skippedTokens skipped, $unknownTokens unknown")
        
        // Join tokens and clean up BPE artifacts
        // Whisper BPE uses Ġ (U+0120) to indicate space before token
        val rawText = textParts.joinToString("")
        Log.d(TAG, "Raw joined text (${rawText.length} chars): \"$rawText\"")
        
        val cleanedText = cleanBpeArtifacts(rawText)
        Log.d(TAG, "Cleaned text: \"$cleanedText\"")
        
        return cleanedText.trim()
    }
    
    /**
     * Detect language from token sequence
     * 
     * Language token typically appears at position 1 or 2 in sequence.
     * Returns language code (e.g., "en", "ru") or null if not detected.
     */
    fun detectLanguage(tokenIds: IntArray): String? {
        // Check first 5 tokens for language token
        for (i in 0 until minOf(5, tokenIds.size)) {
            val tokenId = tokenIds[i]
            if (tokenId in LANGUAGE_TOKEN_START..LANGUAGE_TOKEN_END) {
                val langCode = languageTokens[tokenId]
                if (langCode != null) {
                    Log.d(TAG, "Detected language: $langCode (token $tokenId)")
                    return langCode
                }
            }
        }
        return null
    }
    
    /**
     * Extract timestamp tokens from sequence
     * 
     * Whisper can output timestamp tokens for alignment.
     * Timestamp tokens are in range [50364, 51864] and represent
     * time offsets in 20ms increments.
     * 
     * @return List of (token_position, time_offset_seconds) pairs
     */
    fun extractTimestamps(tokenIds: IntArray): List<Pair<Int, Float>> {
        val timestamps = mutableListOf<Pair<Int, Float>>()
        
        for (i in tokenIds.indices) {
            val tokenId = tokenIds[i]
            if (tokenId >= 50364 && tokenId <= 51864) {
                // Timestamp tokens: 50364 = 0.0s, 50365 = 0.02s, etc.
                val timeSeconds = (tokenId - 50364) * 0.02f
                timestamps.add(Pair(i, timeSeconds))
            }
        }
        
        return timestamps
    }
    
    /**
     * Check if token is a special token (control, not content)
     */
    private fun isSpecialToken(tokenId: Int): Boolean {
        return tokenId in SPECIAL_TOKEN_START..SPECIAL_TOKEN_END
    }
    
    /**
     * Clean up BPE artifacts from decoded text
     * 
     * Whisper BPE uses:
     * - Ġ (U+0120) to indicate space before token → convert to regular space
     * - Byte fallback tokens for non-UTF8 → handle gracefully
     */
    private fun cleanBpeArtifacts(text: String): String {
        return text
            .replace('Ġ', ' ')  // BPE space marker
            .replace("  ", " ")  // Collapse multiple spaces
            .replace(" ,", ",")  // Fix punctuation spacing
            .replace(" .", ".")
            .replace(" ?", "?")
            .replace(" !", "!")
            .replace(" :", ":")
            .replace(" ;", ";")
    }
    
    /**
     * Get statistics about decoded sequence
     * 
     * Useful for debugging and quality assessment.
     */
    data class DecodeStats(
        val totalTokens: Int,
        val contentTokens: Int,
        val specialTokens: Int,
        val language: String?,
        val hasTimestamps: Boolean
    )
    
    /**
     * Decode with detailed statistics
     */
    fun decodeWithStats(tokenIds: IntArray, skipSpecialTokens: Boolean = true): Pair<String, DecodeStats> {
        val text = decode(tokenIds, skipSpecialTokens)
        
        val contentTokens = tokenIds.count { !isSpecialToken(it) }
        val specialTokens = tokenIds.count { isSpecialToken(it) }
        val language = detectLanguage(tokenIds)
        val timestamps = extractTimestamps(tokenIds)
        
        val stats = DecodeStats(
            totalTokens = tokenIds.size,
            contentTokens = contentTokens,
            specialTokens = specialTokens,
            language = language,
            hasTimestamps = timestamps.isNotEmpty()
        )
        
        return Pair(text, stats)
    }
}
