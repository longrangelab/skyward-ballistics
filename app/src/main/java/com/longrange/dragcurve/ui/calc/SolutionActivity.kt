package com.longrange.dragcurve.ui.calc

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.longrange.dragcurve.R
import com.longrange.dragcurve.engine.Engine
import com.longrange.dragcurve.engine.Solution
import com.longrange.dragcurve.engine.clicks
import com.longrange.dragcurve.engine.milTo
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.android.material.R as MR

class SolutionActivity : CalcActivity() {
    private lateinit var solutionBox: LinearLayout
    private lateinit var tableBox: LinearLayout
    private lateinit var progress: ImageView
    private lateinit var busyLogo: SpinLogoDrawable
    private val ui = Handler(Looper.getMainLooper())
    private val showBusy = Runnable {
        progress.visibility = View.VISIBLE
        if (Motion.on(this)) busyLogo.spin() else busyLogo.rest()
    }
    private var shownTab = 0
    private val lastAnswers = HashMap<String, String>()
    private lateinit var nav: BottomNavigationView
    private var tab = R.id.nav_solution
    private var shot = ShotInputs()
    private var rifle: Rifle? = null
    private var load: Load? = null
    private var bullet: Bullet? = null
    private var generation = 0
    private var tableSolution: Solution? = null
    private var tableText: String = ""
    private lateinit var calibrate: CalibratePanel
    private lateinit var cardPanel: TargetCardPanel

    private val host = object : PanelHost {
        override val store get() = this@SolutionActivity.store
        override val worker get() = SolutionActivity.worker
        override fun rifle() = rifle
        override fun load() = load
        override fun bullet() = bullet
        override fun shot() = shot
        override fun settings() = this@SolutionActivity.settings
        override fun alive() = !isFinishing && !isDestroyed
        override fun onLoadChanged(l: Load) {
            load = l
            com.google.android.material.snackbar.Snackbar.make(nav, "Saved to \"${l.name}\"", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
            showTab(tab)
        }
        override fun runOnUi(r: () -> Unit) = runOnUiThread(r)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_solution)
        setupToolbar(getString(R.string.calc_solution))
        solutionBox = findViewById(R.id.solutionContent)
        tableBox = findViewById(R.id.tableContent)
        progress = findViewById(R.id.progress)
        busyLogo = SpinLogoDrawable.create(this, 64, onNavy = SpinLogoDrawable.isNight(this), turnMs = MotionRules.BUSY_TURN_MS)
        progress.setImageDrawable(busyLogo)
        nav = findViewById(R.id.bottomNav)
        calibrate = CalibratePanel(host, findViewById(R.id.calibrateContent)).also { it.restore(savedInstanceState) }
        cardPanel = TargetCardPanel(host, findViewById(R.id.cardContent)).also { it.restore(savedInstanceState) }
        tab = savedInstanceState?.getInt("tab", R.id.nav_solution)
            ?: if (intent.getStringExtra(EXTRA_TAB) == TAB_CALIBRATE) R.id.nav_calibrate else R.id.nav_solution
        nav.selectedItemId = tab
        nav.setOnItemSelectedListener { item -> showTab(item.itemId); true }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        calibrate.save(outState)
        cardPanel.save(outState)
    }

    private fun busy(on: Boolean) {
        ui.removeCallbacks(showBusy)
        if (on) ui.postDelayed(showBusy, MotionRules.BUSY_DELAY_MS)
        else { busyLogo.rest(); progress.visibility = View.GONE }
    }

