package com.echowire.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max

/**
 * DB meter showing peak volume for last second.
 * Displays in decibels (dB) with color-coded level indicator.
 *
 * Color Zones:
 * - Green: below -20 dB (quiet / normal)
 * - Yellow: -20 dB to -8 dB (loud)
 * - Red: -8 dB to 0 dB (very loud / clipping)
 *
 * Peak hold: displays maximum level from last 1 second of samples.
 */
class DbMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val PEAK_HOLD_DURATION_MS = 150   // 150ms peak hold
        private const val DB_MIN = -60f  // Minimum dB to display
        private const val DB_MAX = 0f    // Maximum dB (0 dB = full scale)

        // Color thresholds
        private const val DB_GREEN_MAX = -20f   // green below this
        private const val DB_YELLOW_MAX = -8f   // yellow -20..-8, red above
    }

    // Peak tracking
    private val peakSamples = mutableListOf<PeakSample>()

    @Volatile
    private var currentPeakDb: Float = DB_MIN

    // Paint objects (reused for performance)
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val meterPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Reusable rect for meter bar
    private val meterRect = RectF()

    /**
     * Internal class to track peak samples with timestamps.
     */
    private data class PeakSample(
        val db: Float,
        val timestamp: Long
    )

    /**
     * Add a real dBFS sample directly. Use this from AudioLevelMonitor.
     * @param db  Real RMS dBFS value, typically in [-60, 0].
     */
    fun addDb(db: Float) {
        recordPeak(db.coerceIn(DB_MIN, DB_MAX))
    }

    /**
     * Add a normalized 0..1 level (legacy — converts to dB internally).
     * @param level  Normalized 0.0 (silence) to 1.0 (max)
     */
    fun addSample(level: Float) {
        val db = if (level > 0.0001f) 20f * log10(level) else DB_MIN
        recordPeak(db)
    }

    private fun recordPeak(db: Float) {
        val timestamp = System.currentTimeMillis()
        synchronized(peakSamples) {
            peakSamples.add(PeakSample(db, timestamp))
            val cutoff = timestamp - PEAK_HOLD_DURATION_MS
            peakSamples.removeAll { it.timestamp < cutoff }
            currentPeakDb = peakSamples.maxOfOrNull { it.db } ?: DB_MIN
        }
        postInvalidate()
    }

    /**
     * Clear meter (reset to silence).
     */
    fun clear() {
        synchronized(peakSamples) {
            peakSamples.clear()
            currentPeakDb = DB_MIN
        }
        postInvalidate()
    }

    /**
     * Get current peak in dB.
     */
    fun getPeakDb(): Float = currentPeakDb

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val peakDb = currentPeakDb
        val fillRatio = ((peakDb - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)

        // Color zone: split the fill into green / yellow / red segments
        val greenEnd  = ((DB_GREEN_MAX  - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)
        val yellowEnd = ((DB_YELLOW_MAX - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)

        fun drawSegment(from: Float, to: Float, color: Int) {
            val x0 = w * from
            val x1 = w * minOf(to, fillRatio)
            if (x1 > x0) {
                meterPaint.color = color
                meterRect.set(x0, 0f, x1, h)
                canvas.drawRect(meterRect, meterPaint)
            }
        }

        drawSegment(0f,        greenEnd,  0xFF1DB954.toInt())  // Spotify green
        drawSegment(greenEnd,  yellowEnd, 0xFFFFC107.toInt())  // Amber
        drawSegment(yellowEnd, 1f,        0xFFE53935.toInt())  // Red

        // Thin border
        canvas.drawRect(0f, 0f, w, h, borderPaint)
    }
}
