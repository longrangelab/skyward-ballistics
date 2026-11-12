package com.longrange.dragcurve.ui.calc

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.TwistDirection

class RifleEditActivity : CalcActivity() {
    private lateinit var draft: Rifle
    private lateinit var initial: String
    private var isNew = true
    private lateinit var form: Form

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        val id = intent.getStringExtra(EXTRA_ID)
        val existing = id?.let { i -> store.rifles().firstOrNull { it.id == i } }
        isNew = existing == null
        val start = existing ?: Rifle(id = newId("rifle"), name = "")
        initial = savedInstanceState?.getString("initial") ?: DraftCodec.rifle(start)
        draft = savedInstanceState?.getString("draft")?.let { DraftCodec.rifle(it) } ?: start
        setupToolbar(getString(if (isNew) R.string.calc_add_firearm else R.string.calc_edit_firearm))
        form = contentForm()
        guardUnsaved { DraftCodec.rifle(draft) != initial }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", DraftCodec.rifle(draft))
        outState.putString("initial", initial)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.calc_editor, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_save) { save(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val y = scroll.scrollY
        form.clear()
        val r = draft
        val units = listOf(AngleUnit.MIL, AngleUnit.MOA, AngleUnit.IPHY)
        val unitNames = listOf("MIL", "MOA", "IPHY (in per 100 yd)")

        form.header("Name")
        form.group { text(null, r.name, { draft = draft.copy(name = it) }, hint = "Firearm name") }

        form.header("Firearm Data")
        form.group {
            number("Barrel Twist", Quantity.BULLET_LENGTH, r.twistIn, { v -> if (v != null && v > 0) draft = draft.copy(twistIn = v) },
                decimals = 2, sub = "Inches for one full turn of the rifling, e.g. 8")
            choice("Twist Direction", listOf("Right", "Left"), if (r.twistDirection == TwistDirection.LEFT) 1 else 0) { i ->
                draft = draft.copy(twistDirection = if (i == 1) TwistDirection.LEFT else TwistDirection.RIGHT)
            }
            number("Sight Height", Quantity.LENGTH, r.sightHeightIn, { v -> if (v != null && v >= 0) draft = draft.copy(sightHeightIn = v) }, sub = "Centre of the scope above the centre of the barrel")
            number("Sight Offset", Quantity.LENGTH, r.sightOffsetIn, { v -> if (v != null) draft = draft.copy(sightOffsetIn = v) },
                signed = true, sub = "Usually 0. + when the scope sits right of the barrel")
            number("Barrel Length", Quantity.BULLET_LENGTH, r.barrelLengthIn, { v -> draft = draft.copy(barrelLengthIn = v?.takeIf { it > 0 }) },
                decimals = 1, hint = "optional")
            text("Cartridge", r.cartridge, { draft = draft.copy(cartridge = it.trim()) }, hint = "optional, e.g. 6.5 Creedmoor")
        }

        form.header("Scope Adjustments")
        var elevClickRow: Form.NumberRow? = null
        var windClickRow: Form.NumberRow? = null
        form.group {
            choice("Elevation Unit", unitNames, units.indexOf(r.elevationUnit)) { i ->
                val u = units[i]
                draft = draft.copy(elevationUnit = u, elevationClick = defaultClick(u, draft.elevationClick))
                elevClickRow?.set(draft.elevationClick)
            }
            elevClickRow = number("Elevation Click", Quantity.PLAIN, r.elevationClick, { v -> if (v != null && v > 0) draft = draft.copy(elevationClick = v) },
                decimals = 4, sub = "turret value per click, e.g. 0.1 MIL, 0.25 MOA")
            choice("Windage Unit", unitNames, units.indexOf(r.windageUnit)) { i ->
                val u = units[i]
                draft = draft.copy(windageUnit = u, windageClick = defaultClick(u, draft.windageClick))
                windClickRow?.set(draft.windageClick)
            }
            windClickRow = number("Windage Click", Quantity.PLAIN, r.windageClick, { v -> if (v != null && v > 0) draft = draft.copy(windageClick = v) },
                decimals = 4)
        }

        form.header("Scope correction (optional)")
        form.group {
            number("Elevation Correction", Quantity.PLAIN, r.elevationCorrection, { v -> if (v != null && v > 0) draft = draft.copy(elevationCorrection = v) }, decimals = 4)
            number("Windage Correction", Quantity.PLAIN, r.windageCorrection, { v -> if (v != null && v > 0) draft = draft.copy(windageCorrection = v) }, decimals = 4)
        }
        form.note("Correction factors multiply the calculated adjustment (dial value and clicks) to compensate for scope inaccuracies. Use 1.0 unless you know your scope tracks differently.")

        if (!isNew) {
            form.header(getString(R.string.calc_round_count))
            form.group {
                val list = store.shotRecords(r.id)
                nav(getString(R.string.calc_round_count), ShotText.count(list.sumOf { it.count }),
                    sub = ShotText.summary(this@RifleEditActivity, list)?.substringAfter(" · ")?.replaceFirstChar { it.uppercase() } ?: "Log the rounds you fire, with the date and time") {
                    ShotCounterActivity.open(this@RifleEditActivity, r.id)
                }
            }
        }

        form.header("Notes")
        form.group { text(null, r.notes, { draft = draft.copy(notes = it) }, hint = "Notes", multiline = true) }
        if (EmpiricalHook.isEmpirical(r.externalRef)) form.note("Linked to Empirical Precision. A refresh from the backup may overwrite edits to the name, twist, sight height, barrel length and cartridge.")
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun defaultClick(u: AngleUnit, cur: Double): Double = when (u) {
        AngleUnit.MIL -> if (cur in listOf(0.25, 0.125, 0.5)) 0.1 else cur
        AngleUnit.MOA, AngleUnit.IPHY -> if (cur in listOf(0.1, 0.05)) 0.25 else cur
    }

    private fun save() {
        currentFocus?.let { Form.hideKeyboard(it) }
        val r = draft.copy(name = draft.name.trim())
        when {
            r.name.isEmpty() -> return alert("Missing name", "Give the firearm a profile name.")
            r.twistIn <= 0 -> return alert("Barrel twist", "Enter the barrel twist (inches per turn).")
            r.elevationClick <= 0 || r.windageClick <= 0 -> return alert("Click value", "Click values must be greater than 0.")
        }
        store.saveRifle(r)
        if (store.selection().first == null) store.saveSelection(r.id, null)
        initial = DraftCodec.rifle(r); draft = r
        finish()
    }

    companion object { const val EXTRA_ID = "id" }
}