    override fun onPause() {
        busyLogo.pause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        busyLogo.rest()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val sel = Selected.resolve(store)
        if (!sel.complete) { finish(); return }
        rifle = sel.rifle; load = sel.load; bullet = sel.bullet
        shot = store.lastShot()
        showTab(tab)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.calc_share, menu)
        menu.findItem(R.id.action_share)?.isVisible = tab == R.id.nav_table || (tab == R.id.nav_card && cardPanel.hasCard())
        if (tab == R.id.nav_card) {
            menu.add(0, MENU_NEW_CARD, 10, "New card")
            if (cardPanel.hasCard()) {
                menu.add(0, MENU_RENAME_CARD, 11, "Rename card")
                menu.add(0, MENU_DELETE_CARD, 12, "Delete card")
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> {
                val text = if (tab == R.id.nav_card) cardPanel.shareText() else tableText
                if (text.isNotEmpty()) startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Skyward Ballistics — ${rifle?.name} / ${load?.name}")
                        .putExtra(Intent.EXTRA_TEXT, text), getString(R.string.calc_share)))
                return true
            }
            MENU_NEW_CARD -> { cardPanel.newCard(); invalidateOptionsMenu(); return true }
            MENU_RENAME_CARD -> { cardPanel.renameCard(); return true }
            MENU_DELETE_CARD -> { cardPanel.deleteCard(); invalidateOptionsMenu(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showTab(id: Int) {
        tab = id
        val order = listOf(R.id.nav_solution, R.id.nav_table, R.id.nav_card, R.id.nav_calibrate)
        val panes = listOf(R.id.solutionScroll, R.id.tableScroll, R.id.cardScroll, R.id.calibrateScroll)
        order.forEachIndexed { i, tabId -> findViewById<View>(panes[i]).visibility = if (tabId == id) View.VISIBLE else View.GONE }
        if (shownTab != 0 && shownTab != id) {
            Motion.tabIn(findViewById(panes[order.indexOf(id).coerceAtLeast(0)]), if (order.indexOf(id) > order.indexOf(shownTab)) 1 else -1)
        }
        shownTab = id
        supportActionBar?.title = getString(when (id) {
            R.id.nav_table -> R.string.calc_table
            R.id.nav_card -> R.string.calc_target_card
            R.id.nav_calibrate -> R.string.calc_calibrate
            else -> R.string.calc_solution
        })
        invalidateOptionsMenu()
        when (id) {
            R.id.nav_card -> { busy(false); cardPanel.render() }
            R.id.nav_calibrate -> { busy(false); calibrate.render() }
            else -> compute()
        }
    }

    private fun compute() {
        val r = rifle ?: return; val l = load ?: return; val b = bullet ?: return
        val gen = ++generation
        CalcGuard.problem(r, l, b)?.let { msg ->
            busy(false)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Can't calculate").setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).setOnDismissListener { finish() }.show()
            return
        }
        val s = shot; val st = settings; val table = tab == R.id.nav_table
        val el = l
        busy(true)
        val maxR = st.tableMaxYd.coerceIn(50.0, 5000.0)
        val stepR = st.tableStepYd.coerceIn(1.0, 1000.0).coerceAtLeast(maxR / MAX_TABLE_ROWS)
        worker.execute {
            val result = runCatching {
                if (table) Engine.solve(r, el, b, s, maxRangeYd = maxR,
                    stepYd = stepR, extraRangesYd = listOf(s.distanceYd, l.zeroRangeYd))
                else Engine.solve(r, el, b, s, maxRangeYd = s.distanceYd, stepYd = s.distanceYd.coerceAtLeast(1.0))
            }
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                result.onSuccess { sol -> if (table) renderTable(sol, r, l, b, el) else renderSolution(sol, r, l, b, el) }
                    .onFailure { e -> alert("Can't calculate", e.message ?: e.toString()) }
            }
        }
    }

    private fun updateShot(f: ShotInputs.() -> ShotInputs) {
        shot = shot.f()
        store.saveLastShot(shot)
        compute()
    }

    private fun renderSolution(sol: Solution, r: Rifle, l: Load, b: Bullet, el: Load) {
        val st = settings
        val row = sol.at(shot.distanceYd)
        solutionBox.removeAllViews()
        val answers: LinearLayout; val rest: LinearLayout
        if (isLandscape) {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            answers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            rest = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cols.addView(answers, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cols.addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            solutionBox.addView(cols)
        } else { answers = solutionBox; rest = solutionBox }
        val form = Form(this, rest, st)
        if (isLandscape) form.contentWidthPx = resources.displayMetrics.widthPixels / 2

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(12), dp(8), dp(6)) }
        chips.addView(chip("Speed at target", Num.trim(Units.toDisplay(Quantity.VELOCITY, row.velocityFps, st), 0) + " " + Units.label(Quantity.VELOCITY, st)))
        chips.addView(chip("Energy at target", Num.trim(Units.toDisplay(Quantity.ENERGY, row.energyFtLb, st), 0) + " " + Units.label(Quantity.ENERGY, st)))
        chips.addView(chip("Flight time", Num.fixed(row.tofS, 2) + " s"))
        answers.addView(chips)
        answers.addView(TextView(this).apply {
            text = "${dist(shot.distanceYd)} · ${r.name} · ${l.name}"
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
            setPadding(dp(16), 0, dp(16), dp(6))
        })

        val pm = st.usePlusMinus; val prec = st.outputPrecision
        val eu = r.elevationUnit; val wu = r.windageUnit
        val elevVal = Adjust.dial(row.comeUpMil, eu, r.elevationCorrection)
        val elevClicks = clicks(row.comeUpMil, eu, r.elevationClick, r.elevationCorrection)
        val windVal = Adjust.dial(row.windageMil, wu, r.windageCorrection)
        val windClicks = clicks(row.windageMil, wu, r.windageClick, r.windageCorrection)
        val leadVal = milTo(wu, row.leadMil)
        val leadClicks = clicks(row.leadMil, wu, r.windageClick, 1.0)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        answers.addView(grid)
        fun pair(a: View, b2: View?) {
            val stacked = resources.configuration.fontScale >= 1.5f
            val line = LinearLayout(this).apply { orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
            fun lp() = if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            line.addView(a, lp())
            if (b2 != null) line.addView(b2, lp())
            else if (!stacked) line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

            grid.addView(line)
        }
        pair(
            bigCard("ELEVATION", Adjust.text(elevVal, Adjust.Axis.ELEVATION, prec, r.elevationClick, pm), Adjust.unitLabel(eu),
                Adjust.clicksText(elevClicks, Adjust.Axis.ELEVATION, pm), R.color.sol_elevation),
            bigCard(if (shot.windSpeed2Mph > 0) "WINDAGE 1" else "WINDAGE", Adjust.text(windVal, Adjust.Axis.WINDAGE, prec, r.windageClick, pm),
                Adjust.unitLabel(wu), Adjust.clicksText(windClicks, Adjust.Axis.WINDAGE, pm), R.color.sol_windage),
        )
        val w2 = row.windage2Mil
        val w2Card = if (w2 != null && shot.windSpeed2Mph > 0) {
            val v = Adjust.dial(w2, wu, r.windageCorrection)
            bigCard("WINDAGE 2", Adjust.text(v, Adjust.Axis.WINDAGE, prec, r.windageClick, pm), Adjust.unitLabel(wu),
                Adjust.clicksText(clicks(w2, wu, r.windageClick, r.windageCorrection), Adjust.Axis.WINDAGE, pm), R.color.sol_windage2)
        } else null
        if (shot.targetSpeedMph != 0.0 || w2Card != null) {
            pair(bigCard("LEAD (MOVING TARGET)", Adjust.text(leadVal, Adjust.Axis.WINDAGE, prec, r.windageClick, pm), Adjust.unitLabel(wu),
                Adjust.clicksText(leadClicks, Adjust.Axis.WINDAGE, pm), R.color.sol_lead), w2Card)
        }

        form.header("Change the shot")
        form.group {
            custom(stepper("Distance", dist(shot.distanceYd)) { dir ->
                val stepDisp = Units.toDisplay(Quantity.DISTANCE, st.solutionStepYd.coerceAtLeast(0.1), st)
                val cur = Units.toDisplay(Quantity.DISTANCE, shot.distanceYd, st)
                val next = (Math.rint(cur / stepDisp) + dir) * stepDisp
                val yd = Units.fromDisplay(Quantity.DISTANCE, next, st)
                if (yd > 0 && yd <= 5000) updateShot { copy(distanceYd = yd) }
            })
            custom(stepper("Wind speed", Num.trim(Units.toDisplay(Quantity.SPEED, shot.windSpeed1Mph, st), 1) + " " + Units.label(Quantity.SPEED, st)) { dir ->
                val cur = Units.toDisplay(Quantity.SPEED, shot.windSpeed1Mph, st)
                val next = (Math.rint(cur) + dir).coerceAtLeast(0.0)
                updateShot { copy(windSpeed1Mph = Units.fromDisplay(Quantity.SPEED, next, st)) }
            })
            custom(stepper("Wind angle", Num.trim(shot.windAngleDeg, 0) + "° · " + WindText.clock(shot.windAngleDeg)) { dir ->
                val next = WindText.normalize((Math.rint(shot.windAngleDeg / 30.0) + dir) * 30.0)
                updateShot { copy(windAngleDeg = next) }
            })
        }

        val len = Quantity.LENGTH
        fun inches(v: Double) = Num.fixed(Units.toDisplay(len, v, st), if (Units.metric(len, st)) 1 else 2) + " " + Units.label(len, st)
        form.header("Details")
        form.group {
            info("Muzzle velocity", Num.trim(Units.toDisplay(Quantity.VELOCITY, sol.muzzleVelocityFps, st), 0) + " " + Units.label(Quantity.VELOCITY, st),
                sub = if (l.velocityPerDegFps != 0.0) "adjusted for powder temperature" else null)
            info("Drag data used", dragLabel(l, b, el))
            info("Mach at target", Num.fixed(row.mach, 2), sub = "Bullet speed compared with the speed of sound")
            val sgText = Num.fixed(sol.stabilityMuzzle, 2) + when { sol.stabilityMuzzle < 1.0 -> " not stable"; sol.stabilityMuzzle < 1.4 -> " borderline"; else -> "" }
            val sgView = info("Bullet stability (Sg)", sgText)
            if (sol.stabilityMuzzle < 1.4) sgView.setTextColor(ContextCompat.getColor(this@SolutionActivity, R.color.sol_warn))
            info("Slows to Mach 1.2 at", sol.transonicYd?.let { dist(it) } ?: "beyond this distance")
            info("Goes subsonic at", sol.subsonicYd?.let { dist(it) } ?: "beyond this distance")
            info("Bullet path", inches(row.pathIn))
            info("Wind drift", inches(row.windDriftIn))
            if (shot.spinDrift) info("Spin drift", inches(row.spinDriftIn))
            if (shot.aeroJump) info("Aero jump", Num.fixed(milTo(eu, row.aeroJumpMil), 2) + " " + Adjust.unitLabel(eu))
            if (shot.coriolis) {
                info("Coriolis vertical", inches(row.coriolisVertIn))
                info("Coriolis horizontal", inches(row.coriolisHorizIn))
            }
            if (shot.cantDeg != 0.0) info("Cant", Num.trim(shot.cantDeg, 1) + "°")
        }
    }

    private fun dragLabel(l: Load, b: Bullet, el: Load): String {
        val src = BcSelection.detect(el, b)
        val season = BcSelection.resolvedSeason(el, b, shot.atmosphere.temperatureF)
        val base = when {
            src == DragSource.CURVE -> "Measured drag curve"
            season != null -> "G7 $season rows" + if (el.bcSeason == "auto") " (auto at ${Num.trim(Units.toDisplay(Quantity.TEMPERATURE, shot.atmosphere.temperatureF, settings), 0)}${Units.label(Quantity.TEMPERATURE, settings)})" else ""
            else -> {
                val rows = el.bcPointsOverride ?: b.bcPoints
                val m = el.dragModelOverride ?: b.dragModel
                if (rows.size == 1) "$m ${Num.trim(rows[0].bc, 3)}" else "$m, ${rows.size} BCs"
            }
        }
        return if (l.dragScaleFactor != 1.0) "$base × ${Num.trim(l.dragScaleFactor, 3)}" else base
    }

    private fun dist(yd: Double): String {
        val st = settings
        return Num.trim(Units.toDisplay(Quantity.DISTANCE, yd, st), 0) + " " + Units.label(Quantity.DISTANCE, st)
    }

    private fun chip(label: String, value: String): View {
        val card = MaterialCardView(this).apply { radius = dp(12).toFloat(); cardElevation = 0f; strokeWidth = 0 }
        card.setCardBackgroundColor(card.themeColor(MR.attr.colorSecondaryContainer))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        col.addView(TextView(this).apply {
            text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColor(MR.attr.colorOnSecondaryContainer))
        })
        col.addView(TextView(this).apply {
            text = value; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(MR.attr.colorOnSecondaryContainer))
        })
        card.addView(col)
        card.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(4); marginEnd = dp(4)
        }
        return card
    }

    private fun bigCard(caption: String, value: String, unit: String, clicks: String, colorRes: Int): View {
        val color = ContextCompat.getColor(this, colorRes)
        val card = MaterialCardView(this).apply { radius = dp(14).toFloat(); cardElevation = 0f; strokeWidth = dp(1) }
        card.setCardBackgroundColor(card.themeColor(MR.attr.colorSurfaceContainerHigh))
        card.strokeColor = card.themeColor(MR.attr.colorOutlineVariant)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(View(this).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        col.addView(TextView(this).apply {
            text = caption; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
            setPadding(dp(4), dp(8), dp(4), 0)
        })
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), 0, dp(6), 0) }
        val number = TextView(this).apply {
            text = value; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); maxLines = 1; includeFontPadding = false
        }
        line.addView(number)
        lastAnswers.put(caption, value)?.let { before -> Motion.settle(number, before, value) }
        line.addView(TextView(this).apply {
            text = unit; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(6), 0, 0, 0)
            setTextColor(themeColor(MR.attr.colorOnSurface))
        })
        col.addView(line)
        col.addView(TextView(this).apply {
            text = WindWords.direction(value) + "$clicks clicks"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(themeColor(MR.attr.colorOnSurface))
            setPadding(dp(4), dp(2), dp(4), dp(10))
        })
        card.addView(col)
        card.contentDescription = "$caption ${WindWords.direction(value)}$value $unit, $clicks clicks"
        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        wrap.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun stepper(label: String, value: String, onStep: (Int) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Form.stepButton(this, "−", "Decrease $label") { onStep(-1) }, LinearLayout.LayoutParams(dp(60), dp(60)))
        row.addView(TextView(this).apply {
            text = value; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(MR.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Form.stepButton(this, "+", "Increase $label") { onStep(1) }, LinearLayout.LayoutParams(dp(60), dp(60)))
        col.addView(row)
        return col
    }

    private fun renderTable(sol: Solution, r: Rifle, l: Load, b: Bullet, el: Load) {
        tableSolution = sol
        val st = settings
        tableBox.removeAllViews()
        val pm = st.usePlusMinus; val prec = st.outputPrecision
        val lenU = Units.label(Quantity.LENGTH, st)
        val metricLen = Units.metric(Quantity.LENGTH, st)
        val colW = intArrayOf(78, 78, 74, 78, 74, 72, 80, 62)
        val totalW = colW.sum()
        val text = StringBuilder()

        fun block(title: String, pairs: List<Pair<String, String>>) {
            tableBox.addView(TextView(this).apply {
                this.text = title.uppercase(); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(4), 0, dp(4))
                setBackgroundColor(themeColor(MR.attr.colorSurfaceContainerHighest))
            }, LinearLayout.LayoutParams(dp(totalW), ViewGroup.LayoutParams.WRAP_CONTENT))
            text.append("== ").append(title).append(" ==\n")
            pairs.chunked(2).forEach { two ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                two.forEach { (k, v) ->
                    line.addView(cell(k, totalW / 4, bold = true, start = true))
                    line.addView(cell(v, totalW / 4, start = true))
                    text.append(k).append(": ").append(v).append("   ")
                }
                text.append('\n')
                tableBox.addView(line)
            }
        }
        val atmo = shot.atmosphere
        block("Firearm: ${r.name}", listOf(
            "Twist" to "1:${Num.trim(r.twistIn, 2)}in ${if (r.twistDirection.name == "LEFT") "L" else "R"}",
            "Sight height" to Num.trim(Units.toDisplay(Quantity.LENGTH, r.sightHeightIn, st), 2) + " $lenU",
            "Sight offset" to Num.trim(Units.toDisplay(Quantity.LENGTH, r.sightOffsetIn, st), 2) + " $lenU",
            "CF (elev/wind)" to "${Num.trim(r.elevationCorrection, 3)}/${Num.trim(r.windageCorrection, 3)}",
        ))
        block("Ammo: ${l.name}", listOf(
            "Bullet" to "${Num.trim(b.weightGr, 1)}gr ${Num.trim(b.diameterIn, 3)}\"",
            "Drag" to dragLabel(l, b, el),
            "Muzzle vel" to Num.trim(Units.toDisplay(Quantity.VELOCITY, sol.muzzleVelocityFps, st), 0) + " " + Units.label(Quantity.VELOCITY, st),
            "Zero range" to dist(l.zeroRangeYd),
        ))
        block("Atmosphere", listOf(
            "Altitude" to Num.trim(Units.toDisplay(Quantity.ALTITUDE, atmo.altitudeFt, st), 0) + " " + Units.label(Quantity.ALTITUDE, st),
            "Temperature" to Num.trim(Units.toDisplay(Quantity.TEMPERATURE, atmo.temperatureF, st), 1) + Units.label(Quantity.TEMPERATURE, st),
            "Pressure" to Num.trim(Units.toDisplay(Quantity.PRESSURE, atmo.pressureInHg, st), 2) + " " + Units.label(Quantity.PRESSURE, st) +
                if (atmo.pressureIsAbsolute) " abs" else "",
            "Humidity" to Num.trim(atmo.humidityPct, 0) + "%",
        ))
        block("Shot data", listOf(
            "Distance" to dist(shot.distanceYd),
            "Look angle" to Num.trim(shot.lookAngleDeg, 1) + "°",
            "Wind" to Num.trim(Units.toDisplay(Quantity.SPEED, shot.windSpeed1Mph, st), 1) + " " + Units.label(Quantity.SPEED, st) +
                " @ " + Num.trim(shot.windAngleDeg, 0) + "°",
            "Target" to if (shot.targetSpeedMph == 0.0) "Stationary" else
                Num.trim(Units.toDisplay(Quantity.SPEED, abs(shot.targetSpeedMph), st), 1) + " " + Units.label(Quantity.SPEED, st) +
                    if (shot.targetSpeedMph > 0) " R" else " L",
            "Spin drift" to if (shot.spinDrift) "On" else "Off",
            "Coriolis" to if (shot.coriolis) "On (lat ${Num.trim(shot.latitudeDeg, 1)}°, az ${Num.trim(shot.azimuthDeg, 0)}°)" else "Off",
        ) + (if (shot.localGravity) listOf(
            "Gravity" to "${Num.fixed(Engine.gravityFor(shot), 3)} m/s² (local, lat ${Num.trim(shot.latitudeDeg, 1)}°)",
        ) else emptyList()
        ))

        val eu = Adjust.unitLabel(r.elevationUnit); val wu = Adjust.unitLabel(r.windageUnit)
        val heads = listOf("Range\n(${Units.label(Quantity.DISTANCE, st)})", "Path\n($lenU)", "Path\n($eu)", "Drift\n($lenU)",
            "Drift\n($wu)", "Vel\n(${Units.label(Quantity.VELOCITY, st)})", "Energy\n(${Units.label(Quantity.ENERGY, st)})", "TOF\n(s)")
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(themeColor(MR.attr.colorSurfaceContainerHighest)) }
        heads.forEachIndexed { i, h -> headRow.addView(cell(h, colW[i], bold = true)) }
        tableBox.addView(headRow)
        text.append('\n').append(heads.joinToString("\t") { it.replace('\n', ' ') }).append('\n')

        var rows = sol.rows.filter { it.rangeYd > 0 }
        if (st.tableSortDescending) rows = rows.reversed()
        val stripe = tableBox.themeColor(MR.attr.colorSurfaceContainer)
        val hilite = tableBox.themeColor(MR.attr.colorPrimaryContainer)
        rows.forEachIndexed { idx, row ->
            val isTarget = abs(row.rangeYd - shot.distanceYd) < 1e-6
            val isZero = abs(row.rangeYd - l.zeroRangeYd) < 1e-6
            val values = listOf(
                (if (isZero) "◎ " else "") + Num.trim(Units.toDisplay(Quantity.DISTANCE, row.rangeYd, st), 0),
                Num.fixed(Units.toDisplay(Quantity.LENGTH, row.pathIn, st), if (metricLen) 1 else 2),
                Adjust.text(Adjust.dial(row.comeUpMil, r.elevationUnit, r.elevationCorrection), Adjust.Axis.ELEVATION, prec, r.elevationClick, pm),
                Num.fixed(Units.toDisplay(Quantity.LENGTH, row.windageIn, st), if (metricLen) 1 else 2),
                Adjust.text(Adjust.dial(row.windageMil, r.windageUnit, r.windageCorrection), Adjust.Axis.WINDAGE, prec, r.windageClick, pm),
                Num.trim(Units.toDisplay(Quantity.VELOCITY, row.velocityFps, st), 0),
                Num.trim(Units.toDisplay(Quantity.ENERGY, row.energyFtLb, st), 0),
                Num.fixed(row.tofS, 2),
            )
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                when { isTarget -> setBackgroundColor(hilite); idx % 2 == 1 -> setBackgroundColor(stripe) }
            }
            values.forEachIndexed { i, v -> line.addView(cell(v, colW[i], bold = isTarget)) }
            tableBox.addView(line)
            text.append(values.joinToString("\t")).append('\n')
        }
        tableText = text.toString()
        invalidateOptionsMenu()
    }

    private fun cell(t: String, wDp: Int, bold: Boolean = false, start: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = if (start) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(5), dp(4), dp(5))
        layoutParams = LinearLayout.LayoutParams(dp(wDp), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_CALIBRATE = "calibrate"
        internal val worker = Executors.newSingleThreadExecutor()
        private const val MENU_NEW_CARD = 201
        private const val MENU_RENAME_CARD = 202
        private const val MENU_DELETE_CARD = 203
        private const val MAX_TABLE_ROWS = 400.0
    }
}
