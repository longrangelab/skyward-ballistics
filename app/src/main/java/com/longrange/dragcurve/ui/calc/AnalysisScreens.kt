package com.longrange.dragcurve.ui.calc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.R
import com.longrange.dragcurve.analysis.HitSim
import com.longrange.dragcurve.analysis.TnfCompare
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.ui.BarView
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.google.android.material.R as MR

object AnalysisPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("calc_ui", Context.MODE_PRIVATE)

    fun tnfEntries(c: Context): List<TnfCompare.Entry> = TnfCompare.fromJson(p(c).getString("tnfCompare", null))
    fun setTnfEntries(c: Context, e: List<TnfCompare.Entry>) = p(c).edit().putString("tnfCompare", TnfCompare.toJson(e.take(TnfCompare.MAX_ENTRIES))).apply()
    fun tnfReference(c: Context): Int = p(c).getInt("tnfCompareRef", -1)
    fun setTnfReference(c: Context, i: Int) = p(c).edit().putInt("tnfCompareRef", i).apply()

    fun addTnf(c: Context, e: TnfCompare.Entry): Boolean {
        val cur = tnfEntries(c)
        if (cur.size >= TnfCompare.MAX_ENTRIES) return false
        setTnfEntries(c, cur + e)
        return true
    }

    fun hitState(c: Context): HitState = HitState.fromJson(p(c).getString("hitSim", null))
    fun setHitState(c: Context, s: HitState) = p(c).edit().putString("hitSim", s.toJson()).apply()
}

data class HitState(
    val useSaved: Boolean = true,
    val loadId: String? = null,
    val diameterIn: Double = 0.264,
    val weightGr: Double = 140.0,
    val bc: Double = 0.310,
    val g1: Boolean = false,
    val mvFps: Double = 2750.0,
    val sightHeightIn: Double = 1.75,
    val zeroYd: Double = 100.0,
    val twistIn: Double = 8.0,
    val inputs: HitSim.Inputs = HitSim.Inputs(),
) {
    fun toJson(): String = JSONObject().apply {
        put("useSaved", useSaved); loadId?.let { put("loadId", it) }
        put("diameterIn", diameterIn); put("weightGr", weightGr); put("bc", bc); put("g1", g1); put("mvFps", mvFps)
        put("sightHeightIn", sightHeightIn); put("zeroYd", zeroYd); put("twistIn", twistIn)
        val i = inputs
        put("rangeYd", i.rangeYd); put("targetWidthIn", i.targetWidthIn); put("targetHeightIn", i.targetHeightIn); put("round", i.round)
        put("windMph", i.windMph); put("windFromDeg", i.windFromDeg); put("mvSdFps", i.mvSdFps); put("bcSdPct", i.bcSdPct)
        put("rangeSdYd", i.rangeSdYd); put("windSpeedSdMph", i.windSpeedSdMph); put("windDirSdDeg", i.windDirSdDeg)
        put("groupMoa", i.groupMoa); put("aimSdMoa", i.aimSdMoa)
    }.toString()

    companion object {
        fun fromJson(text: String?): HitState {
            val d = HitState()
            if (text.isNullOrBlank()) return d
            return runCatching {
                val o = JSONObject(text)
                fun n(k: String, def: Double) = o.optDouble(k, def).takeIf { !it.isNaN() && !it.isInfinite() } ?: def
                val di = d.inputs
                HitState(
                    useSaved = o.optBoolean("useSaved", d.useSaved), loadId = o.optString("loadId").ifEmpty { null },
                    diameterIn = n("diameterIn", d.diameterIn), weightGr = n("weightGr", d.weightGr), bc = n("bc", d.bc),
                    g1 = o.optBoolean("g1", false), mvFps = n("mvFps", d.mvFps), sightHeightIn = n("sightHeightIn", d.sightHeightIn),
                    zeroYd = n("zeroYd", d.zeroYd), twistIn = n("twistIn", d.twistIn),
                    inputs = HitSim.Inputs(
                        rangeYd = n("rangeYd", di.rangeYd), targetWidthIn = n("targetWidthIn", di.targetWidthIn),
                        targetHeightIn = n("targetHeightIn", di.targetHeightIn), round = o.optBoolean("round", false),
                        windMph = n("windMph", di.windMph), windFromDeg = n("windFromDeg", di.windFromDeg),
                        mvSdFps = n("mvSdFps", di.mvSdFps), bcSdPct = n("bcSdPct", di.bcSdPct), rangeSdYd = n("rangeSdYd", di.rangeSdYd),
                        windSpeedSdMph = n("windSpeedSdMph", di.windSpeedSdMph), windDirSdDeg = n("windDirSdDeg", di.windDirSdDeg),
                        groupMoa = n("groupMoa", di.groupMoa), aimSdMoa = n("aimSdMoa", di.aimSdMoa),
                    ),
                )
            }.getOrDefault(d)
        }
    }
}

