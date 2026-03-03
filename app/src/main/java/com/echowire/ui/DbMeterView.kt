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
 * - Green: -60 dB to -20 dB (quiet to normal speech)
 * - Yellow: -20 dB to -6 dB (loud speech)
 * - Red: -6 dB to 0 dB (very loud, near clipping)
 *
 * Peak hold: displays maximum level from last 1 second of samples.
 */
class DbMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val PEAK_HOLD_DURATION_MS = 1000  // 1 second peak hold
        private const val DB_MIN = -60f  // Minimum dB to display
        private const val DB_MAX = 0f    // Maximum dB (0 dB = full scale)

        // Color thresholds
        private const val DB_GREEN_MAX = -20f
        private const val DB_YELLOW_MAX = -6f
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
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
     * Add audio level sample.
     * @param level Normalized audio level 0.0 (silence) to 1.0 (max)
     */
    fun addSample(level: Float) {
        val timestamp = System.currentTimeMillis()

        // Convert normalized level to dB
        // RMS to dB: 20 * log10(rms)
        // Add reference offset: full scale = 0 dB
        val db = if (level > 0.0001f) {
            20f * log10(level)
        } else {
            DB_MIN  // Below noise floor
        }

        synchronized(peakSamples) {
            // Add new sample
            peakSamples.add(PeakSample(db, timestamp))

            // Remove samples older than 1 second
            val cutoffTime = timestamp - PEAK_HOLD_DURATION_MS
            peakSamples.removeAll { it.timestamp < cutoffTime }

            // Update current peak (max dB in window)
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

        val width = width.toFloat()
        val height = height.toFloat()

        // Draw black background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        // Get current peak (thread-safe)
        val peakDb = currentPeakDb

        // Calculate meter fill percentage (DB_MIN to DB_MAX)
        val fillRatio = ((peakDb - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)

        // Determine color based on dB level
        meterPaint.color = when {
            peakDb < DB_GREEN_MAX -> Color.GREEN        // Quiet to normal
            peakDb < DB_YELLOW_MAX -> Color.YELLOW      // Loud
            else -> Color.RED                            // Very loud
        }

        // Draw meter bar (left to right fill)
        val meterWidth = width * 0.7f  // 70% of width for bar
        val meterHeight = height * 0.6f
        val meterLeft = width * 0.05f
        val meterTop = (height - meterHeight) / 2f

        meterRect.set(
            meterLeft,
            meterTop,
            meterLeft + (meterWidth * fillRatio),
            meterTop + meterHeight
        )
        canvas.drawRect(meterRect, meterPaint)

        // Draw border around meter
        canvas.drawRect(
            meterLeft,
            meterTop,
            meterLeft + meterWidth,
            meterTop + meterHeight,
            borderPaint
        )

        // Draw dB value text
        val dbText = if (peakDb > DB_MIN + 1f) {
            String.format("%.1f dB", peakDb)
        } else {
            "-∞ dB"
        }

        val textX = meterLeft + meterWidth + (width - meterLeft - meterWidth) / 2f
        val textY = height / 2f + textPaint.textSize / 3f  // Center vertically

        canvas.drawText(dbText, textX, textY, textPaint)

        // Draw scale markers (optional, for reference)
        drawScaleMarkers(canvas, meterLeft, meterTop, meterWidth, meterHeight)
    }

    /**
     * Draw dB scale reference markers.
     */
    private fun drawScaleMarkers(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val markerPaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = 1f
        }

        val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }

        // Draw markers at -60, -40, -20, -6, 0 dB
        val markers = listOf(-60f, -40f, -20f, -6f, 0f)

        for (db in markers) {
            val ratio = ((db - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)
            val x = left + width * ratio

            // Draw vertical line
            canvas.drawLine(x, top, x, top + height, markerPaint)

            // Draw label below meter
            canvas.drawText(
                db.toInt().toString(),
                x,
                top + height + 25f,
                smallTextPaint
            )
        }
    }
}
