package com.longrange.dragcurve.ui.calc

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.engine.Engine
import com.longrange.dragcurve.engine.Truing
import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import java.util.concurrent.Executor
import kotlin.math.abs

interface PanelHost {
    val store: ProfileStore
    val worker: Executor
    fun rifle(): Rifle?
    fun load(): Load?
    fun bullet(): Bullet?
    fun shot(): ShotInputs
    fun settings(): AppSettings
    fun alive(): Boolean
    fun onLoadChanged(l: Load)
    fun runOnUi(r: () -> Unit)
}

object ImpactCheck {
    const val MIN_MIL = -5.0
    const val MAX_MIL = 200.0

    fun maxRangeYd(tableMaxYd: Double) = maxOf(tableMaxYd, 3000.0)

    fun distanceOk(yd: Double?, maxYd: Double) = yd != null && yd > 0 && yd <= maxYd
    fun actualOk(mil: Double?) = mil != null && mil >= MIN_MIL && mil <= MAX_MIL
}

class CalibratePanel(private val host: PanelHost, private val box: LinearLayout) {
    private val ctx = box.context
    private var velocityMode = true
    private val dist = arrayOfNulls<Double>(ROWS)
    private val actual = arrayOfNulls<Double>(ROWS)
    private var result: Truing.Result? = null
    private var resultForVelocity = true
    private var generation = 0
    private val predictedViews = arrayOfNulls<TextView>(ROWS)
    private val distFields = arrayOfNulls<EditText>(ROWS)
    private val actualFields = arrayOfNulls<EditText>(ROWS)
    private var skipped: List<String> = emptyList()
    private var hintView: TextView? = null

    fun save(out: Bundle) {
        out.putBoolean("cal_velocity", velocityMode)
        out.putString("cal_dist", dist.joinToString(";") { it?.toString() ?: "" })
        out.putString("cal_actual", actual.joinToString(";") { it?.toString() ?: "" })
    }

    fun restore(b: Bundle?) {
        b ?: return
        velocityMode = b.getBoolean("cal_velocity", true)
        b.getString("cal_dist")?.split(';')?.forEachIndexed { i, s -> if (i < ROWS) dist[i] = s.toDoubleOrNull() }
        b.getString("cal_actual")?.split(';')?.forEachIndexed { i, s -> if (i < ROWS) actual[i] = s.toDoubleOrNull() }
    }