private fun fmt(v: Double, dec: Int) = String.format(Locale.US, "%.${dec}f", v)

class AnalysisActivity : CalcActivity() {
    private lateinit var form: Form
    private lateinit var tabBar: View
    override val snackAnchor: View? get() = if (::tabBar.isInitialized) tabBar else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabBar = TabNav.install(this, R.layout.calc_activity_scroll, Tab.ANALYSIS)
        setupToolbar(getString(R.string.calc_tab_analysis), up = false)
        form = contentForm()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        form.settings = settings
        form.clear()
        form.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)) })
        val n = AnalysisPrefs.tnfEntries(this).size
        form.group {
            nav(getString(R.string.calc_hit_sim), "", sub = "Your chance of hitting a target at a distance, given how well you know the range, the wind and your velocity, and how well your rifle groups") {
                startActivity(Intent(this@AnalysisActivity, HitSimActivity::class.java))
            }
            nav(getString(R.string.calc_tnf_compare), if (n == 0) "" else "$n", sub = "Which ammo bucks the wind best: your saved ammo, bullets at a velocity, or a velocity and BC you type in") {
                startActivity(Intent(this@AnalysisActivity, TnfCompareScreen::class.java))
            }
        }
    }
}

class HitSimActivity : CalcActivity() {
    private lateinit var form: Form
    private var st = HitState()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private val runSoon = Runnable { compute() }

