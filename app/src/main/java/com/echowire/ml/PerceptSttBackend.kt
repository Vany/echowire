package com.echowire.ml

import android.content.Context
import android.util.Log
import com.percept.Language
import com.percept.OwnerProfileImpl
import com.percept.Percept
import com.percept.SpeechEvent

/**
 * Wraps the Percept on-device STT library into the SttBackend interface.
 * Maps SpeechEvent.Token → onPartialResult, SpeechEvent.Sentence → onFinalResult.
 *
 * Percept manages its own AudioRecord and 5-thread pipeline.
 * Only owner speech produces events (non-owner silently discarded).
 */
class PerceptSttBackend(
    private val context: Context,
    private val ownerProfile: OwnerProfileImpl,
) : SttBackend {

    companion object {
        private const val TAG = "PerceptSttBackend"
    }

    override val displayName = "Percept"

    private var percept: Percept? = null
    private var listener: SttListener? = null
    private var sessionStartMs = 0L

    // Last token text received — may be richer than Sentence.text when Percept VAD fires early.
    // Sentence.text is used if it's longer (i.e., Percept corrected something); otherwise token.
    @Volatile private var lastTokenText = ""

    override fun start() {
        sessionStartMs = System.currentTimeMillis()
        lastTokenText = ""
        try {
            percept = Percept.create(context, ownerProfile) { event ->
                when (event) {
                    is SpeechEvent.Token -> {
                        lastTokenText = event.text
                        listener?.onPartialResult(
                            event.text,
                            event.language.toCode(),
                            event.timestampMs,
                        )
                    }
                    is SpeechEvent.Sentence -> {
                        // Percept VAD sometimes finalizes before the last word is committed.
                        // Use whichever text is longer: sentence (corrected) or last token (fuller).
                        val sentenceText = event.text
                        val finalText = if (lastTokenText.length > sentenceText.length)
                            lastTokenText else sentenceText
                        if (finalText != sentenceText) {
                            Log.d(TAG, "Using token text over trimmed sentence: \"$finalText\" vs \"$sentenceText\"")
                        }
                        lastTokenText = ""
                        listener?.onFinalResult(
                            text = finalText,
                            alternatives = listOf(finalText),
                            confidences = floatArrayOf(1.0f),
                            language = event.language.toCode(),
                            sentenceType = event.type.name,
                            timestampMs = event.endMs,
                            sessionDurationMs = event.endMs - sessionStartMs,
                            speechDurationMs = event.endMs - event.startMs,
                        )
                    }
                    is SpeechEvent.AudioLevel -> {
                        listener?.onAudioLevel(event.rmsDb, System.currentTimeMillis())
                    }
                    is SpeechEvent.Enrolled -> {
                        Log.i(TAG, "Owner enrolled: ${event.sampleCount} samples")
                    }
                }
            }
            percept?.start()
            listener?.onStateChanged(true)
            Log.i(TAG, "Started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Percept", e)
            listener?.onError(-1, "Percept start failed: ${e.message}", System.currentTimeMillis())
        }
    }

    override fun stop() {
        lastTokenText = ""
        try {
            percept?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Percept", e)
        }
        percept = null
        listener?.onStateChanged(false)
        Log.i(TAG, "Stopped")
    }

    override fun release() = stop()

    override fun isActive() = percept != null

    override fun setListener(listener: SttListener?) { this.listener = listener }
}

private fun Language.toCode(): String = when (this) {
    Language.RU -> "ru-RU"
    Language.EN -> "en-US"
    Language.UNKNOWN -> "unknown"
}
