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

    // Longest token text seen this utterance — safety net against Percept VAD trimming.
    // If Sentence.text is shorter than the best token, we use the token (more complete).
    @Volatile private var bestTokenText = ""

    override fun start() {
        sessionStartMs = System.currentTimeMillis()
        bestTokenText = ""
        try {
            percept = Percept.create(context, ownerProfile) { event ->
                when (event) {
                    is SpeechEvent.Token -> {
                        Log.d(TAG, "TOKEN [${event.text}] lang=${event.language}")
                        if (event.text.length > bestTokenText.length) bestTokenText = event.text
                        listener?.onPartialResult(
                            event.text,
                            event.language.toCode(),
                            event.timestampMs,
                        )
                    }
                    is SpeechEvent.Sentence -> {
                        val sentenceText = event.text
                        Log.d(TAG, "SENTENCE [${sentenceText}] best_token=[$bestTokenText] type=${event.type}")
                        // Use whichever text is longer: sentence or best accumulated token.
                        val finalText = if (bestTokenText.length > sentenceText.length)
                            bestTokenText else sentenceText
                        if (finalText != sentenceText) {
                            Log.i(TAG, "Recovered trimmed text: \"$finalText\" vs sentence \"$sentenceText\"")
                        }
                        bestTokenText = ""
                        // ownerSimilarity is null before enrollment; fall back to 1.0 so callers
                        // can always treat it as a valid confidence score.
                        val confidence = event.ownerSimilarity ?: 1.0f
                        listener?.onFinalResult(
                            text = finalText,
                            alternatives = listOf(finalText),
                            confidences = floatArrayOf(confidence),
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
                        Log.i(TAG, "Owner enrolled: ${event.sampleCount} samples, ready=${event.isReady}")
                    }
                    is SpeechEvent.RefinementSample -> {
                        Log.d(TAG, "Refinement sample accepted (session=${event.sessionSampleCount})")
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
        bestTokenText = ""
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