    private lateinit var bigText: TextView
    private lateinit var subText: TextView
    private lateinit var plot: TargetPlotView
    private lateinit var causesBox: LinearLayout
    private lateinit var chart: HitChartView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(R.string.calc_hit_sim))
        form = contentForm()
        st = AnalysisPrefs.hitState(this)
    }

    override fun onResume() { super.onResume(); render() }
    override fun onDestroy() { main.removeCallbacks(runSoon); io.shutdownNow(); super.onDestroy() }

    private fun update(s: HitState) { st = s; AnalysisPrefs.setHitState(this, s); main.removeCallbacks(runSoon); main.postDelayed(runSoon, 250) }
    private fun updateIn(f: (HitSim.Inputs) -> HitSim.Inputs) = update(st.copy(inputs = f(st.inputs)))

    private fun Form.Group.ranged(label: String, q: Quantity, value: Double, lo: Double, hi: Double, set: (Double) -> Unit,
                                  decimals: Int? = null, sub: String? = null) {
        var row: Form.NumberRow? = null
        row = number(label, q, value, { v ->
            val ok = v != null && v >= lo && v <= hi
            row?.edit?.error = if (v == null || ok) null else {
                val s = settings
                val dec = decimals ?: Units.decimals(q, s)
                val unit = Units.label(q, s).let { if (it.isEmpty() || it == "°") it else " $it" }
                "Use ${Num.trim(Units.toDisplay(q, lo, s), dec)} to ${Num.trim(Units.toDisplay(q, hi, s), dec)}$unit"
            }
            if (ok) set(v!!)
        }, decimals = decimals, sub = sub)
    }

    private data class Gear(val rifle: Rifle, val load: Load, val bullet: Bullet, val title: String)

    private fun allAmmo(): List<Gear> {
        val rifles = store.rifles().associateBy { it.id }
        return store.loads(null).mapNotNull { l ->
            val r = rifles[l.rifleId] ?: return@mapNotNull null
            val b = store.bullet(l.bulletId) ?: return@mapNotNull null
            Gear(r, l, b, "${r.name} · ${l.name}")
        }
    }

    private fun gear(): Gear? {
        if (st.useSaved) {
            val all = allAmmo()
            val sel = Selected.resolve(store)
            return all.firstOrNull { it.load.id == st.loadId } ?: all.firstOrNull { it.load.id == sel.load?.id } ?: all.firstOrNull()
        }
        val b = Bullet(id = "sim", name = "Typed in", diameterIn = st.diameterIn, weightGr = st.weightGr,
            dragModel = if (st.g1) DragModel.G1 else DragModel.G7, bcPoints = listOf(BcPoint(st.bc, 0.0)))
        val r = Rifle(id = "sim", name = "", twistIn = st.twistIn, sightHeightIn = st.sightHeightIn)
        val l = Load(id = "sim", rifleId = "sim", name = "", bulletId = "sim", muzzleVelocityFps = st.mvFps, zeroRangeYd = st.zeroYd)
        return Gear(r, l, b, "Typed in")
    }

    private fun render() {
        form.settings = settings
        form.clear()
        val s = settings
        val all = allAmmo()
        val saved = st.useSaved && all.isNotEmpty()

        form.header("Ammo")
        form.group {
            segmented("Use", listOf("My ammo", "Type it in"), if (saved) 0 else 1) { i -> update(st.copy(useSaved = i == 0)); render() }
            if (saved) {
                val g = gear()
                val idx = all.indexOfFirst { it.load.id == g?.load?.id }.coerceAtLeast(0)
                choice("Ammo", all.map { it.title }, idx, sub = g?.let { "${Num.trim(Units.toDisplay(Quantity.VELOCITY, it.load.muzzleVelocityFps, s), 0)} ${Units.label(Quantity.VELOCITY, s)} · zero ${Num.trim(Units.toDisplay(Quantity.DISTANCE, it.load.zeroRangeYd, s), 0)} ${Units.label(Quantity.DISTANCE, s)}" }) { i ->
                    update(st.copy(loadId = all[i].load.id))
                }
            } else {
                if (st.useSaved) info("No saved ammo yet", "", sub = "Type the ammo in below, or add ammo under My gear.")
                ranged("Bullet diameter", Quantity.BULLET_LENGTH, st.diameterIn, 0.1, 0.6, { x -> update(st.copy(diameterIn = x)) })
                ranged("Bullet weight", Quantity.WEIGHT, st.weightGr, 10.0, 800.0, { x -> update(st.copy(weightGr = x)) })
                segmented("BC type", listOf("G7", "G1"), if (st.g1) 1 else 0) { i -> update(st.copy(g1 = i == 1)) }
                ranged("BC", Quantity.PLAIN, st.bc, 0.03, 1.5, { x -> update(st.copy(bc = x)) }, decimals = 3)
                ranged("Muzzle velocity", Quantity.VELOCITY, st.mvFps, 600.0, 4800.0, { x -> update(st.copy(mvFps = x)) })
                ranged("Zero distance", Quantity.DISTANCE, st.zeroYd, 10.0, 1000.0, { x -> update(st.copy(zeroYd = x)) })
                ranged("Sight height", Quantity.LENGTH, st.sightHeightIn, 0.0, 6.0, { x -> update(st.copy(sightHeightIn = x)) })
                ranged("Twist, 1 in", Quantity.BULLET_LENGTH, st.twistIn, 3.0, 30.0, { x -> update(st.copy(twistIn = x)) }, decimals = 1)
            }
        }

        form.header("Target")
        form.group {
            ranged("Distance", Quantity.DISTANCE, st.inputs.rangeYd, 50.0, 3000.0, { r -> updateIn { it.copy(rangeYd = r) } })
            ranged("Width", Quantity.LENGTH, st.inputs.targetWidthIn, 0.25, 400.0, { w -> updateIn { it.copy(targetWidthIn = w) } })
            ranged("Height", Quantity.LENGTH, st.inputs.targetHeightIn, 0.25, 400.0, { h -> updateIn { it.copy(targetHeightIn = h) } })
            segmented("Shape", listOf("Rectangle", "Round"), if (st.inputs.round) 1 else 0) { i -> updateIn { it.copy(round = i == 1) } }
        }

        form.header("The wind you call")
        form.group {
            ranged("Speed", Quantity.SPEED, st.inputs.windMph, 0.0, 60.0, { w -> updateIn { it.copy(windMph = w) } })
            val clocks = (0 until 12).map { h ->
                val c = if (h == 0) 12 else h
                "$c o'clock" + when (h) { 0 -> " (head wind)"; 3 -> " (from the right)"; 6 -> " (tail wind)"; 9 -> " (from the left)"; else -> "" }
            }
            val sel = (Math.round(((st.inputs.windFromDeg % 360) + 360) % 360 / 30.0).toInt()) % 12
            choice("From", clocks, sel) { h -> updateIn { it.copy(windFromDeg = h * 30.0) } }
        }

        form.header("How far off you might be")
        form.note("Each is one standard deviation: about two shots in three are within it.")
        form.group {
            ranged("Velocity spread (SD)", Quantity.VELOCITY, st.inputs.mvSdFps, 0.0, 100.0, { x -> updateIn { it.copy(mvSdFps = x) } })
            ranged("BC, ± %", Quantity.PLAIN, st.inputs.bcSdPct, 0.0, 20.0, { x -> updateIn { it.copy(bcSdPct = x) } }, decimals = 1)
            ranged("Range, ±", Quantity.DISTANCE, st.inputs.rangeSdYd, 0.0, 200.0, { x -> updateIn { it.copy(rangeSdYd = x) } })
            ranged("Wind speed, ±", Quantity.SPEED, st.inputs.windSpeedSdMph, 0.0, 30.0, { x -> updateIn { it.copy(windSpeedSdMph = x) } })
            ranged("Wind direction, ±", Quantity.ANGLE, st.inputs.windDirSdDeg, 0.0, 90.0, { x -> updateIn { it.copy(windDirSdDeg = x) } }, decimals = 0,
                sub = "Matters most in a quartering wind; little in a full-value 3 or 9 o'clock wind")
            ranged("Group size, MOA", Quantity.PLAIN, st.inputs.groupMoa, 0.0, 10.0, { x -> updateIn { it.copy(groupMoa = x) } },
                decimals = 2, sub = "Your rifle and ammo's typical 5-shot group")
            ranged("Aim, ± MOA", Quantity.PLAIN, st.inputs.aimSdMoa, 0.0, 10.0, { x -> updateIn { it.copy(aimSdMoa = x) } },
                decimals = 2, sub = "How steady you hold, from your position")
        }

        form.header("Result")
        form.group {
            val col = LinearLayout(this@HitSimActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
            bigText = TextView(this@HitSimActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(themeColor(MR.attr.colorOnSurface))
            }
            subText = TextView(this@HitSimActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.NOTE); gravity = Gravity.CENTER
                setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
            }
            plot = TargetPlotView(this@HitSimActivity)
            col.addView(bigText); col.addView(subText)
            col.addView(plot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(8) })
            custom(col)
        }
        form.header("What spreads the shots")
        form.group {
            causesBox = LinearLayout(this@HitSimActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            custom(causesBox)
        }
        form.header("Hit chance by distance")
        form.group {
            chart = HitChartView(this@HitSimActivity)
            custom(chart)
        }
        val a = store.lastShot().atmosphere
        form.note("Every simulated shot is dialled for the distance and wind you call, then lands off centre by a random draw of the errors above. " +
            "A shot counts as a hit when the bullet touches the target. Weather comes from the Calculator: " +
            "${Num.trim(Units.toDisplay(Quantity.TEMPERATURE, a.temperatureF, s), 0)}${Units.label(Quantity.TEMPERATURE, s)}, " +
            "${Num.trim(Units.toDisplay(Quantity.PRESSURE, a.pressureInHg, s), Units.decimals(Quantity.PRESSURE, s))} ${Units.label(Quantity.PRESSURE, s)}, " +
            "${Num.trim(Units.toDisplay(Quantity.ALTITUDE, a.altitudeFt, s), 0)} ${Units.label(Quantity.ALTITUDE, s)}.")
        compute()
    }

    private fun compute() {
        val g = gear()
        val i = st.inputs
        val shot = store.lastShot()
        val s = settings
        val gen = ++generation
        if (g == null) { bigText.text = "—"; subText.text = "Pick or type in the ammo first."; return }
        subText.text = "Working it out…"
        io.execute {
            val rep = runCatching { HitSim.run(g.rifle, g.load, g.bullet, shot, i) }
            main.post {
                if (gen != generation || isFinishing || isDestroyed) return@post
                rep.onFailure { bigText.text = "—"; subText.text = "Can't work this out: ${it.message ?: "check the ammo"}" }
                rep.onSuccess { show(it, i, s, g) }
            }
        }
    }

    private fun len(inches: Double, s: com.longrange.dragcurve.model.AppSettings, dec: Int = 1) =
        "${Num.trim(Units.toDisplay(Quantity.LENGTH, inches, s), dec)} ${Units.label(Quantity.LENGTH, s)}"

    private fun show(rep: HitSim.Report, i: HitSim.Inputs, s: com.longrange.dragcurve.model.AppSettings, g: Gear) {
        val r = rep.main
        val dist = "${Num.trim(Units.toDisplay(Quantity.DISTANCE, i.rangeYd, s), 0)} ${Units.label(Quantity.DISTANCE, s)}"
        bigText.text = "${Math.round(r.probability * 100)}%"
        bigText.contentDescription = "Hit chance ${Math.round(r.probability * 100)} percent"
        subText.text = "hit chance at $dist · ${String.format(Locale.US, "%,d", r.hits)} of ${String.format(Locale.US, "%,d", r.shots)} simulated shots\n" +
            "${len(i.targetWidthIn, s)} × ${len(i.targetHeightIn, s)} ${if (i.round) "round target" else "target"} · ${g.title}"
        plot.set(i.targetWidthIn, i.targetHeightIn, i.round, r.impacts, r.hitFlags, r.sdHIn, r.sdVIn)

        causesBox.removeAllViews()
        val top = r.causes.firstOrNull()?.totalIn?.takeIf { it > 0 } ?: 1.0
        for (c in r.causes) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(TextView(this).apply { text = c.name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(themeColor(MR.attr.colorOnSurface)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val tiny = Units.toDisplay(Quantity.LENGTH, c.totalIn, s) < 0.05
            val parts = if (tiny) listOf("under 0.1 ${Units.label(Quantity.LENGTH, s)}") else buildList {
                if (c.vIn >= 0.05 * c.totalIn) add("↕ ${len(c.vIn, s)}")
                if (c.hIn >= 0.05 * c.totalIn) add("↔ ${len(c.hIn, s)}")
            }
            line.addView(TextView(this).apply { text = parts.joinToString("  "); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(themeColor(MR.attr.colorOnSurfaceVariant)) })
            row.addView(line)
            row.addView(BarView(this).apply {
                fraction = (c.totalIn / top).toFloat()
                color = themeColor(MR.attr.colorPrimary)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4) })
            if (c.note.isNotEmpty()) row.addView(TextView(this).apply {
                text = c.note
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.NOTE); setTextColor(themeColor(MR.attr.colorOnSurfaceVariant)); setPadding(0, dp(4), 0, 0)
            })
            causesBox.addView(row)
        }
        causesBox.addView(TextView(this).apply {
            text = "Typical miss from each cause at the target (about one standard deviation), up and down ↕ and side to side ↔. The biggest one is where to improve."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.NOTE); setTextColor(themeColor(MR.attr.colorOnSurfaceVariant)); setPadding(dp(4), dp(6), dp(4), 0)
        })
        chart.set(rep.chart.map { Units.toDisplay(Quantity.DISTANCE, it.first, s) to it.second },
            Units.toDisplay(Quantity.DISTANCE, i.rangeYd, s), Units.label(Quantity.DISTANCE, s))
    }
}