    fun render() {
        val r = host.rifle() ?: return; val l = host.load() ?: return
        val st = host.settings(); val shot = host.shot()
        box.removeAllViews()
        val form = Form(ctx, box, st)
        val eu = Adjust.unitLabel(r.elevationUnit)

        form.header("Current conditions")
        form.group {
            info("Uphill / downhill angle", Num.trim(shot.lookAngleDeg, 1) + "°")
            info("Temperature", Num.trim(Units.toDisplay(Quantity.TEMPERATURE, shot.atmosphere.temperatureF, st), 1) + " " +
                Units.label(Quantity.TEMPERATURE, st))
            info("Wind", Num.trim(Units.toDisplay(Quantity.SPEED, shot.windSpeed1Mph, st), 1) + " " + Units.label(Quantity.SPEED, st) +
                " @ " + Num.trim(shot.windAngleDeg, 0) + "°")
            info("Muzzle Velocity", vel(Engine.muzzleVelocity(l, shot.atmosphere.temperatureF), st),
                sub = if (l.velocityPerDegFps != 0.0) "at this temperature" else null)
            info("Drag Scale Factor", Num.trim(l.dragScaleFactor, 4))
        }

        form.header("Match the app to your real hits")
        form.group {
            segmented("Adjust", listOf("Velocity", "Drag"), if (velocityMode) 0 else 1) { i ->
                velocityMode = i == 0; result = null; render()
            }
        }
        form.note("Shoot at known distances, then type the distance and the elevation you really had to dial to hit. " +
            "Adjust Velocity for most shots (bullet still well above the speed of sound); adjust Drag for very long shots near or past it. " +
            "Use the same unit as your scope ($eu).")
        hintView = form.note("")

        form.header("Your hits")
        form.group {
            custom(headerRow("Distance (${Units.label(Quantity.DISTANCE, st)})", "Dialed ($eu)", "App says"))
            for (i in 0 until ROWS) custom(impactRow(i, st))
        }
        val go = MaterialButton(ctx).apply { text = "Calibrate"; setOnClickListener { calibrate() } }
        box.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), 0)
        })

        result?.let { res -> renderResult(form, res, r, l, st, shot) }
        refreshPredicted()
    }

    private fun vel(fps: Double, st: AppSettings) =
        Num.trim(Units.toDisplay(Quantity.VELOCITY, fps, st), 0) + " " + Units.label(Quantity.VELOCITY, st)

    private fun headerRow(a: String, b: String, c: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(a, b, c).forEach { t ->
            addView(TextView(ctx).apply { text = t; alpha = 0.9f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun maxYd() = ImpactCheck.maxRangeYd(host.settings().tableMaxYd)

    private fun distOk(i: Int) = ImpactCheck.distanceOk(dist[i], maxYd())

    private fun actualMil(i: Int, r: Rifle): Double? = actual[i]?.let { Calibration.dialToMil(it, r.elevationUnit, r.elevationCorrection) }

    private fun distError(i: Int): String? {
        val d = dist[i] ?: return null
        if (ImpactCheck.distanceOk(d, maxYd())) return null
        val st = host.settings()
        return if (d <= 0) "Above 0" else "Max " + Num.trim(Units.toDisplay(Quantity.DISTANCE, maxYd(), st), 0) + " " + Units.label(Quantity.DISTANCE, st)
    }

    private fun actualError(i: Int): String? {
        val r = host.rifle() ?: return null
        val mil = actualMil(i, r) ?: return null
        if (ImpactCheck.actualOk(mil)) return null
        val lo = Adjust.dial(ImpactCheck.MIN_MIL, r.elevationUnit, r.elevationCorrection)
        val hi = Adjust.dial(ImpactCheck.MAX_MIL, r.elevationUnit, r.elevationCorrection)
        return "${Num.trim(lo, 0)} to ${Num.trim(hi, 0)} ${Adjust.unitLabel(r.elevationUnit)}"
    }

    private fun next(field: EditText): EditText? {
        val order = (0 until ROWS).flatMap { listOf(distFields[it], actualFields[it]) }
        val k = order.indexOf(field)
        return if (k >= 0 && k + 1 < order.size) order[k + 1] else null
    }

    private fun impactRow(i: Int, st: AppSettings): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun field(value: Double?, decimals: Int, signed: Boolean, last: Boolean, onChange: (EditText, Double?) -> Unit): EditText {
            val e = EditText(ctx)
            e.isSaveEnabled = false
            e.gravity = Gravity.CENTER
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            e.imeOptions = if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            e.setText(value?.let { Num.trim(it, decimals) } ?: "")
            e.setSelectAllOnFocus(true)
            e.maxLines = 1
            e.setBackgroundResource(com.longrange.dragcurve.R.drawable.calc_input_bg)
            e.minHeight = ctx.dp(52)
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(x: Editable?) = onChange(e, Num.parse(x))
            })
            e.setOnEditorActionListener { v, action, _ ->
                when (action) {
                    EditorInfo.IME_ACTION_NEXT -> { next(v as EditText)?.let { it.requestFocus(); it.selectAll() }; true }
                    EditorInfo.IME_ACTION_DONE -> { Form.hideKeyboard(v); v.clearFocus(); true }
                    else -> false
                }
            }
            return e
        }
        val d = field(dist[i]?.let { Units.toDisplay(Quantity.DISTANCE, it, st) }, 0, false, last = false) { e, v ->
            dist[i] = v?.let { Units.fromDisplay(Quantity.DISTANCE, it, st) }
            e.error = distError(i)
            result = null; refreshPredicted()
        }
        val a = field(actual[i], 2, true, last = i == ROWS - 1) { e, v -> actual[i] = v; e.error = actualError(i); result = null }
        distFields[i] = d; actualFields[i] = a
        d.error = distError(i); a.error = actualError(i)
        row.addView(d, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(8); topMargin = ctx.dp(2); bottomMargin = ctx.dp(2) })
        row.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pred = TextView(ctx).apply { text = "—"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); alpha = 0.95f }
        predictedViews[i] = pred
        row.addView(pred, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun refreshPredicted() {
        val r = host.rifle() ?: return; val l = host.load() ?: return; val b = host.bullet() ?: return
        val shot = host.shot(); val st = host.settings()
        val ranges = (0 until ROWS).map { i -> dist[i]?.takeIf { distOk(i) } }
        val valid = ranges.filterNotNull()
        val gen = ++generation
        if (valid.isEmpty()) { predictedViews.forEach { it?.text = "—" }; hintView?.text = ""; return }
        host.worker.execute {
            val res = runCatching {
                val max = valid.max()
                Engine.solve(r, l, b, shot.copy(distanceYd = max), maxRangeYd = max, stepYd = max, extraRangesYd = valid)
            }.getOrNull()
            host.runOnUi {
                if (gen != generation || !host.alive() || res == null) return@runOnUi
                val machs = mutableListOf<Double>()
                ranges.forEachIndexed { i, d ->
                    val row = d?.let { x -> res.rows.minByOrNull { abs(it.rangeYd - x) } }
                    if (row != null) machs += row.mach
                    predictedViews[i]?.text = row?.let {
                        Adjust.text(Adjust.dial(it.comeUpMil, r.elevationUnit, r.elevationCorrection), Adjust.Axis.ELEVATION,
                            "hundredth", r.elevationClick, st.usePlusMinus)
                    } ?: "—"
                }
                hintView?.text = Calibration.hint(velocityMode, machs) ?: ""
            }
        }
    }

    private fun impacts(r: Rifle): List<Truing.Impact> = (0 until ROWS).mapNotNull { i ->
        val d = dist[i]; val m = actualMil(i, r)
        if (d != null && distOk(i) && m != null && ImpactCheck.actualOk(m)) Truing.Impact(d, m) else null
    }

    private fun problems(): List<String> = (0 until ROWS).mapNotNull { i ->
        val hasD = dist[i] != null; val hasA = actual[i] != null
        val why = when {
            !hasD && !hasA -> null
            !hasD -> "no distance"
            !hasA -> "no actual elevation"
            distError(i) != null -> "distance out of range (${distError(i)})"
            actualError(i) != null -> "elevation out of range (${actualError(i)})"
            else -> null
        }
        why?.let { "Row ${i + 1}: $it" }
    }

    private fun calibrate() {
        val r = host.rifle() ?: return; val l = host.load() ?: return; val b = host.bullet() ?: return
        (box.findFocus())?.let { Form.hideKeyboard(it) }
        val imp = impacts(r)
        val probs = problems()
        if (imp.isEmpty()) {
            val msg = "Enter at least one distance with the elevation that hit." +
                if (probs.isNotEmpty()) "\n\n" + probs.joinToString("\n") else ""
            MaterialAlertDialogBuilder(ctx).setTitle("Calibrate").setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        skipped = probs
        val shot = host.shot(); val mode = velocityMode
        host.worker.execute {
            val res = runCatching {
                if (mode) Truing.muzzleVelocity(r, l, b, shot, imp) else Truing.dragScale(r, l, b, shot, imp)
            }
            host.runOnUi {
                if (!host.alive()) return@runOnUi
                res.onSuccess { result = it; resultForVelocity = mode; render() }
                    .onFailure { e ->
                        MaterialAlertDialogBuilder(ctx).setTitle("Calibrate").setMessage(e.message ?: e.toString())
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
            }
        }
    }

    private fun renderResult(form: Form, res: Truing.Result, r: Rifle, l: Load, st: AppSettings, shot: ShotInputs) {
        val eu = r.elevationUnit
        val toDial = { mil: Double -> Adjust.dial(mil, eu, r.elevationCorrection) }
        val t = shot.atmosphere.temperatureF
        form.header("Result")
        form.group {
            if (resultForVelocity) info("New Muzzle Velocity", vel(res.value, st),
                sub = "at ${Num.trim(Units.toDisplay(Quantity.TEMPERATURE, t, st), 0)}${Units.label(Quantity.TEMPERATURE, st)} (was ${vel(Engine.muzzleVelocity(l, t), st)})")
            else info("New Drag Scale Factor", Num.fixed(res.value, 4), sub = "was ${Num.trim(l.dragScaleFactor, 4)}")
            if (skipped.isNotEmpty()) info("Skipped", skipped.joinToString("\n"))
            info("Typical remaining error", Num.fixed(toDial(res.rmsBeforeMil), 2) + " → " + Num.fixed(toDial(res.rmsAfterMil), 2) + " " + Adjust.unitLabel(eu))
            val imp = impacts(r)
            imp.forEachIndexed { i, im ->
                info(Num.trim(Units.toDisplay(Quantity.DISTANCE, im.rangeYd, st), 0) + " " + Units.label(Quantity.DISTANCE, st),
                    "actual ${Num.fixed(toDial(im.actualComeUpMil), 2)} · before ${Num.fixed(toDial(res.predictedBefore[i]), 2)} · after ${Num.fixed(toDial(res.predictedAfter[i]), 2)}")
            }
        }
        val apply = MaterialButton(ctx).apply { text = "Apply to ammo"; setOnClickListener { confirmApply(res, l, shot) } }
        box.addView(apply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(16), ctx.dp(12), ctx.dp(16), 0)
        })
    }

    private fun confirmApply(res: Truing.Result, l: Load, shot: ShotInputs) {
        val st = host.settings()
        val t = shot.atmosphere.temperatureF
        val updated = if (resultForVelocity) l.copy(muzzleVelocityFps = Calibration.baseMvFor(l, res.value, t))
            else l.copy(dragScaleFactor = res.value)
        val what = if (resultForVelocity) {
            val base = if (l.velocityPerDegFps != 0.0)
                " (${vel(updated.muzzleVelocityFps, st)} at the powder temperature ${Num.trim(Units.toDisplay(Quantity.TEMPERATURE, l.powderTempF, st), 0)}${Units.label(Quantity.TEMPERATURE, st)})" else ""
            "Set the muzzle velocity of \"${l.name}\" to ${vel(res.value, st)} at this temperature$base?"
        } else "Set the drag scale factor of \"${l.name}\" to ${Num.fixed(res.value, 4)}?"
        val emp = if (EmpiricalHook.isEmpirical(l.externalRef) && resultForVelocity)
            "\n\nThis ammo is linked to Empirical Precision: a later refresh may replace the muzzle velocity with Empirical's." else ""
        MaterialAlertDialogBuilder(ctx).setTitle("Apply to ammo").setMessage(what + emp)
            .setPositiveButton("Apply") { _, _ ->
                host.store.saveLoad(updated)
                val saved = host.store.loads(updated.rifleId).firstOrNull { it.id == updated.id } ?: updated
                result = null
                host.onLoadChanged(saved)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    companion object { const val ROWS = 5 }
}
