package com.longrange.dragcurve.ui.calc

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.longrange.dragcurve.R
import com.longrange.dragcurve.engine.Engine
import com.longrange.dragcurve.engine.Row
import com.longrange.dragcurve.engine.clicks
import com.longrange.dragcurve.engine.milTo
import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.CardTarget
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import com.longrange.dragcurve.model.TargetCard
import kotlin.math.abs

object CardShots {
    fun shotFor(t: CardTarget, base: ShotInputs): ShotInputs = base.copy(
        distanceYd = t.distanceYd,
        lookAngleDeg = t.lookAngleDeg,
        windSpeed1Mph = t.windSpeedMph ?: base.windSpeed1Mph,
        windSpeed2Mph = 0.0,
        windAngleDeg = t.windAngleDeg ?: base.windAngleDeg,
        targetSpeedMph = t.targetSpeedMph,
    )
}

class TargetCardPanel(private val host: PanelHost, private val box: LinearLayout) {
    private val ctx = box.context
    private var selectedId: String? = null
    private var generation = 0
    private var shareText = ""

    fun save(out: Bundle) { selectedId?.let { out.putString("card_sel", it) } }
    fun restore(b: Bundle?) { selectedId = b?.getString("card_sel") }

    private fun cards(): List<TargetCard> {
        val r = host.rifle() ?: return emptyList(); val l = host.load() ?: return emptyList()
        return host.store.targetCards(r.id, l.id).sortedBy { it.name.lowercase() }
    }

    private fun current(): TargetCard? = cards().let { cs -> cs.firstOrNull { it.id == selectedId } ?: cs.firstOrNull() }

    fun hasCard() = current() != null
    fun shareText() = shareText

    fun render() {
        val r = host.rifle() ?: return; val l = host.load() ?: return
        val st = host.settings(); val shot = host.shot()
        box.removeAllViews()
        shareText = ""
        val form = Form(ctx, box, st)
        val cs = cards()
        val card = current()
        if (card == null) {
            form.header("Target Card")
            form.note("You have no target card for ${r.name} / ${l.name} yet.\n\nA target card is a list of your targets " +
                "(distance, slope, and optionally their own wind or movement). It shows the elevation and windage for every target at once, " +
                "using today's weather.")
            box.addView(button("Create target card") { newCard() })
            return
        }
        selectedId = card.id
        form.header("Card")
        form.group {
            if (cs.size > 1) choice("Card", cs.map { it.name }, cs.indexOf(card)) { i -> selectedId = cs[i].id; render() }
            else info("Card", card.name)
            info("Weather used", atmoText(shot, st))
        }
        val eu = Adjust.unitLabel(r.elevationUnit); val wu = Adjust.unitLabel(r.windageUnit)
        form.header("Targets ($eu / $wu)")
        val views = ArrayList<Pair<TextView, TextView>>()
        if (card.targets.isEmpty()) form.note("This card has no targets yet. Tap \"Add target\" below. Tap a target later to change or delete it.")
        else form.group {
            card.targets.forEachIndexed { i, t ->
                val (rowView, elev, wind) = targetRow(t, st)
                rowView.setOnClickListener { editTarget(card, i) }
                custom(rowView)
                views += elev to wind
            }
        }
        box.addView(button("Add target") { editTarget(card, -1) })
        form.note("Wind left blank on a target uses the main screen's wind (${speed(shot.windSpeed1Mph, st)} @ ${Num.trim(shot.windAngleDeg, 0)}°).")
        compute(card, r, views)
    }

