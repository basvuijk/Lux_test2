package com.example.luxcurve

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editable brightness curve. X axis: ambient light (log scale, 1 - 10 000 lux).
 * Y axis: screen brightness (0 - 100 %). Drag a point up or down to change the curve.
 */
class CurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Brightness percentage of every control point. */
    var points: FloatArray = Curve.DEFAULT.copyOf()
        set(value) {
            field = value.copyOf()
            invalidate()
        }

    /** log10(lux) of the current sensor reading, or -1 for "unknown". Draws a live marker. */
    var liveLog: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the user lifts their finger after editing. */
    var onCurveChanged: ((FloatArray) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val plot = RectF()
    private var active = -1

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1E24.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A44.toInt()
        strokeWidth = 1f * dp
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A0AA.toInt()
        textSize = 11f * dp
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6D00.toInt()
        strokeWidth = 3f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6D00.toInt() }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        strokeWidth = 1.5f * dp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        plot.set(44f * dp, 14f * dp, w - 18f * dp, h - 28f * dp)
    }

    private fun xForLog(log: Float): Float =
        plot.left + (log - Curve.LOG_MIN) / (Curve.LOG_MAX - Curve.LOG_MIN) * plot.width()

    private fun xForPoint(i: Int): Float =
        plot.left + i.toFloat() / (Curve.POINT_COUNT - 1) * plot.width()

    private fun yForPercent(p: Float): Float = plot.bottom - p / 100f * plot.height()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f * dp, 12f * dp, bgPaint)

        // Vertical grid: one line per decade of lux.
        val labels = arrayOf("1", "10", "100", "1k", "10k")
        for (d in 0..4) {
            val x = xForLog(d.toFloat())
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val w = textPaint.measureText(labels[d])
            canvas.drawText(labels[d], x - w / 2, plot.bottom + 16f * dp, textPaint)
        }
        val unit = "lux"
        canvas.drawText(unit, plot.right - textPaint.measureText(unit), plot.bottom + 26f * dp, textPaint)

        // Horizontal grid: brightness percent.
        for (p in 0..100 step 25) {
            val y = yForPercent(p.toFloat())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            val label = "$p%"
            canvas.drawText(label, plot.left - 6f * dp - textPaint.measureText(label), y + 4f * dp, textPaint)
        }

        // The curve itself (straight segments = linear interpolation on the log axis).
        val path = Path()
        for (i in 0 until Curve.POINT_COUNT) {
            val x = xForPoint(i)
            val y = yForPercent(points[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, curvePaint)

        // Live marker for the current light reading.
        if (liveLog >= 0f) {
            val x = xForLog(liveLog.coerceIn(Curve.LOG_MIN, Curve.LOG_MAX))
            val y = yForPercent(Curve.percentForLog(liveLog, points))
            canvas.drawLine(x, plot.top, x, plot.bottom, livePaint)
            canvas.drawCircle(x, y, 5f * dp, livePaint)
        }

        // Control points.
        for (i in 0 until Curve.POINT_COUNT) {
            val r = if (i == active) 12f * dp else 8f * dp
            canvas.drawCircle(xForPoint(i), yForPercent(points[i]), r, dotPaint)
            canvas.drawCircle(xForPoint(i), yForPercent(points[i]), r, dotRingPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = nearestPoint(event.x)
                moveActiveTo(event.y)
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) moveActiveTo(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) onCurveChanged?.invoke(points.copyOf())
                active = -1
                invalidate()
            }
        }
        return true
    }

    private fun nearestPoint(x: Float): Int {
        var best = 0
        for (i in 1 until Curve.POINT_COUNT) {
            if (abs(xForPoint(i) - x) < abs(xForPoint(best) - x)) best = i
        }
        return best
    }

    private fun moveActiveTo(y: Float) {
        val percent = (plot.bottom - y) / plot.height() * 100f
        points[active] = percent.coerceIn(0f, 100f).roundToInt().toFloat()
        invalidate()
    }
}
