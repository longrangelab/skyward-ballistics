package com.longrange.dragcurve.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class BarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color: Int = 0xFF2E7D32.toInt()
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22888888 }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (10 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val rad = height / 2f
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(r, rad, rad, track)
        if (fraction > 0f) {
            bar.color = color
            r.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
            canvas.drawRoundRect(r, rad, rad, bar)
        }
    }
}
