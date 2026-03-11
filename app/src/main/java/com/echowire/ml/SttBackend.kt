package com.echowire.ml

/**
 * Unified STT backend abstraction.
 * Both Android SpeechRecognizer and Percept implement this.
 * Only one backend may be active at a time (mic exclusivity).
 */
interface SttBackend {
    val displayName: String
    fun start()
    fun stop()
    fun release()
    fun isActive(): Boolean
    fun setListener(listener: SttListener?)
}

/**
 * Unified event interface — the union of what both backends produce.
 * EchoWireService implements this once and doesn't care which backend emits.
 */
interface SttListener {
    fun onPartialResult(text: String, language: String, timestampMs: Long)
    fun onFinalResult(
        text: String,
        alternatives: List<String>,
        confidences: FloatArray,
        language: String,
        sentenceType: String?,       // null for Android STT; COMMAND/QUESTION/STATEMENT for Percept
        timestampMs: Long,
        sessionDurationMs: Long,
        speechDurationMs: Long,
    )
    fun onAudioLevel(rmsDb: Float, timestampMs: Long)
    fun onStateChanged(listening: Boolean)
    fun onError(code: Int, message: String, timestampMs: Long)
}