    private fun button(text: String, onClick: () -> Unit) = MaterialButton(ctx).apply {
        this.text = text; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), 0)
        }
    }

    private fun speed(mph: Double, st: AppSettings) =
        Num.trim(Units.toDisplay(Quantity.SPEED, mph, st), 1) + " " + Units.label(Quantity.SPEED, st)

    private fun dist(yd: Double, st: AppSettings) =
        Num.trim(Units.toDisplay(Quantity.DISTANCE, yd, st), 0) + " " + Units.label(Quantity.DISTANCE, st)

    private fun atmoText(shot: ShotInputs, st: AppSettings): String {
        val a = shot.atmosphere
        return Num.trim(Units.toDisplay(Quantity.TEMPERATURE, a.temperatureF, st), 0) + Units.label(Quantity.TEMPERATURE, st) + " · " +
            Num.trim(Units.toDisplay(Quantity.PRESSURE, a.pressureInHg, st), 2) + " " + Units.label(Quantity.PRESSURE, st) +
            " · DA " + Num.trim(Units.toDisplay(Quantity.ALTITUDE, DensityAltitude.of(a), st), 0) + " " + Units.label(Quantity.ALTITUDE, st)
    }

    private fun describe(t: CardTarget, st: AppSettings): String {
        val parts = mutableListOf(dist(t.distanceYd, st))
        if (t.lookAngleDeg != 0.0) parts += Num.trim(t.lookAngleDeg, 1) + "°"
        if (t.windSpeedMph != null || t.windAngleDeg != null)
            parts += "wind " + (t.windSpeedMph?.let { speed(it, st) } ?: "current") + " @ " + (t.windAngleDeg?.let { Num.trim(it, 0) + "°" } ?: "current")
        if (t.targetSpeedMph != 0.0) parts += "moving " + speed(abs(t.targetSpeedMph), st) + if (t.targetSpeedMph > 0) " R" else " L"
        return parts.joinToString(" · ")
    }

    private fun targetRow(t: CardTarget, st: AppSettings): Triple<LinearLayout, TextView, TextView> {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(ctx).apply { text = t.name.ifBlank { dist(t.distanceYd, st) }; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD })
        left.addView(TextView(ctx).apply { text = describe(t, st); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.9f })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun value(color: Int) = TextView(ctx).apply {
            text = "…"; gravity = Gravity.END; setTextColor(ContextCompat.getColor(ctx, color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val elev = value(R.color.sol_elevation); val wind = value(R.color.sol_windage)
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(elev); right.addView(wind)
        row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(8) })
        return Triple(row, elev, wind)
    }

    private fun compute(card: TargetCard, r: Rifle, views: List<Pair<TextView, TextView>>) {
        val l = host.load() ?: return; val b = host.bullet() ?: return
        val shot = host.shot(); val st = host.settings()
        val gen = ++generation
        val targets = card.targets
        host.worker.execute {
            val rows: List<Row?> = targets.map { t ->
                runCatching {
                    val s = CardShots.shotFor(t, shot)
                    Engine.solve(r, l, b, s, maxRangeYd = t.distanceYd, stepYd = t.distanceYd.coerceAtLeast(1.0)).at(t.distanceYd)
                }.getOrNull()
            }
            host.runOnUi {
                if (gen != generation || !host.alive()) return@runOnUi
                val pm = st.usePlusMinus; val prec = st.outputPrecision
                val text = StringBuilder("${card.name}\n${r.name} / ${l.name}\n${atmoText(shot, st)}\n" +
                    "Elevation ${Adjust.unitLabel(r.elevationUnit)}, windage ${Adjust.unitLabel(r.windageUnit)}\n\n")
                rows.forEachIndexed { i, row ->
                    val (ev, wv) = views.getOrNull(i) ?: return@forEachIndexed
                    val t = targets[i]
                    if (row == null) { ev.text = "—"; wv.text = "—"; return@forEachIndexed }
                    val e = Adjust.dial(row.comeUpMil, r.elevationUnit, r.elevationCorrection)
                    val ec = clicks(row.comeUpMil, r.elevationUnit, r.elevationClick, r.elevationCorrection)
                    val w = Adjust.dial(row.windageMil, r.windageUnit, r.windageCorrection)
                    val wc = clicks(row.windageMil, r.windageUnit, r.windageClick, r.windageCorrection)
                    val eText = Adjust.text(e, Adjust.Axis.ELEVATION, prec, r.elevationClick, pm)
                    val wText = Adjust.text(w, Adjust.Axis.WINDAGE, prec, r.windageClick, pm)
                    ev.text = "$eText  (${Adjust.clicksText(ec, Adjust.Axis.ELEVATION, pm)})"
                    var windLine = "$wText  (${Adjust.clicksText(wc, Adjust.Axis.WINDAGE, pm)})"
                    if (t.targetSpeedMph != 0.0) windLine += "\nlead " + Adjust.text(milTo(r.windageUnit, row.leadMil), Adjust.Axis.WINDAGE, prec, r.windageClick, pm)
                    wv.text = windLine
                    text.append(t.name.ifBlank { dist(t.distanceYd, st) }).append(" (").append(describe(t, st)).append("): ")
                        .append("elev ").append(eText).append(" / wind ").append(wText)
                    if (t.targetSpeedMph != 0.0) text.append(" / lead ").append(Adjust.text(milTo(r.windageUnit, row.leadMil), Adjust.Axis.WINDAGE, prec, r.windageClick, pm))
                    text.append('\n')
                }
                shareText = text.toString()
            }
        }
    }

    fun newCard() {
        val r = host.rifle() ?: return; val l = host.load() ?: return
        askName("New target card", "${l.name} card") { name ->
            val c = host.store.saveTargetCard(TargetCard(id = "", name = name, rifleId = r.id, loadId = l.id))
            selectedId = c.id; render()
        }
    }

    fun renameCard() {
        val c = current() ?: return
        askName("Rename card", c.name) { name -> host.store.saveTargetCard(c.copy(name = name)); render() }
    }

    fun deleteCard() {
        val c = current() ?: return
        host.store.deleteTargetCard(c.id); selectedId = null; render()
        (ctx as? android.app.Activity)?.let { a ->
            UndoDelete.show(a, "Deleted \"${c.name}\"", com.longrange.dragcurve.model.Libraries(cards = listOf(c)), null,
                a.findViewById(R.id.bottomNav)) { selectedId = c.id; render() }
        }
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val (layout, edit) = field("Card name", initial, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, last = true)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0); addView(layout) }
        MaterialAlertDialogBuilder(ctx).setTitle(title).setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ -> edit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun field(label: String, value: String, type: Int, helper: String? = null, placeholder: String? = null,
                      last: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.isHintEnabled = true
        til.isExpandedHintEnabled = false
        til.hint = label
        helper?.let { til.helperText = it }
        placeholder?.let { til.placeholderText = it }
        val e = TextInputEditText(til.context).apply {
            setText(value)
            inputType = type
            maxLines = 1
            isSingleLine = true
            imeOptions = if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            setSelectAllOnFocus(true)
        }
        til.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        til.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(8)
        }
        return til to e
    }

    private fun editTarget(card: TargetCard, index: Int) {
        val st = host.settings()
        val t = card.targets.getOrNull(index)
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val signed = num or InputType.TYPE_NUMBER_FLAG_SIGNED
        fun disp(q: Quantity, v: Double?, d: Int) = v?.let { Num.trim(Units.toDisplay(q, it, st), d) } ?: ""
        val name = field("Name", t?.name ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, placeholder = "e.g. Steel 1")
        val distance = field("Distance (${Units.label(Quantity.DISTANCE, st)})", disp(Quantity.DISTANCE, t?.distanceYd, 0), num)
        val look = field("Look angle (°)", t?.lookAngleDeg?.let { Num.trim(it, 1) } ?: "0", signed, helper = "+ uphill, − downhill")
        val wind = field("Wind speed (${Units.label(Quantity.SPEED, st)})", disp(Quantity.SPEED, t?.windSpeedMph, 1), num,
            helper = "blank = current", placeholder = "current")
        val windAngle = field("Wind angle (°)", t?.windAngleDeg?.let { Num.trim(it, 0) } ?: "", num,
            helper = "blank = current", placeholder = "current")
        val moving = field("Target speed (${Units.label(Quantity.SPEED, st)}, + right)", disp(Quantity.SPEED, t?.targetSpeedMph, 1).ifEmpty { "0" }, signed,
            helper = "+ moving right, − moving left, 0 = stationary", last = true)
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
            listOf(name, distance, look, wind, windAngle, moving).forEach { addView(it.first) }
        }
        val scroll = android.widget.ScrollView(ctx).apply { isFillViewport = true; addView(wrap) }
        val b = MaterialAlertDialogBuilder(ctx).setTitle(if (t == null) "Add target" else "Edit target").setView(scroll)
            .setPositiveButton(R.string.calc_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (t != null) b.setNeutralButton(R.string.calc_delete) { _, _ ->
            MaterialAlertDialogBuilder(ctx).setTitle("Delete this target?")
                .setMessage("\"${t.name.ifBlank { dist(t.distanceYd, host.settings()) }}\" will be removed from the card.")
                .setPositiveButton(R.string.calc_delete) { _, _ ->
                    host.store.saveTargetCard(card.copy(targets = card.targets.filterIndexed { i, _ -> i != index })); render()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val d = Num.parse(distance.second.text)?.let { Units.fromDisplay(Quantity.DISTANCE, it, st) }
                if (d == null || d <= 0 || d > 5000) { distance.first.error = "Enter a distance"; return@setOnClickListener }
                val la = Num.parse(look.second.text) ?: 0.0
                if (abs(la) >= 90) { look.first.error = "Between −90 and 90"; return@setOnClickListener }
                val ws = Num.parse(wind.second.text)?.let { Units.fromDisplay(Quantity.SPEED, it, st) }?.takeIf { it >= 0 }
                val wa = Num.parse(windAngle.second.text)?.let { WindText.normalize(it) }
                val ts = Num.parse(moving.second.text)?.let { Units.fromDisplay(Quantity.SPEED, it, st) } ?: 0.0
                val nt = CardTarget(name = name.second.text?.toString()?.trim().orEmpty(), distanceYd = d, lookAngleDeg = la,
                    windSpeedMph = ws, windAngleDeg = wa, targetSpeedMph = ts)
                val list = card.targets.toMutableList()
                if (index in list.indices) list[index] = nt else list += nt
                host.store.saveTargetCard(card.copy(targets = list.sortedBy { it.distanceYd }))
                dlg.dismiss(); render()
            }
        }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
    }
}
