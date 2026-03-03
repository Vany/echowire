package com.echowire.ml

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enhanced wrapper for Android's built-in SpeechRecognizer API
 * Extracts MAXIMUM information and streams updates as frequently as possible
 *
 * Data extracted:
 * - Partial results (real-time updates while speaking)
 * - Final results with multiple alternatives
 * - Confidence scores for each alternative
 * - RMS audio level changes (10+ times per second)
 * - Speech timing events (start, end, ready)
 * - Network info (for debugging latency)
 *
 * Design: Continuous recognition with auto-restart for 24/7 operation
 */
class EnhancedAndroidSpeechRecognizer(
    private val context: Context,
    private val language: String = "en-US"  // "ru-RU", "en-US", etc.
) {

    companion object {
        private const val TAG = "EnhancedAndroidSTT"
        private const val MAX_RESULTS = 5  // Get top 5 alternatives
        private const val AUTO_RESTART_DELAY_MS = 100L  // Quick restart for continuous recognition
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: RecognitionEventListener? = null
    private var isListening = false
    private var autoRestart = true  // Automatically restart after each result

    // Timing
    private var sessionStartTime = 0L
    private var speechStartTime = 0L

    /**
     * Comprehensive listener for all recognition events
     * All callbacks include timestamp for client-side timing analysis
     */
    interface RecognitionEventListener {
        /**
         * Partial result while user is speaking (real-time)
         * Called multiple times per second during speech
         *
         * @param partialText Best guess so far
         * @param timestamp System time when partial was generated
         */
        fun onPartialResult(partialText: String, timestamp: Long)

        /**
         * Final result with all available metadata
         *
         * @param results Array of alternatives, sorted by confidence
         * @param confidenceScores Confidence scores (0.0-1.0) for each alternative
         * @param timestamp System time when result was finalized
         * @param sessionDurationMs Time from ready to result
         * @param speechDurationMs Time from speech start to end
         */
        fun onFinalResult(
            results: List<String>,
            confidenceScores: FloatArray,
            timestamp: Long,
            sessionDurationMs: Long,
            speechDurationMs: Long
        )

        /**
         * Audio level changed (RMS dB value)
         * Called ~10-30 times per second
         *
         * @param rmsdB RMS audio level in decibels
         * @param timestamp System time
         */
        fun onRmsChanged(rmsdB: Float, timestamp: Long)

        /**
         * Recognition session ready to receive speech
         */
        fun onReadyForSpeech(timestamp: Long)

        /**
         * User started speaking
         */
        fun onBeginningOfSpeech(timestamp: Long)

        /**
         * User stopped speaking (silence detected)
         */
        fun onEndOfSpeech(timestamp: Long)

        /**
         * Error occurred
         *
         * @param errorCode Error code from SpeechRecognizer
         * @param errorMessage Human-readable error message
         * @param timestamp System time
         */
        fun onError(errorCode: Int, errorMessage: String, timestamp: Long)

        /**
         * Recognition state changed
         *
         * @param isListening True if currently listening for speech
         */
        fun onStateChanged(isListening: Boolean)
    }

    /**
     * Initialize the speech recognizer
     */
    fun initialize(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return false
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    sessionStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Ready for speech (language: $language)")
                    listener?.onReadyForSpeech(sessionStartTime)
                }

                override fun onBeginningOfSpeech() {
                    speechStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Beginning of speech")
                    listener?.onBeginningOfSpeech(speechStartTime)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Called frequently (~10-30 Hz) - perfect for real-time audio visualization
                    listener?.onRmsChanged(rmsdB, System.currentTimeMillis())
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Raw audio data - not commonly provided by all implementations
                    buffer?.let {
                        Log.v(TAG, "Audio buffer received: ${it.size} bytes")
                    }
                }

                override fun onEndOfSpeech() {
                    val now = System.currentTimeMillis()
                    Log.d(TAG, "End of speech")
                    listener?.onEndOfSpeech(now)
                }

                override fun onError(error: Int) {
                    val now = System.currentTimeMillis()
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error: $error"
                    }

                    Log.w(TAG, "Recognition error: $errorMessage (code: $error)")
                    listener?.onError(error, errorMessage, now)

                    isListening = false
                    listener?.onStateChanged(false)

                    // Auto-restart on certain errors
                    if (autoRestart && shouldAutoRestart(error)) {
                        Log.i(TAG, "Auto-restarting recognition after error")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startListening()
                        }, AUTO_RESTART_DELAY_MS)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val now = System.currentTimeMillis()

                    results?.let { bundle ->
                        // Extract all available alternatives
                        val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val scores = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                        if (!matches.isNullOrEmpty()) {
                            val sessionDuration = now - sessionStartTime
                            val speechDuration = if (speechStartTime > 0) now - speechStartTime else 0L

                            // Log all alternatives with confidence scores
                            matches.forEachIndexed { index, text ->
                                val confidence = scores?.getOrNull(index) ?: 0f
                                Log.d(TAG, "Result #$index: \"$text\" (confidence: ${"%.3f".format(confidence)})")
                            }

                            Log.i(
                                TAG, "Final result: \"${matches[0]}\" " +
                                        "(session: ${sessionDuration}ms, speech: ${speechDuration}ms)"
                            )

                            listener?.onFinalResult(
                                results = matches,
                                confidenceScores = scores ?: FloatArray(matches.size) { 0f },
                                timestamp = now,
                                sessionDurationMs = sessionDuration,
                                speechDurationMs = speechDuration
                            )
                        } else {
                            Log.w(TAG, "Empty results received")
                        }
                    }

                    isListening = false
                    listener?.onStateChanged(false)

                    // Auto-restart for continuous recognition
                    if (autoRestart) {
                        Log.d(TAG, "Auto-restarting recognition for continuous mode")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startListening()
                        }, AUTO_RESTART_DELAY_MS)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val now = System.currentTimeMillis()

                    partialResults?.let { bundle ->
                        val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0]
                            Log.v(TAG, "Partial: \"$partial\"")
                            listener?.onPartialResult(partial, now)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Custom events - vendor-specific
                    Log.v(TAG, "Event: type=$eventType, params=$params")
                }
            })
        }

        Log.i(TAG, "EnhancedAndroidSTT initialized for language: $language")
        return true
    }

    /**
     * Start listening for speech
     * Designed for continuous recognition with auto-restart
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Language settings
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)

            // Request maximum data
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)  // Enable partial results
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)  // Get top 5 alternatives
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)  // Request confidence scores

            // Audio settings
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)  // Use cloud for better accuracy

            // Try to enable on-device if available (device-dependent)
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            listener?.onStateChanged(true)
            Log.d(TAG, "Started listening (language: $language)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            listener?.onError(-1, "Failed to start: ${e.message}", System.currentTimeMillis())
        }
    }

    /**
     * Stop listening (disable auto-restart temporarily)
     */
    fun stopListening() {
        if (!isListening) return

        autoRestart = false  // Disable auto-restart
        speechRecognizer?.stopListening()
        isListening = false
        listener?.onStateChanged(false)
        Log.d(TAG, "Stopped listening")
    }

    /**
     * Enable/disable auto-restart for continuous recognition
     */
    fun setAutoRestart(enabled: Boolean) {
        autoRestart = enabled
        Log.d(TAG, "Auto-restart: $enabled")
    }

    /**
     * Set listener for recognition events
     */
    fun setListener(listener: RecognitionEventListener?) {
        this.listener = listener
    }

    /**
     * Check if currently listening
     */
    fun isListening(): Boolean = isListening

    /**
     * Release resources
     */
    fun release() {
        autoRestart = false
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        listener = null
        Log.i(TAG, "Released")
    }

    /**
     * Determine if should auto-restart after error
     */
    private fun shouldAutoRestart(errorCode: Int): Boolean {
        return when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> true  // Common, restart immediately

            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK -> true  // Network issues, retry

            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> false  // Fatal errors

            else -> false
        }
    }

    /**
     * Get current language
     */
    fun getLanguage(): String = language

    /**
     * Check if speech recognition is available on this device
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
}
