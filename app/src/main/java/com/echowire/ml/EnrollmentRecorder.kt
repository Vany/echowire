package com.echowire.ml

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Records a short PCM sample for owner voice enrollment.
 * Returns ShortArray suitable for OwnerProfile.addSample().
 *
 * IMPORTANT: No other AudioRecord may be active (mic exclusivity).
 * Caller must stop the active STT backend before recording.
 */
class EnrollmentRecorder(
    private val durationMs: Int = 3000,
    private val sampleRate: Int = 16000,
) {
    companion object {
        private const val TAG = "EnrollmentRecorder"
        const val DURATION_MS = 3000
    }

    fun record(onComplete: (ShortArray) -> Unit, onError: (Exception) -> Unit) {
        Thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(bufferSize, sampleRate), // at least 1s buffer
                )
                val totalSamples = sampleRate * durationMs / 1000
                val pcm = ShortArray(totalSamples)
                recorder.startRecording()
                Log.i(TAG, "Recording ${durationMs}ms enrollment sample...")

                var offset = 0
                while (offset < totalSamples) {
                    val read = recorder.read(pcm, offset, minOf(bufferSize / 2, totalSamples - offset))
                    if (read > 0) offset += read else break
                }

                recorder.stop()
                recorder.release()
                Log.i(TAG, "Recorded $offset samples")
                onComplete(pcm)
            } catch (e: Exception) {
                Log.e(TAG, "Enrollment recording failed", e)
                onError(e)
            }
        }.start()
    }
}
