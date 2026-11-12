package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.longrange.dragcurve.R
import com.longrange.dragcurve.engine.DragTables
import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.CdPoint
import com.google.android.material.R as MR

data class RowDraft(var bc: Double?, var minVelFps: Double?)

object BcRows {
    fun drafts(rows: List<BcPoint>): MutableList<RowDraft> =
        rows.sortedByDescending { it.minVelFps }.map { RowDraft(it.bc, it.minVelFps) }.toMutableList()
            .ifEmpty { mutableListOf(RowDraft(null, 0.0)) }

    fun finish(drafts: List<RowDraft>): Pair<List<BcPoint>?, String?> {
        val filled = drafts.filter { it.bc != null || it.minVelFps != null }
        if (filled.any { it.bc == null }) return null to "A BC row has a min velocity but no BC."
        val rows = if (filled.size == 1) listOf(BcPoint(filled[0].bc!!, filled[0].minVelFps ?: 0.0))
        else {
            if (filled.any { it.minVelFps == null }) return null to "With several BCs, every row needs a min velocity (one of them 0)."
            filled.map { BcPoint(it.bc!!, it.minVelFps!!) }
        }
        val err = BcSelection.validate(rows)
        return if (err != null) null to err else rows.sortedByDescending { it.minVelFps } to null
    }

    fun encode(drafts: List<RowDraft>): String =
        drafts.joinToString(";") { "${it.bc ?: ""},${it.minVelFps ?: ""}" }

    fun decode(s: String?): MutableList<RowDraft>? {
        if (s == null) return null
        if (s.isEmpty()) return mutableListOf()
        return s.split(';').map { p ->
            val (a, b) = (p.split(',') + listOf("", "")).take(2)
            RowDraft(a.toDoubleOrNull(), b.toDoubleOrNull())
        }.toMutableList()
    }
}

fun bcRowsView(ctx: Context, settings: AppSettings, rows: MutableList<RowDraft>, onChange: () -> Unit = {}, onStructure: () -> Unit): View {
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val velU = Units.label(Quantity.VELOCITY, settings)
    fun field(hint: String, value: String, onChange: (Double?) -> Unit) = EditText(ctx).apply {
        isSaveEnabled = false
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setSelectAllOnFocus(true)
        maxLines = 1
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) = onChange(Num.parse(e))
        })
    }
    box.addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        fun cap(t: String, w: Float) = addView(TextView(ctx).apply { text = t; alpha = 0.9f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w))
        cap("BC", 1f); cap("Min Vel ($velU)", 1.3f)
        addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(52), 0))
    })
    rows.forEachIndexed { i, r ->
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bc = field("BC", r.bc?.let { Num.trim(it, 4) } ?: "") { r.bc = it; onChange() }
        val vel = field("Min Vel ($velU)", r.minVelFps?.let { Num.trim(Units.toDisplay(Quantity.VELOCITY, it, settings), 0) } ?: "") {
            r.minVelFps = it?.let { v -> Units.fromDisplay(Quantity.VELOCITY, v, settings) }
        }
        line.addView(bc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(vel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        line.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_calc_delete)
            contentDescription = "Remove row"
            background = null
            alpha = 0.6f
            isEnabled = rows.size > 1
            setOnClickListener { if (i < rows.size) { rows.removeAt(i); onStructure() } }
        }, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))
        box.addView(line)
    }
    box.addView(MaterialButton(ctx, null, MR.attr.borderlessButtonStyle).apply {
        text = "Add BC row"
        setIconResource(R.drawable.ic_calc_add)
        setOnClickListener { rows.add(RowDraft(null, null)); onStructure() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return box
}

fun bcRowsText(rows: List<BcPoint>, settings: AppSettings): String =
    rows.sortedByDescending { it.minVelFps }.joinToString("\n") {
        Num.fixed(it.bc, 3) + "   ≥ " + Num.trim(Units.toDisplay(Quantity.VELOCITY, it.minVelFps, settings), 0) + " " +
            Units.label(Quantity.VELOCITY, settings)
    }

class DragCurveView(ctx: Context, private val curve: List<CdPoint>) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }
    private val ref = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(4).toFloat(), ctx.dp(4).toFloat()), 0f)
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; textSize = ctx.dp(14).toFloat() }

    init {
        minimumHeight = ctx.dp(200)
        line.color = themeColor(MR.attr.colorPrimary)
        ref.color = themeColor(MR.attr.colorOnSurfaceVariant)
        axis.color = themeColor(MR.attr.colorOnSurfaceVariant)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dp(200))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (curve.size < 2) return
        val pts = curve.sortedBy { it.mach }
        val m0 = pts.first().mach; val m1 = pts.last().mach
        val g7 = pts.map { DragTables.lookup(DragTables.G7, it.mach) }
        val cdMax = (pts.maxOf { it.cd }).coerceAtLeast(g7.maxOrNull() ?: 0.0) * 1.1
        val left = context.dp(46).toFloat(); val bottom = height - context.dp(26).toFloat()
        val right = width - context.dp(8).toFloat(); val top = context.dp(8).toFloat()
        fun x(m: Double) = (left + (m - m0) / (m1 - m0).coerceAtLeast(1e-6) * (right - left)).toFloat()
        fun y(cd: Double) = (bottom - cd / cdMax * (bottom - top)).toFloat()
        c.drawLine(left, bottom, right, bottom, axis); c.drawLine(left, top, left, bottom, axis)
        c.drawText("Cd", context.dp(4).toFloat(), top + axis.textSize, axis)
        c.drawText(Num.trim(cdMax, 2), context.dp(4).toFloat(), top + 2.2f * axis.textSize, axis)
        c.drawText("Mach " + Num.trim(m0, 2), left, height.toFloat() - 4, axis)
        val endLabel = Num.trim(m1, 2)
        c.drawText(endLabel, right - axis.measureText(endLabel), height.toFloat() - 4, axis)
        val pr = Path(); val pl = Path()
        pts.forEachIndexed { i, p ->
            if (i == 0) { pl.moveTo(x(p.mach), y(p.cd)); pr.moveTo(x(p.mach), y(g7[i])) }
            else { pl.lineTo(x(p.mach), y(p.cd)); pr.lineTo(x(p.mach), y(g7[i])) }
        }
        c.drawPath(pr, ref)
        c.drawPath(pl, line)
    }
}

fun curveCaption(ctx: Context, curve: List<CdPoint>): TextView = TextView(ctx).apply {
    val s = curve.sortedBy { it.mach }
    text = "Measured Cd vs Mach (${s.size} points, Mach ${Num.trim(s.first().mach, 2)}–${Num.trim(s.last().mach, 2)}); dashed: G7 reference"
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.9f
}
