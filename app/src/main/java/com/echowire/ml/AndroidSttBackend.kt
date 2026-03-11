package com.echowire.ml

import android.content.Context
import android.util.Log

/**
 * Wraps EnhancedAndroidSpeechRecognizer into the SttBackend interface.
 * Translates RecognitionEventListener → SttListener.
 */
class AndroidSttBackend(
    private val context: Context,
    private var language: String = "en-US",
) : SttBackend, EnhancedAndroidSpeechRecognizer.RecognitionEventListener {

    companion object {
        private const val TAG = "AndroidSttBackend"
    }

    override val displayName = "Android"

    private var recognizer: EnhancedAndroidSpeechRecognizer? = null
    private var listener: SttListener? = null

    override fun start() {
        val rec = EnhancedAndroidSpeechRecognizer(context, language)
        rec.setListener(this)
        if (!rec.initialize()) {
            listener?.onError(-1, "Speech recognition not available", System.currentTimeMillis())
            return
        }
        rec.setAutoRestart(true)
        rec.startListening()
        recognizer = rec
        Log.i(TAG, "Started (language=$language)")
    }

    override fun stop() {
        recognizer?.release()
        recognizer = null
        Log.i(TAG, "Stopped")
    }

    override fun release() = stop()

    override fun isActive() = recognizer?.isListening() ?: false

    override fun setListener(listener: SttListener?) { this.listener = listener }

    fun setLanguage(lang: String) {
        language = lang
        if (recognizer != null) { stop(); start() }
    }

    fun getLanguage(): String = language

    // RecognitionEventListener → SttListener

    override fun onPartialResult(partialText: String, timestamp: Long) {
        listener?.onPartialResult(partialText, language, timestamp)
    }

    override fun onFinalResult(
        results: List<String>,
        confidenceScores: FloatArray,
        timestamp: Long,
        sessionDurationMs: Long,
        speechDurationMs: Long,
    ) {
        listener?.onFinalResult(
            text = results.firstOrNull() ?: "",
            alternatives = results,
            confidences = confidenceScores,
            language = language,
            sentenceType = null,
            timestampMs = timestamp,
            sessionDurationMs = sessionDurationMs,
            speechDurationMs = speechDurationMs,
        )
    }

    override fun onRmsChanged(rmsdB: Float, timestamp: Long) {
        listener?.onAudioLevel(rmsdB, timestamp)
    }

    override fun onReadyForSpeech(timestamp: Long) {}
    override fun onBeginningOfSpeech(timestamp: Long) {}
    override fun onEndOfSpeech(timestamp: Long) {}

    override fun onError(errorCode: Int, errorMessage: String, timestamp: Long) {
        listener?.onError(errorCode, errorMessage, timestamp)
    }

    override fun onStateChanged(isListening: Boolean) {
        listener?.onStateChanged(isListening)
    }
}
