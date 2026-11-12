package com.longrange.dragcurve.ui.calc

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.longrange.dragcurve.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.google.android.material.R as MR

@SuppressLint("ViewConstructor")
class WindDialView(ctx: Context, private val onPick: (Double) -> Unit, private val onRelease: () -> Unit = {}) : View(ctx) {
    var angleDeg: Double = 90.0
        set(v) {
            field = WindText.normalize(v)
            if (!dragging) { settle?.cancel(); shownDeg = field }
            contentDescription = describe()
            invalidate()
        }

    private var shownDeg = 90.0
    private var dragging = false
    private var settle: ValueAnimator? = null

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = themeColor(MR.attr.colorSurfaceContainerLowest) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = themeColor(MR.attr.colorOnSurface) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = themeColor(MR.attr.colorOutline) }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = themeColor(MR.attr.colorOnSurface)
        textSize = min(15f * sp, 22f * d)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ContextCompat.getColor(ctx, R.color.sky_cyan) }
    private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND; color = themeColor(MR.attr.colorOnSurface)
    }
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = themeColor(MR.attr.colorOnSurface) }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = describe()
    }

    private fun describe() = "Wind direction dial: from ${WindText.clock(angleDeg)}. Tap the dial to change."

    override fun onMeasure(w: Int, h: Int) {
        val want = (150 * d).toInt()
        val size = resolveSize(want, w)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - 3f * d
        c.drawCircle(cx, cy, r, face)
        c.drawCircle(cx, cy, r, rim)
        for (h in 0 until 12) {
            val a = Math.toRadians(h * 30.0)
            val sx = sin(a).toFloat(); val sy = -cos(a).toFloat()
            if (h % 3 == 0) {
                val label = if (h == 0) "12" else h.toString()
                val rr = r - num.textSize * 0.95f
                c.drawText(label, cx + sx * rr, cy + sy * rr + num.textSize * 0.36f, num)
            } else {
                c.drawLine(cx + sx * (r - 9f * d), cy + sy * (r - 9f * d), cx + sx * (r - 2f * d), cy + sy * (r - 2f * d), tick)
            }
        }
        c.drawCircle(cx, cy, 5f * d, me)
        val a = Math.toRadians(shownDeg)
        val ux = sin(a).toFloat(); val uy = -cos(a).toFloat()
        val px = -uy; val py = ux
        val tail = r - num.textSize * 1.9f
        val tip = 11f * d
        val headLen = 16f * d; val headW = 11f * d; val shaftW = 4.5f * d
        path.reset()
        path.moveTo(cx + ux * tip, cy + uy * tip)
        path.lineTo(cx + ux * (tip + headLen) + px * headW, cy + uy * (tip + headLen) + py * headW)
        path.lineTo(cx + ux * (tip + headLen) + px * shaftW, cy + uy * (tip + headLen) + py * shaftW)
        path.lineTo(cx + ux * tail + px * shaftW, cy + uy * tail + py * shaftW)
        path.lineTo(cx + ux * tail - px * shaftW, cy + uy * tail - py * shaftW)
        path.lineTo(cx + ux * (tip + headLen) - px * shaftW, cy + uy * (tip + headLen) - py * shaftW)
        path.lineTo(cx + ux * (tip + headLen) - px * headW, cy + uy * (tip + headLen) - py * headW)
        path.close()
        c.drawPath(path, arrow)
        c.drawPath(path, arrowEdge)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                settle?.cancel()
                val dx = e.x - width / 2f; val dy = e.y - height / 2f
                if (dx * dx + dy * dy < (10f * d) * (10f * d)) return true
                val deg = WindText.normalize(Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())))
                shownDeg = deg
                val snapped = WindText.normalize(Math.rint(deg / 15.0) * 15.0)
                if (snapped != angleDeg) { angleDeg = snapped; onPick(snapped) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) { dragging = false; settleOnMark(); onRelease() }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun settleOnMark() {
        val from = shownDeg
        val turn = MotionRules.shortestTurn(from, angleDeg)
        if (turn == 0.0 || !Motion.on(context)) { shownDeg = angleDeg; invalidate(); return }
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MotionRules.DIAL_SETTLE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                shownDeg = if (f >= 1f) angleDeg else WindText.normalize(from + turn * f)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        settle?.cancel()
        shownDeg = angleDeg
        super.onDetachedFromWindow()
    }
}

object FixedText {
    @SuppressLint("WrongConstant")
    fun fit(tv: TextView, candidates: List<String>, widthPx: Int) {
        tv.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        tv.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        val w = (widthPx - tv.compoundPaddingLeft - tv.compoundPaddingRight).coerceAtLeast(1)
        val tallest = candidates.maxOf {
            StaticLayout.Builder.obtain(it, 0, it.length, tv.paint, w)
                .setIncludePad(tv.includeFontPadding)
                .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build().height
        }
        val h = tallest + tv.compoundPaddingTop + tv.compoundPaddingBottom
        val lp = tv.layoutParams ?: return
        if (lp.height != h) { lp.height = h; tv.layoutParams = lp }
    }
}
