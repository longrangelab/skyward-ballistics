package com.longrange.dragcurve.ui.calc

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel

class BulletEditActivity : CalcActivity() {
    private lateinit var form: Form
    private lateinit var b: Bullet
    private var rows: MutableList<RowDraft> = mutableListOf()
    private var readOnly = false
    private var isNew = true
    private var moreOpen = false
    private lateinit var initial: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        val existing = intent.getStringExtra(EXTRA_ID)?.let { store.bullet(it) }
        isNew = existing == null
        if (savedInstanceState != null) {
            b = DraftCodec.bullet(savedInstanceState.getString("b")!!)
            rows = BcRows.decode(savedInstanceState.getString("rows")) ?: mutableListOf()
            initial = savedInstanceState.getString("initial")!!
            moreOpen = savedInstanceState.getBoolean("moreOpen")
        } else {
            val copied = intent.getStringExtra(EXTRA_COPY_FROM)?.let { store.bullet(it) }
                ?.let { BulletBook.copyOf(it, newId("bullet"), store.userBullets().map { u -> u.name }) }
            b = existing ?: copied ?: Bullet(id = newId("bullet"), name = "", diameterIn = 0.308, weightGr = 175.0, dragModel = DragModel.G7)
            rows = BcRows.drafts(b.bcPoints)
            initial = if (copied != null) "" else snapshot()
            moreOpen = b.noseLengthIn != null
        }
        readOnly = b.source == "library"
        setupToolbar(when { readOnly -> BulletSearch.title(b); isNew -> getString(R.string.calc_add_bullet); else -> getString(R.string.calc_edit_bullet) })
        form = contentForm()
        if (!readOnly) guardUnsaved { snapshot() != initial }
        render()
    }

    private fun snapshot() = DraftCodec.bullet(b) + BcRows.encode(rows)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("b", DraftCodec.bullet(b))
        outState.putString("rows", BcRows.encode(rows))
        outState.putString("initial", initial)
        outState.putBoolean("moreOpen", moreOpen)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (readOnly) menu.add(0, MENU_COPY, 0, "Copy to my bullets")
        else {
            menuInflater.inflate(R.menu.calc_editor, menu)
            if (!isNew) menu.add(0, MENU_DELETE, 1, R.string.calc_delete)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> save()
            MENU_COPY -> {
                val copy = store.saveUserBullet(BulletBook.copyOf(b, newId("bullet"), store.userBullets().map { it.name }))
                startActivity(android.content.Intent(this, BulletEditActivity::class.java).putExtra(EXTRA_ID, copy.id))
                finish()
            }
            MENU_DELETE -> {
                val saved = store.userBullets().firstOrNull { it.id == b.id }
                if (store.deleteBullet(b.id)) {
                    if (saved != null) UndoDelete.offer("Deleted \"${saved.name}\"", com.longrange.dragcurve.model.Libraries(bullets = listOf(saved)))
                    finish()
                } else alert("Bullet in use", "Some ammo still uses this bullet. Change or delete that ammo first.")
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun render() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val y = scroll.scrollY
        form.clear()
        if (readOnly) renderLibrary() else renderEditable()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun renderLibrary() {
        form.header("Bullet")
        form.group {
            info("Maker", b.maker.ifBlank { "—" })
            info("Caliber", b.caliber.ifBlank { "—" })
            if (b.category.isNotBlank()) info("Type", b.category)
            info("Diameter", Num.trim(b.diameterIn, 3) + " in")
            info("Weight", Num.trim(b.weightGr, 1) + " gr")
            info("Length", b.lengthIn?.let { Num.trim(it, 4) + " in" } ?: "—")
            b.noseLengthIn?.let { info("Nose length", Num.trim(it, 4) + " in") }
        }
        if (b.dragCurve.size >= 2) {
            form.header("Measured drag curve")
            form.group { custom(DragCurveView(this@BulletEditActivity, b.dragCurve)); custom(curveCaption(this@BulletEditActivity, b.dragCurve)) }
        }
        if (b.bcPoints.isNotEmpty()) {
            form.header("All-season BC rows (${b.dragModel.takeIf { it != DragModel.CUSTOM } ?: DragModel.G7})")
            form.group { info("BC  ≥ min vel", bcRowsText(b.bcPoints, settings)) }
        }
        listOf("cold", "mild", "hot").forEach { s ->
            b.seasonalBcPoints[s]?.takeIf { it.isNotEmpty() }?.let { r ->
                form.header("$s-weather BC rows (G7)")
                form.group { info("BC  ≥ min vel", bcRowsText(r, settings)) }
            }
        }
        originText(b)?.let { form.header("Where this data came from"); form.note(it) }
        if (b.notes.isNotBlank()) { form.header("Notes"); form.note(b.notes) }
        form.note("Library bullets are read-only. Use ⋮ → Copy to my bullets to make an editable copy.")
    }

    private fun mark(row: Form.NumberRow, problem: String?, helper: String) {
        val sub = row.subView() ?: return
        sub.text = problem ?: helper
        sub.setTextColor(if (problem != null) androidx.core.content.ContextCompat.getColor(this, R.color.sol_warn)
            else sub.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun renderEditable() {
        form.header("Bullet")
        form.group {
            text("Name", b.name, { b = b.copy(name = it) }, hint = "e.g. 140 ELD-M")
            text("Maker", b.maker, { b = b.copy(maker = it) }, hint = "optional")
            text("Caliber", b.caliber, { b = b.copy(caliber = it) }, hint = "optional, e.g. 6.5 mm")
        }
        form.header("Dimensions")
        form.group {
            val dHelp = "Across the bullet, from the box (for example 0.264)"
            var dia: Form.NumberRow? = null
            dia = number("Diameter", Quantity.BULLET_LENGTH, b.diameterIn.takeIf { it > 0 }, { v ->
                b = b.copy(diameterIn = v ?: 0.0)
                dia?.let { mark(it, BulletBook.diameterProblem(v), dHelp) }
            }, sub = dHelp)
            mark(dia, BulletBook.diameterProblem(b.diameterIn), dHelp)
            val wHelp = "Bullet weight in grains, from the box"
            var wt: Form.NumberRow? = null
            wt = number("Weight", Quantity.WEIGHT, b.weightGr.takeIf { it > 0 }, { v ->
                b = b.copy(weightGr = v ?: 0.0)
                wt?.let { mark(it, BulletBook.weightProblem(v), wHelp) }
            }, sub = wHelp)
            mark(wt, BulletBook.weightProblem(b.weightGr), wHelp)
            number("Length", Quantity.BULLET_LENGTH, b.lengthIn, { v -> b = b.copy(lengthIn = v?.takeIf { it > 0 }) }, hint = "optional",
                sub = "Used for stability and spin drift. Estimated if left blank")
        }
        form.foldable("More", { if (b.noseLengthIn != null) "Nose length ${Num.trim(b.noseLengthIn!!, 3)} in" else "Nose length (optional)" },
            moreOpen, { open -> moreOpen = open }) {
            number("Nose length", Quantity.BULLET_LENGTH, b.noseLengthIn, { v -> b = b.copy(noseLengthIn = v?.takeIf { it > 0 }) }, hint = "optional",
                sub = "Tip to where the bullet reaches full diameter. Only used for aerodynamic jump")
        }
        val hasCurve = b.dragCurve.size >= 2
        val models = if (hasCurve) listOf(DragModel.CUSTOM, DragModel.G1, DragModel.G7) else listOf(DragModel.G1, DragModel.G7)
        val names = models.map { if (it == DragModel.CUSTOM) "Measured drag curve" else it.name }
        form.header("Drag model")
        form.group {
            choice("Drag model", names, models.indexOf(b.dragModel).coerceAtLeast(0),
                sub = "The kind of BC printed on the box: G1 or G7") { i -> b = b.copy(dragModel = models[i]); render() }
        }
        if (b.dragModel == DragModel.CUSTOM && hasCurve) {
            form.group { custom(DragCurveView(this@BulletEditActivity, b.dragCurve)); custom(curveCaption(this@BulletEditActivity, b.dragCurve)) }
        }
        form.header("Ballistic coefficient (BC)")
        var bcMessage: android.widget.TextView? = null
        val bcHelp = "The BC applies at and above this speed; one row must be 0."
        fun showBc() {
            val tv = bcMessage ?: return
            val problem = BulletBook.bcProblem(rows)
            tv.text = problem ?: bcHelp
            tv.setTextColor(if (problem != null) androidx.core.content.ContextCompat.getColor(this, R.color.sol_warn)
                else tv.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        form.group { custom(bcRowsView(this@BulletEditActivity, settings, rows, onChange = { showBc() }) { render() }) }
        bcMessage = form.note(bcHelp)
        showBc()
        form.note(getString(R.string.calc_bc_hint) + if (b.dragModel == DragModel.CUSTOM) " Optional with a measured curve." else "")
        form.header("Notes")
        form.group { text(null, b.notes, { b = b.copy(notes = it) }, hint = "Notes", multiline = true) }
        originText(b)?.let { form.header("Where this data came from"); form.note(it) }
    }

    private fun originText(b: Bullet): String? {
        val p = b.provenance
        val lines = listOfNotNull(
            b.collection.takeIf { it.isNotBlank() }?.let { "Library: $it" },
            p?.source?.takeIf { it.isNotBlank() }?.let { "Source: $it" },
            p?.method?.takeIf { it.isNotBlank() }?.let { "Method: $it" },
            p?.date?.takeIf { it.isNotBlank() }?.let { "Date: $it" },
            p?.license?.takeIf { it.isNotBlank() }?.let { "Licence / terms: $it" },
            p?.url?.takeIf { it.isNotBlank() }?.let { "From: $it" },
        )
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun save() {
        currentFocus?.let { Form.hideKeyboard(it) }
        var out = b.copy(name = b.name.trim(), maker = b.maker.trim(), caliber = b.caliber.trim())
        if (out.name.isEmpty()) return alert("Missing name", "Give the bullet a name.")
        BulletBook.diameterProblem(out.diameterIn)?.let { return alert("Diameter", "$it.") }
        BulletBook.weightProblem(out.weightGr)?.let { return alert("Weight", "$it.") }
        BulletBook.bcProblem(rows)?.let { return alert("Ballistic coefficient", "$it.") }
        val blank = rows.all { it.bc == null && it.minVelFps == null }
        if (out.dragModel == DragModel.CUSTOM && blank) out = out.copy(bcPoints = emptyList())
        else {
            val (r, err) = BcRows.finish(rows)
            if (r == null) return alert("Ballistic coefficient", err ?: "Check the BC rows.")
            out = out.copy(bcPoints = r)
        }
        val saved = store.saveUserBullet(out)
        initial = snapshot()
        if (intent.getBooleanExtra(EXTRA_RETURN, false)) {
            setResult(RESULT_OK, android.content.Intent().putExtra(BulletLibraryActivity.RESULT_ID, saved.id))
        }
        finish()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_COPY_FROM = "copyFrom"
        const val EXTRA_RETURN = "returnId"
        private const val MENU_COPY = 101
        private const val MENU_DELETE = 102
    }
}
