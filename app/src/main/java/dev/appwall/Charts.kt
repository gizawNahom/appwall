package dev.appwall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.AttributeSet
import android.view.View

/**
 * Small bar chart: one bar per value, oldest first; the newest bar is drawn in the accent color.
 * Bars share the view's width evenly, so the same chart works as a tiny sparkline or a wide one.
 */
class SparklineView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gap = 3 * density
    private val radius = 2 * density
    private val minHeight = 2 * density
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.spark_dim) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val rect = RectF()

    var values: IntArray = IntArray(0)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val barWidth = (width - gap * (values.size - 1)) / values.size
        var x = 0f
        values.forEachIndexed { i, v ->
            val h = (height * v.toFloat() / max).coerceAtLeast(minHeight)
            rect.set(x, height - h, x + barWidth, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, if (i == values.lastIndex) accent else dim)
            x += barWidth + gap
        }
    }
}

/** A thin horizontal meter: a track with a filled part for [fraction] (0..1). */
class MeterView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.bar) }
    private val rect = RectF()

    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) {
            rect.right = (width * fraction).coerceAtLeast(height.toFloat()) // never thinner than a dot
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}

/** Sets a bundled typeface on a span of text, e.g. Geist Mono for the number in a sentence. */
class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = typeface
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = typeface
    }
}
