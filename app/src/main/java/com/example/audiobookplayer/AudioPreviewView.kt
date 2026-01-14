package com.example.audiobookplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class AudioPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var barValues: FloatArray = FloatArray(DEFAULT_BARS) { DEFAULT_BAR_HEIGHT }
    private var progressRatio = 0f
    private var waveformSeed = 0

    init {
        val baseColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.LTGRAY)
        val highlightColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        barPaint.color = baseColor
        barPaint.alpha = 90
        progressPaint.color = highlightColor
        progressPaint.alpha = 200
    }

    fun setWaveformSeed(seed: Int) {
        waveformSeed = seed
        generateWaveform()
    }

    fun setProgress(progress: Float) {
        progressRatio = min(1f, max(0f, progress))
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        generateWaveform()
    }

    private fun generateWaveform() {
        val barCount = max(DEFAULT_BARS, width / BAR_WIDTH_HINT)
        val random = Random(waveformSeed)
        barValues = FloatArray(barCount) {
            MIN_BAR_HEIGHT + random.nextFloat() * (1f - MIN_BAR_HEIGHT)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (barValues.isEmpty()) {
            return
        }
        val availableWidth = width.toFloat()
        val barWidth = availableWidth / barValues.size
        val barSpacing = barWidth * BAR_SPACING_RATIO
        val cornerRadius = barWidth * CORNER_RADIUS_RATIO
        val progressIndex = ceil(progressRatio * barValues.size).toInt()
        val viewHeight = height.toFloat()
        barValues.forEachIndexed { index, barValue ->
            val barHeight = viewHeight * barValue
            val left = index * barWidth
            val right = left + barWidth - barSpacing
            val top = (viewHeight - barHeight) / 2f
            val paint = if (index < progressIndex) progressPaint else barPaint
            canvas.drawRoundRect(left, top, right, top + barHeight, cornerRadius, cornerRadius, paint)
        }
    }

    companion object {
        private const val DEFAULT_BARS = 60
        private const val DEFAULT_BAR_HEIGHT = 0.4f
        private const val MIN_BAR_HEIGHT = 0.2f
        private const val BAR_SPACING_RATIO = 0.2f
        private const val CORNER_RADIUS_RATIO = 0.3f
        private const val BAR_WIDTH_HINT = 12
    }
}
