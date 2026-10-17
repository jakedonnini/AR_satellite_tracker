package com.jakedonnini.sattracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * A minimal vertical slider: drag anywhere along it; bottom is 0, top is 1. Draws its own
 * track, fill, thumb and the [topLabel]/[bottomLabel] / [valueLabel] text.
 */
class VerticalSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var value: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onValueChanged: ((Float) -> Unit)? = null
    var topLabel = ""
    var bottomLabel = ""
    var valueLabel = ""
        set(v) {
            field = v
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 4 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(trackPaint).apply { color = Color.WHITE }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }

    private val pad get() = 24 * dp
    private fun yFor(v: Float) = height - pad - v * (height - 2 * pad)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val top = yFor(1f)
        val bottom = yFor(0f)
        val thumbY = yFor(value)
        canvas.drawLine(cx, top, cx, bottom, trackPaint)
        canvas.drawLine(cx, thumbY, cx, bottom, fillPaint)
        canvas.drawCircle(cx, thumbY, 10 * dp, thumbPaint)
        canvas.drawText(topLabel, cx, top - 10 * dp, textPaint)
        canvas.drawText(bottomLabel, cx, bottom + 20 * dp, textPaint)
        if (valueLabel.isNotEmpty()) {
            canvas.drawText(valueLabel, cx - 26 * dp, thumbY + textPaint.textSize / 3, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // continuous drag control; performClick on release
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = (height - pad - event.y) / (height - 2 * pad)
                onValueChanged?.invoke(value)
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