class TargetPlotView(ctx: Context) : View(ctx) {
    private var w = 12.0; private var h = 12.0; private var round = false
    private var pts: List<Pair<Double, Double>> = emptyList(); private var hits: List<Boolean> = emptyList()
    private var sdH = 1.0; private var sdV = 1.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(widthIn: Double, heightIn: Double, round: Boolean, impacts: List<Pair<Double, Double>>, hit: List<Boolean>, sdHIn: Double, sdVIn: Double) {
        w = widthIn; h = heightIn; this.round = round; pts = impacts; hits = hit; sdH = sdHIn; sdV = sdVIn
        contentDescription = "Target with ${impacts.size} simulated impacts, ${hit.count { it }} hits"
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val half = max(max(w, h) / 2 * 1.15, 2.6 * max(sdH, sdV))
        val k = (min(width, height) / 2f - 4f) / half.toFloat()
        val rect = RectF(cx - (w / 2 * k).toFloat(), cy - (h / 2 * k).toFloat(), cx + (w / 2 * k).toFloat(), cy + (h / 2 * k).toFloat())
        paint.style = Paint.Style.FILL; paint.color = themeColor(MR.attr.colorSurfaceContainerHighest)
        if (round) c.drawOval(rect, paint) else c.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(2).toFloat(); paint.color = themeColor(MR.attr.colorOutline)
        if (round) c.drawOval(rect, paint) else c.drawRect(rect, paint)
        paint.strokeWidth = 1f; paint.color = themeColor(MR.attr.colorOutlineVariant)
        c.drawLine(0f, cy, width.toFloat(), cy, paint); c.drawLine(cx, 0f, cx, height.toFloat(), paint)
        paint.style = Paint.Style.FILL
        val hitC = themeColor(MR.attr.colorPrimary); val missC = themeColor(MR.attr.colorError)
        val r = context.dp(2).toFloat() + 0.5f
        pts.forEachIndexed { i, (x, y) ->
            paint.color = if (hits.getOrElse(i) { false }) hitC else missC
            paint.alpha = 190
            c.drawCircle(cx + (x * k).toFloat(), cy - (y * k).toFloat(), r, paint)
        }
        paint.alpha = 255
    }
}

