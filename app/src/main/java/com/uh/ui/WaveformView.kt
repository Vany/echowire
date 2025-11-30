package com.uh.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Moving window waveform analyzer view that displays real-time audio levels.
 * Color indicates recognition state: green (idle), yellow (recognizing), red (error).
 *
 * Thread-safe: UI updates happen on main thread via postInvalidate().
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SAMPLE_COUNT = 100  // Number of samples to display in window
        private const val WAVEFORM_STROKE_WIDTH = 4f  // Line thickness
    }

    // State enum for color selection
    enum class State {
        IDLE,         // Green: not recognizing
        RECOGNIZING,  // Yellow: recognition in progress
        ERROR         // Red: error occurred
    }

    // Audio level samples (circular buffer, normalized 0.0-1.0)
    private val samples = FloatArray(SAMPLE_COUNT) { 0f }
    private var sampleIndex = 0  // Current write position in circular buffer

    // Current state
    @Volatile
    private var currentState: State = State.IDLE

    // Paint objects for drawing (reused for performance)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = WAVEFORM_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    // Path for waveform (reused)
    private val path = Path()

    /**
     * Add audio level sample to waveform (thread-safe).
     * @param level Audio level 0.0 (silence) to 1.0 (max)
     */
    fun addSample(level: Float) {
        // Coerce to valid range
        val normalizedLevel = level.coerceIn(0f, 1f)

        // Write to circular buffer
        synchronized(samples) {
            samples[sampleIndex] = normalizedLevel
            sampleIndex = (sampleIndex + 1) % SAMPLE_COUNT
        }

        // Request redraw on main thread
        postInvalidate()
    }

    /**
     * Set recognition state (thread-safe).
     * Changes waveform color.
     */
    fun setState(state: State) {
        if (currentState != state) {
            currentState = state
            postInvalidate()
        }
    }

    /**
     * Clear waveform (reset to silence).
     */
    fun clear() {
        synchronized(samples) {
            samples.fill(0f)
            sampleIndex = 0
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // Draw black background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        // Set color based on state
        paint.color = when (currentState) {
            State.IDLE -> Color.rgb(0, 255, 0)        // Green
            State.RECOGNIZING -> Color.rgb(255, 255, 0)  // Yellow
            State.ERROR -> Color.rgb(255, 0, 0)       // Red
        }

        // Build waveform path from samples (thread-safe copy)
        val samplesCopy: FloatArray
        val currentIndex: Int
        synchronized(samples) {
            samplesCopy = samples.clone()
            currentIndex = sampleIndex
        }

        path.reset()

        val centerY = height / 2f
        val stepX = width / (SAMPLE_COUNT - 1)

        // Start from oldest sample (circular buffer)
        var firstPoint = true
        for (i in 0 until SAMPLE_COUNT) {
            val sampleIdx = (currentIndex + i) % SAMPLE_COUNT
            val sample = samplesCopy[sampleIdx]

            // Convert sample to Y coordinate (amplitude from center)
            val amplitude = sample * (height / 2f) * 0.9f  // 90% of half-height
            val x = i * stepX
            val y = centerY - amplitude + (Math.random().toFloat() - 0.5f) * amplitude * 0.2f  // Slight variation for organic look

            if (firstPoint) {
                path.moveTo(x, y)
                firstPoint = false
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw waveform
        canvas.drawPath(path, paint)

        // Draw center line (reference)
        paint.color = Color.GRAY
        paint.strokeWidth = 1f
        canvas.drawLine(0f, centerY, width, centerY, paint)
        paint.strokeWidth = WAVEFORM_STROKE_WIDTH  // Restore
    }
}
