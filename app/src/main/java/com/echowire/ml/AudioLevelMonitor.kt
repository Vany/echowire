package com.echowire.ml

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Independent RMS dBFS meter — runs its own AudioRecord.
 * Concurrent capture from two AudioRecords in the same app is fully supported on Android 10+ (API 29+).
 * Fires ~30 Hz callbacks with real dBFS in range [-60, 0].
 */
class AudioLevelMonitor(
    private val onLevel: (dBFS: Float) -> Unit,
) {
    companion object {
        private const val TAG = "AudioLevelMonitor"
        private const val SAMPLE_RATE = 16000
        // 33ms chunks → ~30 Hz
        private val CHUNK_SAMPLES = SAMPLE_RATE * 33 / 1000
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSamples = maxOf(CHUNK_SAMPLES * 2, minBuf / 2)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSamples * 2,  // bytes
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord create failed", e)
                running = false
                return@Thread
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (mic conflict?)")
                recorder.release()
                running = false
                return@Thread
            }

            val buf = ShortArray(CHUNK_SAMPLES)
            recorder.startRecording()
            Log.i(TAG, "Started (${SAMPLE_RATE}Hz, chunk=$CHUNK_SAMPLES samples)")

            while (running) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) {
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / n) / 32768.0
                    val db = if (rms > 1e-5) (20.0 * log10(rms)).toFloat() else -60f
                    onLevel(db)
                }
            }

            recorder.stop()
            recorder.release()
            Log.i(TAG, "Stopped")
        }, "AudioLevelMonitor").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY }
        thread!!.start()
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
