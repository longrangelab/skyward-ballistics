package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.AppSettings
import com.google.android.material.R as MR

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun View.themeColor(attr: Int): Int = MaterialColors.getColor(this, attr)

object Type {
    const val HEADER = 15f
    const val LABEL = 18f
    const val SUB = 15f
    const val VALUE = 18f
    const val INPUT = 20f
    const val UNIT = 16f
    const val NOTE = 16f
}

class Form(val ctx: Context, private val root: LinearLayout, var settings: AppSettings) {
    var contentWidthPx: Int = ctx.resources.displayMetrics.widthPixels

    fun clear() = root.removeAllViews()

    fun header(title: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(20), ctx.dp(12), ctx.dp(8))
        }
        row.addView(View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, R.color.sky_cyan)) },
            LinearLayout.LayoutParams(ctx.dp(5), ctx.dp(20)).apply { marginEnd = ctx.dp(8) })
        val tv = TextView(ctx).apply {
            text = title
            isAllCaps = true
            letterSpacing = 0.06f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.HEADER)
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        tv.setTextColor(tv.themeColor(MR.attr.colorOnSurface))
        if (actionLabel != null && action != null) {
            row.addView(MaterialButton(ctx, null, MR.attr.borderlessButtonStyle).apply {
                text = actionLabel
                setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                setOnClickListener { action() }
            })
        }
    }

    fun note(text: String): TextView {
        val tv = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.NOTE)
            setLineSpacing(0f, 1.1f)
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(4))
        }
        root.addView(tv)
        tv.setTextColor(tv.themeColor(MR.attr.colorOnSurfaceVariant))
        return tv
    }

    fun group(block: Group.() -> Unit): Group {
        val card = MaterialCardView(ctx).apply {
            radius = ctx.dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = ctx.dp(1)
        }
        card.setCardBackgroundColor(card.themeColor(MR.attr.colorSurfaceContainerHigh))
        card.strokeColor = card.themeColor(MR.attr.colorOutlineVariant)
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(12), 0, ctx.dp(12), 0)
        })
        return Group(this, inner).apply(block)
    }

    fun foldable(title: String, summary: () -> String, expanded: Boolean, onToggle: (Boolean) -> Unit, block: Group.() -> Unit) {
        root.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dp(10)))
        group {
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val reveal = RevealLayout(ctx)
            reveal.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            reveal.fraction = if (expanded) 1f else 0f
            reveal.visibility = if (expanded) View.VISIBLE else View.GONE
            var open = expanded
            val header = foldHeader(title, summary(), expanded)
            header.row.setOnClickListener {
                open = !open
                onToggle(open)
                header.setOpen(open, summary())
                Motion.reveal(reveal, open) { f -> header.progress(f) }
            }
            box.addView(reveal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            Group(this@Form, body, dividerFirst = true).block()
        }
    }

    fun addView(v: View) = root.addView(v)

    class Group(private val form: Form, val box: LinearLayout, dividerFirst: Boolean = false) {
        private val ctx get() = form.ctx
        private var count = if (dividerFirst) 1 else 0

        private fun newRow(vertical: Boolean = false): LinearLayout {
            if (count++ > 0) {
                val div = View(ctx)
                box.addView(div, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)).apply {
                    marginStart = ctx.dp(16); marginEnd = ctx.dp(16)
                })
                div.setBackgroundColor(div.themeColor(MR.attr.colorOutlineVariant))
            }
            val row = LinearLayout(ctx).apply {
                orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(60)
                setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
            }
            box.addView(row)
            return row
        }

        private fun line(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        private fun label(row: LinearLayout, text: String, sub: String? = null, bold: Boolean = false): TextView {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val tv = TextView(ctx).apply {
                this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }
            col.addView(tv)
            tv.setTextColor(tv.themeColor(MR.attr.colorOnSurface))
            if (sub != null) {
                val st = TextView(ctx).apply {
                    this.text = sub; setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.SUB)
                    tag = "sub"
                }
                col.addView(st)
                st.setTextColor(st.themeColor(MR.attr.colorOnSurfaceVariant))
            }
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return tv
        }

        private fun valueText(row: LinearLayout, text: String): TextView {
            val tv = TextView(ctx).apply {
                this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.VALUE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END; maxLines = 4
                maxWidth = (form.contentWidthPx * 0.56f).toInt()
            }
            row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(12)
            })
            tv.setTextColor(tv.themeColor(MR.attr.colorOnSurface))
            return tv
        }

        private fun numberEdit(signed: Boolean, hint: String, big: Boolean = false): EditText = EditText(ctx).apply {
            isSaveEnabled = false
            setBackgroundResource(R.drawable.calc_input_bg)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 30f else Type.INPUT)
            typeface = Typeface.DEFAULT_BOLD
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
            this.hint = hint
            minWidth = ctx.dp(if (big) 120 else 84)
            minimumWidth = minWidth
            minHeight = ctx.dp(if (big) 60 else 48)
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
            maxLines = 1
            setTextColor(themeColor(MR.attr.colorOnSurface))
        }

        private fun unitText(text: String, big: Boolean = false): TextView = TextView(ctx).apply {
            this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 18f else Type.UNIT)
            minWidth = ctx.dp(30)
            setPadding(ctx.dp(6), 0, 0, 0)
            setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
        }

        private fun pill(text: String, onClick: () -> Unit): MaterialButton = MaterialButton(ctx).apply {
            this.text = text
            backgroundTintList = ColorStateList.valueOf(themeColor(MR.attr.colorSecondaryContainer))
            setTextColor(themeColor(MR.attr.colorOnSecondaryContainer))
            strokeWidth = 0
            minWidth = 0; minimumWidth = 0; minHeight = ctx.dp(48); minimumHeight = ctx.dp(48)
            insetTop = 0; insetBottom = 0
            setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(4))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onClick() }
        }

        private fun wire(edit: EditText, nr: NumberRow, q: Quantity, onChange: (Double?) -> Unit) {
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    if (nr.muted) return
                    onChange(Num.parse(e)?.let { Units.fromDisplay(q, it, form.settings) })
                }
            })
            edit.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { hideKeyboard(v); v.clearFocus(); true } else false
            }
        }

        fun number(
            label: String, q: Quantity, value: Double?, onChange: (Double?) -> Unit,
            signed: Boolean = false, decimals: Int? = null, hint: String = "", sub: String? = null,
            action: Pair<String, () -> Unit>? = null, widestText: String? = null,
        ): NumberRow {
            val row = newRow(vertical = action != null)
            val top = if (action != null) line().also { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) } else row
            val lbl = label(top, label, sub)
            val s = form.settings
            val dec = decimals ?: Units.decimals(q, s)
            val edit = numberEdit(signed, hint)
            val nr = NumberRow(q, s, dec, edit, lbl)
            nr.set(value)
            if (signed) {
                top.addView(MaterialButton(ctx, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "+/−"; minWidth = 0; minimumWidth = 0; minHeight = ctx.dp(48); minimumHeight = ctx.dp(48)
                    insetTop = 0; insetBottom = 0
                    setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    contentDescription = "Change sign of $label"
                    setOnClickListener {
                        val t = edit.text.toString().trim()
                        edit.setText(if (t.startsWith("-")) t.substring(1) else if (t.isEmpty()) "-" else "-$t")
                        edit.setSelection(edit.text.length)
                    }
                }, LinearLayout.LayoutParams(ctx.dp(60), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = ctx.dp(8); marginEnd = ctx.dp(8)
                })
            }
            val editWidth = if (widestText == null) ViewGroup.LayoutParams.WRAP_CONTENT
                else maxOf(edit.minWidth, Math.ceil(edit.paint.measureText(widestText).toDouble()).toInt() + edit.paddingLeft + edit.paddingRight + ctx.dp(10))
            top.addView(edit, LinearLayout.LayoutParams(editWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (!signed) marginStart = ctx.dp(12)
            })
            val unit = Units.label(q, s)
            if (unit.isNotEmpty()) top.addView(unitText(unit))
            if (action != null) {
                val under = line().apply { gravity = Gravity.END }
                under.addView(pill(action.first, action.second))
                row.addView(under, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(8)
                })
            }
            wire(edit, nr, q, onChange)
            row.setOnClickListener { edit.requestFocus(); showKeyboard(edit) }
            return nr
        }

        fun stepper(
            label: String, q: Quantity, value: Double?, onChange: (Double?) -> Unit, onStep: (Int) -> Unit,
            decimals: Int? = null, sub: String? = null,
        ): NumberRow {
            val row = newRow(vertical = true)
            val top = line()
            val lbl = label(top, label, sub, bold = true)
            row.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val s = form.settings
            val edit = numberEdit(false, "", big = true)
            val nr = NumberRow(q, s, decimals ?: Units.decimals(q, s), edit, lbl)
            nr.set(value)
            val controls = line().apply { gravity = Gravity.CENTER }
            controls.addView(stepButton(ctx, "−", "Decrease $label") { edit.clearFocus(); onStep(-1) })
            val mid = line().apply { gravity = Gravity.CENTER }
            mid.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val unit = Units.label(q, s)
            if (unit.isNotEmpty()) mid.addView(unitText(unit, big = true))
            controls.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            controls.addView(stepButton(ctx, "+", "Increase $label") { edit.clearFocus(); onStep(1) })
            row.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(8)
            })
            wire(edit, nr, q, onChange)
            return nr
        }

        fun text(label: String?, value: String, onChange: (String) -> Unit, hint: String = "", multiline: Boolean = false): EditText {
            val row = newRow(vertical = true)
            if (label != null) {
                val l = line(); label(l, label)
                row.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val edit = EditText(ctx).apply {
                isSaveEnabled = false
                setBackgroundResource(R.drawable.calc_input_bg)
                setText(value)
                this.hint = hint
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.INPUT)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                if (!multiline) maxLines = 1
                minHeight = ctx.dp(52)
                setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                setTextColor(themeColor(MR.attr.colorOnSurface))
            }
            row.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (label != null) topMargin = ctx.dp(6)
            })
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) = onChange(e?.toString().orEmpty())
            })
            row.setOnClickListener { edit.requestFocus(); showKeyboard(edit) }
            return edit
        }

        fun switch(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit): MaterialSwitch {
            val row = newRow()
            label(row, label, sub)
            val sw = MaterialSwitch(ctx).apply { isChecked = checked; isSaveEnabled = false; minimumHeight = ctx.dp(48) }
            row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(12)
            })
            sw.setOnCheckedChangeListener { _, c -> onChange(c) }
            row.setOnClickListener { sw.toggle() }
            return sw
        }

        private fun arrow(row: LinearLayout, down: Boolean) {
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_calc_chevron)
                if (down) rotation = 90f
                imageTintList = ColorStateList.valueOf(themeColor(MR.attr.colorOnSurfaceVariant))
            }, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply { marginStart = ctx.dp(4) })
        }

        fun choice(label: String, options: List<String>, selected: Int, sub: String? = null, onPick: (Int) -> Unit): TextView {
            val row = newRow()
            label(row, label, sub)
            val tv = valueText(row, options.getOrNull(selected) ?: "—")
            arrow(row, down = true)
            var current = selected
            row.setOnClickListener {
                MaterialAlertDialogBuilder(ctx).setTitle(label)
                    .setSingleChoiceItems(options.toTypedArray(), current) { d, which ->
                        d.dismiss(); current = which; tv.text = options[which]; onPick(which)
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            return tv
        }

        fun nav(label: String, value: String, sub: String? = null, onClick: () -> Unit): TextView {
            val row = newRow()
            label(row, label, sub)
            val tv = valueText(row, value)
            arrow(row, down = false)
            row.setOnClickListener { onClick() }
            return tv
        }

        fun info(label: String, value: String, sub: String? = null): TextView {
            val row = newRow()
            label(row, label, sub)
            return valueText(row, value).apply { typeface = Typeface.DEFAULT }
        }

        fun custom(v: View) {
            val row = newRow()
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        class FoldHeader(val row: LinearLayout, private val title: String, private val summaryView: TextView,
                         private val summaryBox: RevealLayout, private val hint: TextView, private val chevron: ImageView) {
            fun setOpen(open: Boolean, summary: String) {
                hint.text = if (open) "Close" else "Open"
                if (!open) summaryView.text = summary
                row.contentDescription = "$title. ${if (open) "" else "$summary. "}${if (open) "Tap to close" else "Tap to open"}"
            }

            fun progress(f: Float) {
                chevron.rotation = 90f + 180f * f
                summaryBox.fraction = 1f - f
                summaryBox.alpha = 1f - f
                summaryBox.visibility = if (f >= 1f) View.GONE else View.VISIBLE
            }
        }

        fun foldHeader(title: String, summary: String, expanded: Boolean): FoldHeader {
            val row = newRow()
            row.minimumHeight = ctx.dp(68)
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val tv = TextView(ctx).apply {
                text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL); typeface = Typeface.DEFAULT_BOLD
            }
            col.addView(tv)
            tv.setTextColor(tv.themeColor(MR.attr.colorOnSurface))
            val summaryView = TextView(ctx).apply { text = summary; setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.SUB); tag = "sub" }
            val summaryBox = RevealLayout(ctx)
            summaryBox.addView(summaryView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(summaryBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            summaryView.setTextColor(summaryView.themeColor(MR.attr.colorOnSurfaceVariant))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val hint = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD
                setPadding(ctx.dp(8), 0, 0, 0)
                gravity = Gravity.END
                minWidth = Math.ceil(maxOf(paint.measureText("Open"), paint.measureText("Close")).toDouble()).toInt() + ctx.dp(8)
            }
            row.addView(hint)
            hint.setTextColor(hint.themeColor(MR.attr.colorSecondary))
            val chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_calc_chevron)
                imageTintList = ColorStateList.valueOf(themeColor(MR.attr.colorSecondary))
            }
            row.addView(chevron, LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)))
            val tvBg = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tvBg, true)) row.setBackgroundResource(tvBg.resourceId)
            return FoldHeader(row, title, summaryView, summaryBox, hint, chevron).apply {
                setOpen(expanded, summary)
                progress(if (expanded) 1f else 0f)
            }
        }

        fun segmented(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): List<MaterialButton> {
            val row = newRow()
            label(row, label)
            val buttons = options.mapIndexed { i, o ->
                MaterialButton(ctx, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = o; isCheckable = true; isChecked = i == selected
                    minWidth = ctx.dp(64); minimumWidth = ctx.dp(64)
                    setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
                }
            }
            buttons.forEachIndexed { i, b ->
                b.setOnClickListener { buttons.forEachIndexed { j, o -> o.isChecked = j == i }; onPick(i) }
                row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = ctx.dp(6)
                })
            }
            return buttons
        }
    }

    class NumberRow(private val q: Quantity, private val s: AppSettings, private val decimals: Int,
                    val edit: EditText, val label: TextView) {
        var muted = false
        fun set(model: Double?) {
            muted = true
            edit.setText(model?.let { Num.trim(Units.toDisplay(q, it, s), decimals) } ?: "")
            muted = false
        }
        fun setEnabled(on: Boolean) {
            edit.isEnabled = on
            (edit.parent as? View)?.alpha = if (on) 1f else 0.45f
        }
        fun subView(): TextView? = (label.parent as? ViewGroup)?.findViewWithTag("sub")
    }

    companion object {
        fun showKeyboard(v: View) {
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            v.post { imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
        }
        fun hideKeyboard(v: View) {
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        }

        fun stepButton(ctx: Context, glyph: String, description: String, onClick: () -> Unit): MaterialButton =
            MaterialButton(ctx, null, MR.attr.materialButtonOutlinedStyle).apply {
                text = glyph
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.DEFAULT_BOLD
                contentDescription = description
                minWidth = ctx.dp(60); minimumWidth = ctx.dp(60); minHeight = ctx.dp(60); minimumHeight = ctx.dp(60)
                insetTop = 0; insetBottom = 0
                cornerRadius = ctx.dp(30)
                strokeWidth = ctx.dp(2)
                strokeColor = ColorStateList.valueOf(themeColor(MR.attr.colorOnSurface))
                setTextColor(themeColor(MR.attr.colorOnSurface))
                setPadding(0, 0, 0, 0)
                setOnClickListener { onClick() }
                Motion.pressFeedback(this)
            }
    }
}
