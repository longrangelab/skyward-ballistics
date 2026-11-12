package com.longrange.dragcurve.ui.calc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.longrange.dragcurve.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

object MotionRules {
    const val LOGO_TURN_MS = 24_000L
    const val BUSY_TURN_MS = 1_400L
    const val BUSY_DELAY_MS = 150L
    const val VALUE_MS = 150L
    const val FOLD_MS = 220L
    const val TAB_MS = 180L
    const val PRESS_IN_MS = 80L
    const val PRESS_OUT_MS = 140L
    const val DIAL_SETTLE_MS = 140L
    const val LAUNCH_TURN_MS = 900L
    const val LAUNCH_TURN_DEG = 50f
    const val LAUNCH_FADE_MS = 220L

    val TRANSITIONS_MS = listOf(VALUE_MS, FOLD_MS, TAB_MS, PRESS_IN_MS, PRESS_OUT_MS, DIAL_SETTLE_MS, LAUNCH_FADE_MS)

    fun enabled(animatorDurationScale: Float, reducedSetting: Boolean): Boolean =
        !reducedSetting && animatorDurationScale > 0f && !animatorDurationScale.isNaN()

    private val number = Regex("\\d+(?:\\.\\d+)?")

    fun valueSlide(old: String, new: String): Int {
        if (old == new) return 0
        val a = number.find(old)?.value?.toDoubleOrNull()
        val b = number.find(new)?.value?.toDoubleOrNull()
        return if (a != null && b != null && b < a) -1 else 1
    }

    fun shortestTurn(from: Double, to: Double): Double {
        val d = ((to - from) % 360 + 540) % 360 - 180
        return if (d == -180.0) 180.0 else d
    }

    fun minRedrawDeg(sizePx: Int): Float = Math.toDegrees(0.35 / (sizePx.coerceAtLeast(2) / 2.0)).toFloat()
}

object MotionPref {
    val LABELS = listOf("Full", "Reduced")
    private const val PREFS = "calc_ui"
    private const val KEY = "reducedMotion"

    fun reduced(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(ctx: Context, reduced: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, reduced).apply()
    }
}

object Motion {
    private val standard = FastOutSlowInInterpolator()
    private val settleIn = DecelerateInterpolator(1.6f)

    fun on(ctx: Context): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return MotionRules.enabled(scale, MotionPref.reduced(ctx))
    }

    fun setTextIfChanged(tv: TextView, text: CharSequence) {
        if (tv.text?.toString() != text.toString()) tv.text = text
    }

    fun setValue(tv: TextView, text: String) {
        val old = tv.text?.toString().orEmpty()
        if (old == text) return
        tv.text = text
        if (old.isEmpty() || old == "—") return
        settle(tv, old, text)
    }

    fun settle(v: View, old: String, new: String) {
        val dir = MotionRules.valueSlide(old, new)
        if (dir == 0 || !on(v.context)) return
        v.animate().cancel()
        v.alpha = 0.3f
        v.translationY = dir * 6f * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(MotionRules.VALUE_MS).setInterpolator(settleIn).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun pressFeedback(v: View, pressedScale: Float = 0.94f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (view.isEnabled && on(view.context)) {
                    view.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(MotionRules.PRESS_IN_MS).setInterpolator(standard).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (view.scaleX != 1f || view.scaleY != 1f) {
                    if (on(view.context)) view.animate().scaleX(1f).scaleY(1f).setDuration(MotionRules.PRESS_OUT_MS).setInterpolator(standard).start()
                    else { view.animate().cancel(); view.scaleX = 1f; view.scaleY = 1f }
                }
            }
            false
        }
    }

    fun tabIn(v: View, direction: Int) {
        v.animate().cancel()
        if (!on(v.context)) { v.alpha = 1f; v.translationX = 0f; return }
        v.alpha = 0f
        v.translationX = direction * 16f * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationX(0f).setDuration(MotionRules.TAB_MS).setInterpolator(settleIn).start()
    }

    fun reveal(target: RevealLayout, open: Boolean, alongside: (Float) -> Unit = {}) {
        (target.getTag(R.id.content) as? Animator)?.cancel()
        val end = if (open) 1f else 0f
        if (!on(target.context)) {
            target.fraction = end; target.alpha = 1f
            target.visibility = if (open) View.VISIBLE else View.GONE
            alongside(end)
            return
        }
        target.visibility = View.VISIBLE
        val a = ValueAnimator.ofFloat(target.fraction, end)
        a.duration = MotionRules.FOLD_MS
        a.interpolator = standard
        a.addUpdateListener {
            val f = it.animatedValue as Float
            target.fraction = f; target.alpha = f
            alongside(f)
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                target.alpha = 1f
                if (!open) target.visibility = View.GONE
            }
        })
        target.setTag(R.id.content, a)
        a.start()
    }
}