class HitChartView(ctx: Context) : View(ctx) {
    init { minimumHeight = ctx.dp(220) }
    private var pts: List<Pair<Double, Double>> = emptyList()
    private var mark = 0.0
    private var unit = "yd"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(points: List<Pair<Double, Double>>, markAt: Double, unitLabel: String) {
        pts = points; mark = markAt; unit = unitLabel
        contentDescription = points.joinToString(", ") { "${it.first.toInt()} $unitLabel ${Math.round(it.second * 100)} percent" }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (pts.size < 2) return
        val text = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        val left = paint.apply { textSize = text }.measureText("100%") + context.dp(8)
        val bottom = height - text * 1.8f
        val top = text; val right = width - context.dp(12).toFloat()
        val x0 = pts.first().first; val x1 = pts.last().first
        fun x(v: Double) = (left + (v - x0) / (x1 - x0) * (right - left)).toFloat()
        fun y(p: Double) = (bottom - p * (bottom - top)).toFloat()
        val muted = themeColor(MR.attr.colorOnSurfaceVariant)
        paint.textSize = text; paint.style = Paint.Style.FILL
        for (p in listOf(0.0, 0.5, 1.0)) {
            paint.color = themeColor(MR.attr.colorOutlineVariant); paint.strokeWidth = 1f
            c.drawLine(left, y(p), right, y(p), paint)
            paint.color = muted; paint.textAlign = Paint.Align.RIGHT
            c.drawText("${(p * 100).toInt()}%", left - context.dp(6), y(p) + text / 3, paint)
        }
        paint.textAlign = Paint.Align.CENTER; paint.color = muted
        val ticks = listOf(x0, (x0 + x1) / 2, x1)
        ticks.forEachIndexed { i, t ->
            paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            c.drawText("${Math.round(t)} ${if (i == 2) unit else ""}".trim(), x(t), height - text * 0.3f, paint)
        }
        val path = Path()
        pts.forEachIndexed { i, (d, p) -> if (i == 0) path.moveTo(x(d), y(p)) else path.lineTo(x(d), y(p)) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(3).toFloat(); paint.color = themeColor(MR.attr.colorPrimary)
        paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, paint)
        val m = pts.minByOrNull { abs(it.first - mark) } ?: return
        paint.style = Paint.Style.FILL
        c.drawCircle(x(m.first), y(m.second), context.dp(6).toFloat(), paint)
        paint.color = themeColor(MR.attr.colorOnSurface); paint.textAlign = Paint.Align.CENTER
        val label = "${Math.round(m.second * 100)}%"
        val ly = if (y(m.second) - context.dp(12) < top) y(m.second) + text + context.dp(8) else y(m.second) - context.dp(12)
        c.drawText(label, x(m.first).coerceIn(left + text, right - text), ly, paint)
    }
}

