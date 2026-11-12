package com.longrange.dragcurve.ui.calc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.exchange.Ep
import com.longrange.dragcurve.exchange.EmpiricalLink
import com.longrange.dragcurve.exchange.EpCatalog
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Recipe

class LoadEditActivity : CalcActivity() {
    private lateinit var form: Form
    private lateinit var load: Load
    private lateinit var bullet: Bullet
    private lateinit var src: DragSource
    private var editModel = DragModel.G7
    private var rows: MutableList<RowDraft> = mutableListOf()
    private var isNew = true
    private var bulletIsNew = false
    private var recipeOpen = false
    private lateinit var initial: String

    private val pickBullet = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data?.getBooleanExtra(BulletLibraryActivity.RESULT_MANUAL, false) == true) {
            enterOwnBc(); return@registerForActivityResult
        }
        val id = res.data?.getStringExtra(BulletLibraryActivity.RESULT_ID)
        val b = id?.let { store.bullet(it) }
        if (res.resultCode == Activity.RESULT_OK && b != null) {
            bullet = b; bulletIsNew = false
            load = load.copy(bulletId = b.id)
            if (load.name.isBlank()) load = load.copy(name = BulletSearch.title(b))
            resetDrag(BcSelection.default(b))
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        val rifleId = intent.getStringExtra(EXTRA_RIFLE)
        val existing = intent.getStringExtra(EXTRA_ID)?.let { id -> store.loads(null).firstOrNull { it.id == id } }
        isNew = existing == null
        if (existing == null && rifleId == null) { finish(); return }

        if (savedInstanceState != null) {
            load = DraftCodec.load(savedInstanceState.getString("load")!!)
            bullet = DraftCodec.bullet(savedInstanceState.getString("bullet")!!)
            src = DragSource.valueOf(savedInstanceState.getString("src")!!)
            editModel = DragModel.valueOf(savedInstanceState.getString("model")!!)
            rows = BcRows.decode(savedInstanceState.getString("rows")) ?: mutableListOf()
            bulletIsNew = savedInstanceState.getBoolean("bulletIsNew")
            recipeOpen = savedInstanceState.getBoolean("recipeOpen")
            initial = savedInstanceState.getString("initial")!!
        } else {
            val fromLibrary = intent.getStringExtra(EXTRA_BULLET)?.let { store.bullet(it) }
            when {
                existing != null -> {
                    load = existing
                    val b = store.bullet(existing.bulletId)
                    if (b != null) { bullet = b; bulletIsNew = false }
                    else { bullet = newBullet(existing.bulletId); bulletIsNew = true }
                    src = if (b == null) DragSource.CUSTOM else BcSelection.detect(existing, b)
                    editModel = existing.dragModelOverride?.takeIf { it != DragModel.CUSTOM }
                        ?: bullet.dragModel.takeIf { it != DragModel.CUSTOM } ?: DragModel.G7
                    rows = BcRows.drafts(existing.bcPointsOverride ?: bullet.bcPoints)
                }
                fromLibrary != null -> {
                    bullet = fromLibrary; bulletIsNew = false
                    load = Load(id = newId("load"), rifleId = rifleId!!, name = BulletSearch.title(fromLibrary),
                        bulletId = fromLibrary.id, muzzleVelocityFps = 2800.0)
                    resetDrag(BcSelection.default(fromLibrary))
                }
                else -> {
                    bullet = newBullet(newId("bullet")); bulletIsNew = true
                    load = Load(id = newId("load"), rifleId = rifleId!!, name = "", bulletId = bullet.id, muzzleVelocityFps = 2800.0)
                    src = DragSource.BULLET; editModel = DragModel.G7; rows = mutableListOf(RowDraft(null, 0.0))
                }
            }
            initial = snapshot()
            recipeOpen = load.recipe != null
        }
        setupToolbar(getString(if (isNew) R.string.calc_add_ammo else R.string.calc_edit_ammo))
        form = contentForm()
        guardUnsaved { snapshot() != initial }
        render()
    }

    private fun newBullet(id: String) = Bullet(id = id, name = "", diameterIn = 0.308, weightGr = 175.0, dragModel = DragModel.G7)

    private fun snapshot() = DraftCodec.load(load) + DraftCodec.bullet(bullet) + src.name + editModel.name + BcRows.encode(rows)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("load", DraftCodec.load(load))
        outState.putString("bullet", DraftCodec.bullet(bullet))
        outState.putString("src", src.name)
        outState.putString("model", editModel.name)
        outState.putString("rows", BcRows.encode(rows))
        outState.putBoolean("bulletIsNew", bulletIsNew)
        outState.putBoolean("recipeOpen", recipeOpen)
        outState.putString("initial", initial)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.calc_editor, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_save) { save(); return true }
        return super.onOptionsItemSelected(item)
    }

    private val isLibrary get() = bullet.source == "library"

    private val rowsEditable get() = src == DragSource.CUSTOM || (src == DragSource.BULLET && !isLibrary)

    private fun resetDrag(s: DragSource) {
        src = s
        editModel = bullet.dragModel.takeIf { it != DragModel.CUSTOM } ?: DragModel.G7
        rows = BcRows.drafts(shownRows(s))
    }

    private fun shownRows(s: DragSource) = when (s) {
        DragSource.COLD, DragSource.MILD, DragSource.HOT -> bullet.seasonalBcPoints[s.season].orEmpty()
        else -> bullet.bcPoints
    }

    private var tnfRow: TextView? = null
    private var tnfKey: Any? = null
    private val tnfTimer = android.os.Handler(android.os.Looper.getMainLooper())
    private val tnfTick = object : Runnable {
        override fun run() { updateTnf(); tnfTimer.postDelayed(this, 700) }
    }

    override fun onResume() { super.onResume(); tnfTimer.removeCallbacks(tnfTick); tnfTimer.postDelayed(tnfTick, 700) }
    override fun onPause() { tnfTimer.removeCallbacks(tnfTick); super.onPause() }

    private fun draftFlight(): Pair<Load, Bullet>? = runCatching {
        var b = bullet
        if (LoadFlags.mvNeeded(load)) return null
        var finalRows: List<com.longrange.dragcurve.model.BcPoint> = emptyList()
        if (rowsEditable) finalRows = BcRows.finish(rows).first ?: return null
        if (!isLibrary && src == DragSource.BULLET) b = b.copy(dragModel = editModel, bcPoints = finalRows)
        if (!isLibrary && src == DragSource.CUSTOM && b.bcPoints.isEmpty() && b.dragCurve.size < 2) b = b.copy(dragModel = editModel, bcPoints = finalRows)
        BcSelection.apply(load, b, src, editModel, finalRows) to b
    }.getOrNull()

    private fun updateTnf() {
        val row = tnfRow ?: return
        val f = draftFlight()
        val key = f?.let { (l, b) -> Triple(l.muzzleVelocityFps, Triple(l.dragModelOverride, l.bcPointsOverride, l.bcSeason) to l.dragScaleFactor, b.copy(name = "", updatedAt = 0)) }
        if (key == tnfKey && row.text != "…") return
        tnfKey = key
        val v = f?.let { (l, b) -> AmmoTnf.get(l, b) { if (tnfKey == key) { tnfKey = null; updateTnf() } } }
        row.text = when {
            f == null -> "—"
            v == null -> "…"
            else -> String.format(java.util.Locale.US, "%.0f", v.tnf)
        }
        (row.parent as? ViewGroup)?.findViewWithTag<TextView>("sub")?.text =
            if (v != null) AmmoTnf.detail(v) else if (f == null) "Muzzle velocity × G7 BC: enter both to see it" else "Muzzle velocity × G7 BC"
    }

    private fun render() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val y = scroll.scrollY
        form.settings = settings
        form.clear()
        val l = load; val b = bullet

        form.header("Name")
        form.group { text(null, l.name, { load = load.copy(name = it) }, hint = "Ammo Name") }

        form.header("Bullet")
        form.group {
            nav(getString(R.string.calc_bullet), if (bulletIsNew && b.name.isBlank()) "My own BC" else BulletSearch.title(b),
                sub = when {
                    isLibrary -> BulletSearch.sourceLabel(b) + ". Tap to search for another bullet"
                    b.category == EmpiricalLink.EP_BULLET_CATEGORY -> "made from Empirical's single BC. Tap to swap for a library bullet"
                    bulletIsNew && b.name.isBlank() -> "Enter the BC below, or tap to search the bullet library and your bullets"
                    else -> "My bullet. Tap to search for another bullet"
                }) { changeBullet() }
            if (isLibrary) {
                info("Diameter", Num.trim(b.diameterIn, 3) + " in")
                info("Weight", Num.trim(b.weightGr, 1) + " gr")
                info("Length", b.lengthIn?.let { Num.trim(it, 3) + " in" } ?: "estimated")
            } else {
                number("Bullet Diameter", Quantity.BULLET_LENGTH, b.diameterIn, { v -> if (v != null && v > 0) bullet = bullet.copy(diameterIn = v) })
                number("Bullet Weight", Quantity.WEIGHT, b.weightGr, { v -> if (v != null && v > 0) bullet = bullet.copy(weightGr = v) })
                number("Bullet Length", Quantity.BULLET_LENGTH, b.lengthIn, { v -> bullet = bullet.copy(lengthIn = v?.takeIf { it > 0 }) },
                    hint = "optional", sub = "for stability / spin drift")
                number("Nose Length", Quantity.BULLET_LENGTH, b.noseLengthIn, { v -> bullet = bullet.copy(noseLengthIn = v?.takeIf { it > 0 }) },
                    hint = "optional", sub = "only for aerodynamic jump")
            }
        }

        form.header("Speed and drag")
        form.group {
            number("Muzzle Velocity", Quantity.VELOCITY, l.muzzleVelocityFps.takeIf { it > 0 },
                { v -> if (v != null && v > 0) load = load.copy(muzzleVelocityFps = v, muzzleVelocitySource = "") },
                hint = "needed",
                sub = when {
                    LoadFlags.mvNeeded(l) -> "Needed: enter the bullet speed leaving the barrel (chronograph or ammo box)"
                    l.muzzleVelocitySource.isNotBlank() -> "From " + l.muzzleVelocitySource
                    else -> "Bullet speed leaving the barrel (chronograph or ammo box)"
                })
            val choices = BcSelection.choices(b)
            choice("Drag data", choices.map { it.label }, choices.indexOf(src).coerceAtLeast(0)) { i ->
                val s = choices[i]
                if (s == DragSource.CUSTOM && src != DragSource.CUSTOM) {
                    editModel = (if (src == DragSource.BULLET) bullet.dragModel else DragModel.G7).takeIf { it != DragModel.CUSTOM } ?: DragModel.G7
                    rows = BcRows.drafts(if (src == DragSource.CURVE || src == DragSource.AUTO) bullet.bcPoints else shownRows(src))
                    src = s
                } else resetDrag(s)
                render()
            }
            if (rowsEditable) {
                choice("Drag Model", listOf("G1", "G7"), if (editModel == DragModel.G1) 0 else 1) { i ->
                    editModel = if (i == 0) DragModel.G1 else DragModel.G7
                }
            }
            number("Drag Scale Factor", Quantity.PLAIN, l.dragScaleFactor, { v -> if (v != null && v > 0) load = load.copy(dragScaleFactor = v) },
                decimals = 4, sub = "1.0 = as measured. Calibrate can set this for you")
            tnfRow = info("TNF", "—", sub = "Muzzle velocity × G7 BC")
            tnfKey = null
            updateTnf()
        }

        when {
            rowsEditable -> {
                form.header("Ballistic Coefficient")
                form.group { custom(bcRowsView(this@LoadEditActivity, settings, rows) { render() }) }
                form.note(getString(R.string.calc_bc_hint))
            }
            src == DragSource.CURVE && b.dragCurve.size >= 2 -> {
                form.header("Measured Drag Curve")
                form.group {
                    custom(DragCurveView(this@LoadEditActivity, b.dragCurve))
                    custom(curveCaption(this@LoadEditActivity, b.dragCurve))
                }
                form.note("The measured curve is the most accurate drag model for this bullet.")
            }
            src == DragSource.AUTO -> {
                form.header("Seasonal BC rows")
                form.group {
                    listOf("cold", "mild", "hot").forEach { s ->
                        b.seasonalBcPoints[s]?.takeIf { it.isNotEmpty() }?.let { info(s.replaceFirstChar { c -> c.uppercase() } + " (G7)", bcRowsText(it, settings)) }
                    }
                }
                form.note("Picked at calculation time from the shooting temperature: cold below 30°F, hot above 90°F, mild in between.")
            }
            else -> {
                form.header("Ballistic Coefficient")
                form.group { info("G7 rows", bcRowsText(shownRows(src), settings)) }
            }
        }

        form.header("Powder temperature (optional)")
        form.group {
            number("Powder Temperature", Quantity.TEMPERATURE, l.powderTempF, { v -> if (v != null) load = load.copy(powderTempF = v) }, signed = true)
            number("Speed change per degree", Quantity.VEL_PER_DEG, l.velocityPerDegFps.takeIf { it != 0.0 },
                { v -> load = load.copy(velocityPerDegFps = v ?: 0.0) }, signed = true, hint = "0")
        }
        form.note("Optional. Your ammo's powder temperature when the muzzle velocity was measured, and the change in velocity (+/−) per degree.")

        form.header("Zero (sight-in)")
        if (!l.zeroConfirmed) form.note("Zero not confirmed. Empirical Precision does not record a zero, so this is the usual 100 yd with no offset. Check it against how the firearm is sighted in, then tap Save.")
        form.group {
            number("Zero Range", Quantity.DISTANCE, l.zeroRangeYd, { v -> if (v != null && v > 0) load = load.copy(zeroRangeYd = v) }, sub = "The distance the firearm is sighted in at")
            number("Zero Height", Quantity.LENGTH, l.zeroHeightIn, { v -> load = load.copy(zeroHeightIn = v ?: 0.0) }, signed = true, sub = "+ impact above aim")
            number("Zero Offset", Quantity.LENGTH, l.zeroOffsetIn, { v -> load = load.copy(zeroOffsetIn = v ?: 0.0) }, signed = true, sub = "+ impact right of aim")
            switch("Zero was shot in different weather", l.zeroAtmosphere != null, sub = "Turn on to enter the weather on the day you zeroed") { on ->
                load = load.copy(zeroAtmosphere = if (on) (load.zeroAtmosphere ?: store.lastShot().atmosphere) else null)
                render()
            }
            val za = l.zeroAtmosphere
            if (za != null) {
                number("Altitude", Quantity.ALTITUDE, za.altitudeFt, { v -> if (v != null && v > -2000 && v < 30000) zeroAtmo { copy(altitudeFt = v) } }, signed = true)
                number("Pressure", Quantity.PRESSURE, za.pressureInHg, { v -> if (v != null && v > 10 && v < 35) zeroAtmo { copy(pressureInHg = v) } })
                switch("Pressure was measured here", za.pressureIsAbsolute, sub = "On: station (absolute) pressure. Off: sea-level pressure from a forecast") { c -> zeroAtmo { copy(pressureIsAbsolute = c) } }
                number("Temperature", Quantity.TEMPERATURE, za.temperatureF, { v -> if (v != null && v > -80 && v < 160) zeroAtmo { copy(temperatureF = v) } }, signed = true)
                number("Humidity", Quantity.HUMIDITY, za.humidityPct, { v -> if (v != null && v in 0.0..100.0) zeroAtmo { copy(humidityPct = v) } })
            }
        }

        recipeSection(l)

        form.header("Notes")
        form.group { text(null, l.notes, { load = load.copy(notes = it) }, hint = "Notes", multiline = true) }
        if (EmpiricalHook.isEmpirical(l.externalRef)) form.note("Linked to Empirical Precision. A refresh from the backup may overwrite edits to the name, notes, muzzle velocity and recipe.")
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun recipe() = load.recipe ?: Recipe()

    private fun setRecipe(f: Recipe.() -> Recipe) { load = load.copy(recipe = recipe().f().takeIf { !it.isEmpty }) }

    private fun recipeSection(l: Load) {
        val catalog = EpCatalog.fromJson(store.empiricalCatalog())
        val others = store.loads(null).mapNotNull { it.recipe }
        form.foldable("Reloading recipe", { RecipeText.summary(load.recipe) }, recipeOpen, { open -> recipeOpen = open }) {
            val rc = l.recipe ?: Recipe()
            pick("Powder", rc.powder, catalog.powders, others.map { it.powder }, Ep.KEY_POWDER) { copy(powder = it) }
            number("Charge Weight", Quantity.WEIGHT, rc.chargeGr, { v -> setRecipe { copy(chargeGr = v?.takeIf { it > 0 }) } }, decimals = 2, hint = "optional")
            pick("Primer", rc.primer, catalog.primers, others.map { it.primer }, Ep.KEY_PRIMER) { copy(primer = it) }
            pick("Case (brass)", rc.brass, catalog.brass, others.map { it.brass }, Ep.KEY_BRASS) { copy(brass = it) }
            number("Times Fired", Quantity.PLAIN, rc.firings?.toDouble(), { v -> setRecipe { copy(firings = v?.takeIf { it >= 0 }?.toInt()) } }, decimals = 0, hint = "optional")
            pick("Bullet (as loaded)", rc.bullet, catalog.bullets, others.map { it.bullet }, Ep.KEY_BULLET,
                sub = "The bullet in your reloading record. Drag comes from the Bullet chosen at the top") { copy(bullet = it) }
            pick("Cartridge", rc.cartridge, catalog.cartridges, others.map { it.cartridge }, Ep.KEY_CARTRIDGE) { copy(cartridge = it) }
            number("Overall Length (COAL)", Quantity.BULLET_LENGTH, rc.coalIn, { v -> setRecipe { copy(coalIn = v?.takeIf { it > 0 }) } }, decimals = 4, hint = "optional")
            number("Base to Ogive (CBTO)", Quantity.BULLET_LENGTH, rc.cbtoIn, { v -> setRecipe { copy(cbtoIn = v?.takeIf { it > 0 }) } }, decimals = 4, hint = "optional")
            text("CBTO Comparator", rc.cbtoComparator, { setRecipe { copy(cbtoComparator = it.trim()) } }, hint = "optional, e.g. 0.264 insert")
            number("Shoulder Bump", Quantity.BULLET_LENGTH, rc.shoulderBumpIn, { v -> setRecipe { copy(shoulderBumpIn = v) } }, decimals = 4, hint = "optional")
            text("Shoulder Comparator", rc.shoulderComparator, { setRecipe { copy(shoulderComparator = it.trim()) } }, hint = "optional")
        }
        form.note("Optional, for hand-loaded ammo. It is kept with the ammo and goes to and from Empirical Precision; it does not change the ballistic solution.")
    }

    private fun Form.Group.pick(
        label: String, value: String, known: List<EpCatalog.Entry>, used: List<String>, key: String,
        sub: String? = null, set: Recipe.(String) -> Recipe,
    ) {
        val names = (known.map { it.name } + used.filter { it.isNotBlank() }).distinct()
        fun apply(name: String) {
            val id = known.firstOrNull { it.name == name }?.id
            setRecipe { set(name) }
            load = load.copy(externalIds = if (id != null) load.externalIds + (key to id) else load.externalIds - key)
            render()
        }
        fun type() {
            val input = android.widget.EditText(this@LoadEditActivity).apply { setText(value); setSingleLine(); textSize = 20f; setSelection(text.length) }
            val pad = dp(20)
            val box = android.widget.FrameLayout(this@LoadEditActivity).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
            MaterialAlertDialogBuilder(this@LoadEditActivity).setTitle(label).setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ -> apply(input.text?.toString().orEmpty().trim()) }
                .setNegativeButton(android.R.string.cancel, null).show()
            input.requestFocus()
        }
        nav(label, value.ifBlank { "—" }, sub = sub) {
            if (names.isEmpty()) { type(); return@nav }
            val items = names + listOf("Type it in…") + (if (value.isNotBlank()) listOf("Clear") else emptyList())
            MaterialAlertDialogBuilder(this@LoadEditActivity).setTitle(label)
                .setItems(items.toTypedArray()) { _, i ->
                    when {
                        i < names.size -> apply(names[i])
                        i == names.size -> type()
                        else -> apply("")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun zeroAtmo(f: com.longrange.dragcurve.model.Atmosphere.() -> com.longrange.dragcurve.model.Atmosphere) {
        load.zeroAtmosphere?.let { load = load.copy(zeroAtmosphere = it.f()) }
    }

    private fun changeBullet() =
        pickBullet.launch(Intent(this, BulletLibraryActivity::class.java).putExtra(BulletLibraryActivity.EXTRA_UNIFIED, true))

    private fun enterOwnBc() {
        if (bulletIsNew && bullet.name.isBlank()) return
        bullet = if (isLibrary) bullet.copy(id = newId("bullet"), source = "user", libraryOrder = null, seasonalBcPoints = emptyMap(),
            dragCurve = emptyList(), dragModel = DragModel.G7, name = "")
        else newBullet(newId("bullet"))
        bulletIsNew = true
        load = load.copy(bulletId = bullet.id)
        src = DragSource.BULLET; editModel = DragModel.G7; rows = BcRows.drafts(bullet.bcPoints)
        render()
    }

    private fun save() {
        currentFocus?.let { Form.hideKeyboard(it) }
        var l = load.copy(name = load.name.trim())
        var b = bullet
        if (l.name.isEmpty()) return alert("Missing name", "Give the ammo a profile name.")
        if (l.muzzleVelocityFps < 300 || l.muzzleVelocityFps > 6000) return alert("Muzzle velocity", "Enter a muzzle velocity between 300 and 6000 fps.")
        if (b.diameterIn < 0.1 || b.diameterIn > 1.0) return alert("Bullet diameter", "Enter the bullet diameter in inches (e.g. 0.264).")
        if (b.weightGr <= 0) return alert("Bullet weight", "Enter the bullet weight in grains.")
        if (l.zeroRangeYd <= 0) return alert("Zero range", "Enter the zero range.")

        var finalRows: List<com.longrange.dragcurve.model.BcPoint> = emptyList()
        if (rowsEditable) {
            val (r, err) = BcRows.finish(rows)
            if (r == null) return alert("Ballistic coefficient", err ?: "Check the BC rows.")
            finalRows = r
        }
        if (src == DragSource.CURVE && b.dragCurve.size < 2) return alert("Drag", "This bullet has no measured drag curve.")

        if (!isLibrary) {
            if (src == DragSource.BULLET) b = b.copy(dragModel = editModel, bcPoints = finalRows)
            if (b.name.isBlank()) b = b.copy(name = l.name)
            if (src == DragSource.CUSTOM && b.bcPoints.isEmpty() && b.dragCurve.size < 2) b = b.copy(dragModel = editModel, bcPoints = finalRows)
            val stored = store.bullet(b.id)
            if (stored == null || stored != b) {
                val sharedWithOthers = stored != null && store.loads(null).any { it.bulletId == b.id && it.id != l.id }
                if (sharedWithOthers) b = b.copy(id = newId("bullet"), name = b.name + " (" + l.name + ")")
                b = store.saveUserBullet(b)
            }
        }
        l = BcSelection.apply(l.copy(bulletId = b.id, zeroConfirmed = true, recipe = l.recipe?.takeIf { !it.isEmpty }), b, src, editModel, finalRows)
        store.saveLoad(l)
        val (selR, selL) = store.selection()
        if (selR == l.rifleId && (selL == null || isNew)) store.saveSelection(l.rifleId, l.id)
        initial = snapshot()
        finish()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_RIFLE = "rifle"
        const val EXTRA_BULLET = "bullet"
    }
}