class RevealLayout(ctx: Context) : ViewGroup(ctx) {
    var fraction: Float = 1f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) { field = c; requestLayout() }
        }

    init { clipChildren = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null || child.visibility == GONE) {
            setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), 0)
            return
        }
        child.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(resolveSize(child.measuredWidth, widthMeasureSpec), (child.measuredHeight * fraction).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, r - l, child.measuredHeight)
    }
}

class SpinLogoDrawable(
    private val bore: Bitmap,
    private val rifling: Bitmap,
    private val sizePx: Int,
    turnMs: Long,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val minStep = MotionRules.minRedrawDeg(sizePx)
    private var angle = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = turnMs
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val a = it.animatedValue as Float
            if (abs(a - angle) >= minStep) { angle = a; invalidateSelf() }
        }
    }

    val isSpinning: Boolean get() = animator.isStarted && !animator.isPaused

    var playTime: Long
        get() = animator.currentPlayTime
        set(v) {
            animator.currentPlayTime = v.coerceAtLeast(0L) % animator.duration
            angle = animator.animatedValue as? Float ?: angle
            invalidateSelf()
        }

    fun spin() {
        if (!animator.isStarted) animator.start() else if (animator.isPaused) animator.resume()
    }

    fun pause() {
        if (animator.isStarted && !animator.isPaused) animator.pause()
    }

    fun rest() {
        animator.cancel()
        if (angle != 0f) { angle = 0f; invalidateSelf() }
    }

    override fun draw(canvas: Canvas) {
        dst.set(bounds)
        canvas.drawBitmap(bore, null, dst, paint)
        val save = canvas.save()
        canvas.rotate(angle, dst.centerX(), dst.centerY())
        canvas.drawBitmap(rifling, null, dst, paint)
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        fun create(ctx: Context, sizeDp: Int, onNavy: Boolean, turnMs: Long): SpinLogoDrawable {
            val px = ctx.dp(sizeDp)
            fun layer(res: Int) = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(ctx.resources, res), px, px, true)
            return SpinLogoDrawable(layer(R.drawable.logo_bore),
                layer(if (onNavy) R.drawable.logo_rifling_light else R.drawable.logo_rifling_navy), px, turnMs)
        }

        fun isNight(ctx: Context): Boolean =
            ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}

class LaunchArtView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val base: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.launch_base)
    private val rifling: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.launch_rifling)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    var turn: Float = 0f
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onDraw(c: Canvas) {
        val w = width - paddingLeft - paddingRight; val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val s = min(w / base.width.toFloat(), h / base.height.toFloat())
        val bw = base.width * s; val bh = base.height * s
        val left = paddingLeft + (w - bw) / 2f; val top = paddingTop + (h - bh) / 2f
        dst.set(left, top, left + bw, top + bh)
        c.drawBitmap(base, null, dst, paint)
        val cx = left + CENTRE_X * bw; val cy = top + CENTRE_Y * bh; val half = SIZE * bw / 2f
        dst.set(cx - half, cy - half, cx + half, cy + half)
        val save = c.save()
        c.rotate(turn, cx, cy)
        c.drawBitmap(rifling, null, dst, paint)
        c.restoreToCount(save)
    }

    private companion object {
        const val CENTRE_X = 0.49710f
        const val CENTRE_Y = 0.38272f
        const val SIZE = 0.63647f
    }
}