class TnfCompareScreen : CalcActivity() {
    private lateinit var form: Form
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val calc = TnfCompare.Calc()
    private var entries: List<TnfCompare.Entry> = emptyList()
    private var ref = -1
    private var rows: List<TnfCompare.Row>? = null
    private var generation = 0

    private val pickBullet = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = res.data?.getStringExtra(BulletLibraryActivity.RESULT_ID)
        val b = id?.let { store.bullet(it) }
        if (res.resultCode == Activity.RESULT_OK && b != null) {
            val mv = store.loads(null).firstOrNull { it.bulletId == b.id }?.muzzleVelocityFps ?: 2800.0
            askMv("Muzzle velocity for ${BulletSearch.title(b)}", mv) { v -> add(TnfCompare.Entry(TnfCompare.Kind.BULLET, refId = b.id, mvFps = v)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(R.string.calc_tnf_compare))
        form = contentForm()
    }

    override fun onResume() {
        super.onResume()
        entries = AnalysisPrefs.tnfEntries(this)
        ref = AnalysisPrefs.tnfReference(this).takeIf { it in entries.indices } ?: -1
        recompute()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun save() { AnalysisPrefs.setTnfEntries(this, entries); AnalysisPrefs.setTnfReference(this, ref) }

    private fun flight(e: TnfCompare.Entry): TnfCompare.Flight? {
        val s = settings
        fun mvText(v: Double) = "${Num.trim(Units.toDisplay(Quantity.VELOCITY, v, s), 0)} ${Units.label(Quantity.VELOCITY, s)}"
        return when (e.kind) {
            TnfCompare.Kind.CUSTOM -> TnfCompare.customFlight(e.label, e.mvFps ?: return null, e.g7Bc ?: return null).copy(subtitle = "Typed in at ${mvText(e.mvFps)}")
            TnfCompare.Kind.BULLET -> {
                val b = store.bullet(e.refId) ?: return null
                val mv = e.mvFps ?: return null
                TnfCompare.Flight(BulletSearch.title(b), "Bullet at ${mvText(mv)}", b, TnfCompare.load(b, mv))
            }
            TnfCompare.Kind.AMMO -> {
                val l = store.loads(null).firstOrNull { it.id == e.refId } ?: return null
                val b = store.bullet(l.bulletId) ?: return null
                val r = store.rifles().firstOrNull { it.id == l.rifleId }
                TnfCompare.Flight(l.name, listOfNotNull(r?.name, "ammo at ${mvText(l.muzzleVelocityFps)}").joinToString(" · "), b, l)
            }
        }
    }

    private fun recompute() {
        rows = null
        render()
        val gen = ++generation
        val es = entries; val r = ref
        val flights = es.associateWith { flight(it) }
        io.execute {
            val out = runCatching { TnfCompare.compare(es, { flights[it] }, calc, r.takeIf { it >= 0 }) }.getOrNull()
            main.post {
                if (gen != generation || isFinishing || isDestroyed) return@post
                rows = out ?: emptyList(); render()
            }
        }
    }

    private fun add(e: TnfCompare.Entry) {
        if (entries.size >= TnfCompare.MAX_ENTRIES) return
        entries = entries + e; save(); recompute()
    }

    private fun remove(i: Int) {
        entries = entries.filterIndexed { j, _ -> j != i }
        ref = when { ref == i -> -1; ref > i -> ref - 1; else -> ref }
        save(); recompute()
    }

    private fun render() {
        val s = settings
        form.settings = s
        form.clear()
        form.note("TNF = muzzle velocity × G7 BC. The higher it is, the less the wind moves the bullet. " +
            "Ammo with several BCs, a G1 BC or a drag curve gets the single G7 BC that drifts the same at 1000 yd.")
        if (entries.size >= 2) {
            form.group {
                choice("Compare against", listOf("Highest TNF") + entries.mapIndexed { i, e -> "${i + 1}. " + (rows?.getOrNull(i)?.title ?: e.label.ifBlank { e.kind.name.lowercase() }) }, ref + 1) { i ->
                    ref = i - 1; save(); recompute()
                }
            }
        }
        val rs = rows
        if (entries.isEmpty()) form.note("Nothing to compare yet. Add your saved ammo, a bullet at a velocity, or type in a velocity and BC.")
        else if (rs == null) form.note("Working it out…")
        else {
            val maxTnf = rs.mapNotNull { it.tnf }.maxOrNull() ?: 1.0
            val maxDrift = rs.mapNotNull { it.driftMil }.maxOrNull() ?: 1.0
            rs.forEachIndexed { i, r -> card(i, r, maxTnf, maxDrift, s) }
        }
        form.header("Add")
        val full = entries.size >= TnfCompare.MAX_ENTRIES
        if (full) form.note("The list is full (${TnfCompare.MAX_ENTRIES}). Remove one to add another.")
        else form.group {
            nav("My saved ammo", "", sub = "Pick one or more") { pickAmmo() }
            nav("A bullet at a muzzle velocity", "", sub = "From your bullet library or your own bullets") {
                pickBullet.launch(Intent(this@TnfCompareScreen, BulletLibraryActivity::class.java).putExtra(BulletLibraryActivity.EXTRA_PICK, true))
            }
            nav("Type in a velocity and BC", "", sub = "Any TNF profile: a name, muzzle velocity and G7 BC") { askCustom(null) }
        }
    }

    private fun card(i: Int, r: TnfCompare.Row, maxTnf: Double, maxDrift: Double, s: com.longrange.dragcurve.model.AppSettings) {
        form.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) })
        form.group {
            val col = LinearLayout(this@TnfCompareScreen).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(6)) }
            fun tv(t: String, sp: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this@TnfCompareScreen).apply {
                text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); if (bold) typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(if (muted) MR.attr.colorOnSurfaceVariant else MR.attr.colorOnSurface))
            }
            val head = LinearLayout(this@TnfCompareScreen).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val names = LinearLayout(this@TnfCompareScreen).apply { orientation = LinearLayout.VERTICAL }
            names.addView(tv("${i + 1}. ${r.title}", 18f, bold = true))
            if (r.subtitle.isNotEmpty()) names.addView(tv(r.subtitle, 14f, muted = true))
            head.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (r.tnf != null) head.addView(tv(fmt(r.tnf, 0), 30f, bold = true).apply { contentDescription = "TNF ${fmt(r.tnf, 0)}" })
            col.addView(head)
            if (r.problem.isNotEmpty()) col.addView(tv(r.problem, 15f).apply { setTextColor(themeColor(MR.attr.colorError)) })
            if (r.tnf != null) {
                val bc = "G7 BC ${fmt(r.g7Bc!!, 3)}" + if (r.bcIsEquivalent) " (1000-yd equivalent)" else ""
                val delta = when {
                    r.isReference -> if (entries.size > 1) "the reference" else ""
                    r.deltaTnf != null -> "${if (r.deltaTnf >= 0) "+" else "−"}${fmt(abs(r.deltaTnf), 0)} TNF vs the reference"
                    else -> ""
                }
                col.addView(tv(listOf(bc, delta).filter { it.isNotEmpty() }.joinToString(" · "), 15f).apply { setPadding(0, dp(6), 0, 0) })
                val inches = r.driftMil!! * 36.0
                col.addView(tv("Wind drift at 1000 yd, 10 mph full value: ${fmt(r.driftMil, 2)} mil (${Num.trim(Units.toDisplay(Quantity.LENGTH, inches, s), 1)} ${Units.label(Quantity.LENGTH, s)})", 15f))
                if (!r.isReference) {
                    val m = when (val mm = r.matchMv) {
                        is TnfCompare.MatchMv.At -> "${Num.trim(Units.toDisplay(Quantity.VELOCITY, Math.ceil(mm.mvFps / 10) * 10, s), 0)} ${Units.label(Quantity.VELOCITY, s)}"
                        TnfCompare.MatchMv.AboveMax -> "more than ${Num.trim(Units.toDisplay(Quantity.VELOCITY, TnfCompare.MV_HIGH, s), 0)} ${Units.label(Quantity.VELOCITY, s)}"
                        TnfCompare.MatchMv.NotAvailable -> null
                    }
                    if (m != null) col.addView(tv("To drift as little as the reference it needs $m", 15f, muted = true))
                }
                fun bar(label: String, f: Double, color: Int) = LinearLayout(this@TnfCompareScreen).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0)
                    addView(tv(label, 13f, muted = true).apply { minEms = 3 })
                    addView(BarView(this@TnfCompareScreen).apply { fraction = f.toFloat().coerceIn(0f, 1f); this.color = color },
                        LinearLayout.LayoutParams(0, dp(10), 1f))
                }
                col.addView(bar("TNF", r.tnf / maxTnf, col.themeColor(if (r.isReference) MR.attr.colorSecondary else MR.attr.colorPrimary)))
                col.addView(bar("Drift", r.driftMil / maxDrift, col.themeColor(MR.attr.colorTertiary)))
            }
            val buttons = LinearLayout(this@TnfCompareScreen).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            fun btn(t: String, go: () -> Unit) = MaterialButton(this@TnfCompareScreen, null, MR.attr.borderlessButtonStyle).apply {
                text = t; setOnClickListener { go() }
            }
            val e = r.entry
            when (e.kind) {
                TnfCompare.Kind.BULLET -> buttons.addView(btn("Change velocity") {
                    askMv("Muzzle velocity", e.mvFps ?: 2800.0) { v -> entries = entries.toMutableList().also { it[i] = e.copy(mvFps = v) }; save(); recompute() }
                })
                TnfCompare.Kind.CUSTOM -> buttons.addView(btn("Edit") { askCustom(i) })
                TnfCompare.Kind.AMMO -> {}
            }
            buttons.addView(btn("Remove") { remove(i) })
            col.addView(buttons)
            custom(col)
        }
    }

    private fun pickAmmo() {
        val rifles = store.rifles().associateBy { it.id }
        val loads = store.loads(null).filter { it.rifleId in rifles }
        if (loads.isEmpty()) { alert("No saved ammo", "Add ammo under My gear first, or type in a velocity and BC."); return }
        val have = entries.filter { it.kind == TnfCompare.Kind.AMMO }.map { it.refId }.toSet()
        val labels = loads.map { "${rifles[it.rifleId]?.name} · ${it.name}" }.toTypedArray()
        val checked = BooleanArray(loads.size) { loads[it].id in have }
        MaterialAlertDialogBuilder(this).setTitle("Saved ammo to compare")
            .setMultiChoiceItems(labels, checked) { _, w, on -> checked[w] = on }
            .setPositiveButton("Add") { _, _ ->
                val new = loads.filterIndexed { j, l -> checked[j] && l.id !in have }.map { TnfCompare.Entry(TnfCompare.Kind.AMMO, refId = it.id) }
                val room = TnfCompare.MAX_ENTRIES - entries.size
                entries = entries + new.take(room); save(); recompute()
                if (new.size > room) alert("List full", "Only ${TnfCompare.MAX_ENTRIES} can be compared at once; ${new.size - room} were not added.")
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun numberField(hint: String, value: String, decimal: Boolean = true) = EditText(this).apply {
        this.hint = hint; setText(value); maxLines = 1
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
    }

    private fun dialogBox(vararg rows: Pair<String, View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0)
        for ((label, v) in rows) {
            addView(TextView(this@TnfCompareScreen).apply { text = label; setPadding(0, dp(8), 0, 0) })
            addView(v)
        }
    }

    private fun askMv(title: String, mvFps: Double, onOk: (Double) -> Unit) {
        val s = settings
        val unit = Units.label(Quantity.VELOCITY, s)
        val f = numberField(unit, Num.trim(Units.toDisplay(Quantity.VELOCITY, mvFps, s), Units.decimals(Quantity.VELOCITY, s)))
        MaterialAlertDialogBuilder(this).setTitle(title).setView(dialogBox("Muzzle velocity, $unit" to f))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = Num.parse(f.text)?.let { Units.fromDisplay(Quantity.VELOCITY, it, s) }
                if (v == null || v < 600 || v > 4800) alert("Muzzle velocity", "Type a velocity between 600 and 4800 fps.") else onOk(v)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askCustom(index: Int?) {
        val s = settings
        val old = index?.let { entries[it] }
        val unit = Units.label(Quantity.VELOCITY, s)
        val name = EditText(this).apply { hint = "e.g. 6.5 PRC 147"; setText(old?.label.orEmpty()); maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        val mv = numberField(unit, old?.mvFps?.let { Num.trim(Units.toDisplay(Quantity.VELOCITY, it, s), Units.decimals(Quantity.VELOCITY, s)) }.orEmpty())
        val bc = numberField("e.g. 0.351", old?.g7Bc?.let { fmt(it, 3) }.orEmpty())
        MaterialAlertDialogBuilder(this).setTitle(if (old == null) "Type in a TNF profile" else "Edit")
            .setView(dialogBox("Name" to name, "Muzzle velocity, $unit" to mv, "G7 BC" to bc))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = Num.parse(mv.text)?.let { Units.fromDisplay(Quantity.VELOCITY, it, s) }
                val b = Num.parse(bc.text)
                when {
                    v == null || v < 600 || v > 4800 -> alert("Muzzle velocity", "Type a velocity between 600 and 4800 fps.")
                    b == null || b < 0.03 || b > 1.5 -> alert("G7 BC", "Type a G7 BC between 0.03 and 1.5 (most rifle bullets are 0.2–0.4).")
                    else -> {
                        val e = TnfCompare.Entry(TnfCompare.Kind.CUSTOM, label = name.text.toString().trim(), mvFps = v, g7Bc = b)
                        if (index == null) add(e) else { entries = entries.toMutableList().also { it[index] = e }; save(); recompute() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
