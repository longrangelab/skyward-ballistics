package com.longrange.dragcurve.fill

import com.longrange.dragcurve.match.EditorMatcher
import com.longrange.dragcurve.match.ScreenDetector
import com.longrange.dragcurve.match.CalcAppUi
import com.longrange.dragcurve.match.UiNode
import com.longrange.dragcurve.match.Values
import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.Atmosphere
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.TwistDirection
import kotlin.math.abs

object AppImportUi {
    const val TITLE_EDIT_FIREARM = "Edit Firearm"
    const val TITLE_EDIT_AMMO = CalcAppUi.TITLE_EDIT
    const val TITLE_FIREARMS = "Firearms"
    const val DESC_ADD_FIREARM = "Add Firearm"
    const val MENU_EDIT = "Edit"
    const val MENU_DUPLICATE = "Duplicate"
    const val MENU_DELETE = "Delete"
    const val MAIN_FIREARM_ROW = "Firearm"

    const val BARREL_TWIST = "Barrel Twist"
    const val TWIST_DIRECTION = "Twist Direction"
    const val SIGHT_HEIGHT = "Sight Height"
    const val SIGHT_OFFSET = "Sight Offset"
    const val ELEVATION_UNIT = "Elevation Unit"
    const val ELEVATION_TURRET = "Elevation Turret"
    const val WINDAGE_UNIT = "Windage Unit"
    const val WINDAGE_TURRET = "Windage Turret"
    const val ELEVATION_CORRECTION = "Elevation Correction"
    const val WINDAGE_CORRECTION = "Windage Correction"
}

class ImportProblem(message: String) : Exception(message)

data class AppFirearm(
    val name: String,
    val listSubtitle: String = "",
    val twistIn: Double?,
    val twistDirection: TwistDirection,
    val sightHeightIn: Double?,
    val sightOffsetIn: Double,
    val elevationUnit: AngleUnit,
    val elevationClick: Double,
    val windageUnit: AngleUnit,
    val windageClick: Double,
    val elevationCorrection: Double,
    val windageCorrection: Double,
    val scopeRead: Boolean = true,
    val clicksRead: Boolean = true,
)

data class AppAmmo(
    val firearm: String,
    val name: String,
    val listSubtitle: String = "",
    val diameterIn: Double,
    val weightGr: Double,
    val lengthIn: Double?,
    val noseLengthIn: Double?,
    val muzzleVelocityFps: Double,
    val atmosphereStandard: String,
    val dragModel: DragModel?,
    val dragModelText: String,
    val dragScaleFactor: Double,
    val bcPoints: List<BcPoint>,
    val powderTempF: Double?,
    val velocityPerDegFps: Double,
    val zeroRangeYd: Double,
    val zeroHeightIn: Double,
    val zeroOffsetIn: Double,
    val zeroAtmosphere: Atmosphere?,
    val notes: String,
    val problem: String? = null,
    val libraryBulletId: String? = null,
    val libraryBulletName: String? = null,
)

data class AppImport(
    val firearms: List<AppFirearm> = emptyList(),
    val ammo: List<AppAmmo> = emptyList(),
    val warnings: List<String> = emptyList(),
    val refPrefix: String = ImportMerge.PREFIX,
) {
    fun ammoOf(firearm: String) = ammo.filter { it.firearm == firearm }
}

enum class ImportScreenKind { MAIN, FIREARM_LIST, AMMO_LIST, ITEM_MENU, FIREARM_EDITOR, AMMO_EDITOR, OTHER_EDITOR, OTHER }

data class ImportScreen(val kind: ImportScreenKind, val main: UiNode?, val roots: List<UiNode>, val title: String?)

data class ListRow(val name: String, val subtitle: String, val node: UiNode)

object ImportScreens {
    private fun hasDesc(root: UiNode, d: String) = root.walk().any { it.desc.equals(d, ignoreCase = true) }
    private fun hasText(root: UiNode, t: String) = root.walk().any { !it.isEditText && it.text == t }

    fun isItemMenu(root: UiNode): Boolean =
        hasText(root, AppImportUi.MENU_EDIT) && hasText(root, AppImportUi.MENU_DUPLICATE) &&
            hasText(root, AppImportUi.MENU_DELETE) && !hasDesc(root, CalcAppUi.DESC_SAVE)

    fun isEditor(root: UiNode): Boolean = hasDesc(root, CalcAppUi.DESC_SAVE) && hasDesc(root, CalcAppUi.DESC_CANCEL)

    fun isFirearmList(root: UiNode): Boolean =
        hasDesc(root, AppImportUi.DESC_ADD_FIREARM) && hasDesc(root, CalcAppUi.DESC_BACK)

    fun topBarTitle(root: UiNode): String? {
        val icon = root.walk().firstOrNull {
            it.desc.equals(CalcAppUi.DESC_BACK, true) || it.desc.equals(CalcAppUi.DESC_CANCEL, true)
        } ?: return null
        val tol = maxOf(20, icon.bounds.height)
        return root.walk().filter {
            it.text.isNotEmpty() && !it.isEditText && abs(it.bounds.centerY - icon.bounds.centerY) <= tol &&
                it.bounds.left >= icon.bounds.right
        }.minByOrNull { it.bounds.left }?.text
    }

    fun classify(roots: List<UiNode>): ImportScreen {
        roots.firstOrNull { isItemMenu(it) }?.let { return ImportScreen(ImportScreenKind.ITEM_MENU, it, roots, null) }
        roots.firstOrNull { isEditor(it) }?.let { e ->
            val t = topBarTitle(e)
            val kind = when {
                t.equals(AppImportUi.TITLE_EDIT_FIREARM, true) -> ImportScreenKind.FIREARM_EDITOR
                t.equals(AppImportUi.TITLE_EDIT_AMMO, true) -> ImportScreenKind.AMMO_EDITOR
                else -> ImportScreenKind.OTHER_EDITOR
            }
            return ImportScreen(kind, e, roots, t)
        }
        roots.firstOrNull { isFirearmList(it) }?.let { return ImportScreen(ImportScreenKind.FIREARM_LIST, it, roots, topBarTitle(it)) }
        roots.firstOrNull { ScreenDetector.isAmmoList(it) }?.let { return ImportScreen(ImportScreenKind.AMMO_LIST, it, roots, topBarTitle(it)) }
        roots.firstOrNull { ScreenDetector.isMainScreen(it) }?.let { return ImportScreen(ImportScreenKind.MAIN, it, roots, null) }
        return ImportScreen(ImportScreenKind.OTHER, roots.firstOrNull(), roots, null)
    }

    fun listRows(root: UiNode): List<ListRow> =
        root.walk()
            .filter { (it.clickable || it.longClickable) && it.walk().none { n -> n.desc.isNotEmpty() || n.isEditText || n.checkable } }
            .mapNotNull { row ->
                val texts = row.walk().filter { it !== row && !it.isEditText && it.text.isNotEmpty() }.map { it.text }.toList()
                if (texts.isEmpty()) null else ListRow(texts[0], texts.getOrNull(1).orEmpty(), row)
            }
            .distinctBy { it.node.id }
            .sortedBy { it.node.bounds.top }
            .toList()

    fun mainRow(root: UiNode, label: String): UiNode? =
        root.walk()
            .filter { !it.isEditText && it.text == label }
            .mapNotNull { EditorMatcher.clickableSelfOrAncestor(it) }
            .firstOrNull { it.walk().none { n -> n.isEditText || n.desc.isNotEmpty() || n.checkable } }

    fun mainValue(root: UiNode, label: String): String? = EditorMatcher(root).pickerValue(label)?.text

    fun menuItem(root: UiNode, text: String): UiNode? =
        root.walk().filter { !it.isEditText && it.text == text }
            .mapNotNull { EditorMatcher.clickableSelfOrAncestor(it) }
            .firstOrNull { item -> item.walk().filter { it.text.isNotEmpty() || it.desc.isNotEmpty() }.all { it.text == text } }

    fun scrollable(root: UiNode): UiNode? =
        root.walk().filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }

    fun signature(root: UiNode): String =
        root.walk().filter { it.text.isNotEmpty() || it.desc.isNotEmpty() || it.checkable || it.isEditText }
            .joinToString("|") { "${it.text}/${it.desc}/${if (it.checkable) it.checked else ""}@${it.bounds.top}" }

    fun ammoCount(subtitle: String): Int? =
        Regex("""^(\d+)\s+ammo profiles?$""", RegexOption.IGNORE_CASE).find(subtitle.trim())?.groupValues?.get(1)?.toIntOrNull()

    data class AmmoSummary(val weight: Double, val weightUnit: String, val dragModel: String, val bcCount: Int)

    fun ammoSummary(subtitle: String): AmmoSummary? {
        val m = Regex("""^([\d.]+)\s*([A-Za-z]+)\s+(\S+)\s+\((\d+)\s+BCs?\)$""").find(subtitle.trim()) ?: return null
        val (w, u, dm, n) = m.destructured
        return AmmoSummary(w.toDoubleOrNull() ?: return null, u, dm, n.toInt())
    }
}

enum class Quantity { LENGTH, MASS, VELOCITY, DISTANCE, TEMPERATURE, ALTITUDE, PRESSURE, PERCENT, VELOCITY_PER_DEGREE, NONE }

enum class FieldKind { TEXT, TEXT_BELOW, PICKER, SWITCH }

data class FieldSpec(val label: String, val kind: FieldKind, val q: Quantity = Quantity.NONE, val required: Boolean = true)

data class Cell(val text: String, val unit: String?, val checked: Boolean?)

object FormSpecs {
    private val U = AppImportUi
    private val A = CalcAppUi

    val FIREARM = listOf(
        FieldSpec(A.PROFILE, FieldKind.TEXT_BELOW),
        FieldSpec(U.BARREL_TWIST, FieldKind.TEXT, Quantity.LENGTH),
        FieldSpec(U.TWIST_DIRECTION, FieldKind.PICKER),
        FieldSpec(U.SIGHT_HEIGHT, FieldKind.TEXT, Quantity.LENGTH),
        FieldSpec(U.SIGHT_OFFSET, FieldKind.TEXT, Quantity.LENGTH),
        FieldSpec(U.ELEVATION_UNIT, FieldKind.PICKER),
        FieldSpec(U.ELEVATION_TURRET, FieldKind.PICKER),
        FieldSpec(U.WINDAGE_UNIT, FieldKind.PICKER),
        FieldSpec(U.WINDAGE_TURRET, FieldKind.PICKER),
        FieldSpec(U.ELEVATION_CORRECTION, FieldKind.TEXT),
        FieldSpec(U.WINDAGE_CORRECTION, FieldKind.TEXT),
    )

    val ZERO_ATMOSPHERE = listOf(
        FieldSpec(A.ALTITUDE, FieldKind.TEXT, Quantity.ALTITUDE, required = false),
        FieldSpec(A.PRESSURE, FieldKind.TEXT, Quantity.PRESSURE, required = false),
        FieldSpec(A.PRESSURE_ABSOLUTE, FieldKind.SWITCH, required = false),
        FieldSpec(A.TEMPERATURE, FieldKind.TEXT, Quantity.TEMPERATURE, required = false),
        FieldSpec(A.HUMIDITY, FieldKind.TEXT, Quantity.PERCENT, required = false),
    )

    val AMMO = listOf(
        FieldSpec(A.PROFILE, FieldKind.TEXT_BELOW),
        FieldSpec(A.DIAMETER, FieldKind.TEXT, Quantity.LENGTH),
        FieldSpec(A.WEIGHT, FieldKind.TEXT, Quantity.MASS),
        FieldSpec(A.LENGTH, FieldKind.TEXT, Quantity.LENGTH),
        FieldSpec(A.NOSE_LENGTH, FieldKind.TEXT, Quantity.LENGTH, required = false),
        FieldSpec(A.MUZZLE_VELOCITY, FieldKind.TEXT, Quantity.VELOCITY),
        FieldSpec(A.ATMOSPHERE, FieldKind.PICKER),
        FieldSpec(A.DRAG_MODEL, FieldKind.PICKER),
        FieldSpec(A.DRAG_SCALE, FieldKind.TEXT),
        FieldSpec(A.POWDER_TEMP, FieldKind.TEXT, Quantity.TEMPERATURE),
        FieldSpec(A.VELOCITY_VARIATION, FieldKind.TEXT, Quantity.VELOCITY_PER_DEGREE),
        FieldSpec(A.ZERO_RANGE, FieldKind.TEXT, Quantity.DISTANCE),
        FieldSpec(A.ZERO_HEIGHT, FieldKind.TEXT, Quantity.LENGTH),
        FieldSpec(A.ZERO_OFFSET, FieldKind.TEXT, Quantity.LENGTH),
        FieldSpec(A.USE_ZERO_ATMOSPHERE, FieldKind.SWITCH),
    ) + ZERO_ATMOSPHERE + FieldSpec(A.NOTES, FieldKind.TEXT_BELOW, required = false)
}

class FormAccumulator(
    val specs: List<FieldSpec>,
    private val bcTable: Boolean = specs.any { it.label == CalcAppUi.DRAG_MODEL },
) {
    val cells = LinkedHashMap<String, Cell>()
    val bcRows = mutableListOf<Pair<String, String>>()
    var minVelLabel: String? = null
        private set
    var pages = 0
        private set
    var firstProfile: String? = null
        private set

    fun add(root: UiNode) {
        pages++
        val m = EditorMatcher(root)
        for (s in specs) {
            if (cells.containsKey(s.label)) continue
            readCell(root, m, s)?.let { cells[s.label] = it }
        }
        if (pages == 1) firstProfile = cells[CalcAppUi.PROFILE]?.text
        if (bcTable) {
            val rows = m.bcRows()
            if (rows.minVelLabel != null && minVelLabel == null) minVelLabel = rows.minVelLabel
            for (r in rows.rows) {
                val pair = r.bc.text to (r.minVel?.text.orEmpty())
                if (pair.first.isEmpty() && pair.second.isEmpty()) continue
                if (pair !in bcRows) bcRows += pair
            }
        }
    }

    companion object {
        private fun isSign(n: UiNode) = n.text == "+/−" || n.text == "+/-"

        fun rowCell(m: EditorMatcher, s: FieldSpec): Cell? {
            for (l in m.labels(s.label)) {
                val row = l.parent ?: continue
                val inRow = row.walk().filter { it !== row && it !== l }.toList()
                when (s.kind) {
                    FieldKind.TEXT -> {
                        val boxes = inRow.filter { it.isEditText }
                        if (boxes.size != 1) continue
                        val box = boxes[0]
                        val unit = inRow.filter { !it.isEditText && it.text.isNotEmpty() && !isSign(it) && !it.isDescendantOf(box) }
                            .lastOrNull()?.text
                        return Cell(box.rawText.orEmpty().trim(), unit, null)
                    }
                    FieldKind.PICKER -> {
                        if (inRow.any { it.isEditText || it.checkable }) continue
                        val texts = inRow.filter { it.text.isNotEmpty() }
                        if (texts.size != 1) continue
                        return Cell(texts[0].text, null, null)
                    }
                    FieldKind.SWITCH -> {
                        val sw = inRow.filter { it.checkable }
                        if (sw.size != 1 || inRow.any { it.isEditText }) continue
                        return Cell("", null, sw[0].checked)
                    }
                    FieldKind.TEXT_BELOW -> return null
                }
            }
            return null
        }

        fun readCell(root: UiNode, m: EditorMatcher, s: FieldSpec): Cell? = rowCell(m, s) ?: when (s.kind) {
            FieldKind.TEXT -> m.rowEditText(s.label)?.let { Cell(it.rawText.orEmpty().trim(), m.rowUnit(s.label), null) }
            FieldKind.TEXT_BELOW -> {
                val lab = m.labels(s.label).firstOrNull()
                val gap = maxOf(48, (lab?.bounds?.height ?: 16) * 3)
                m.editTextBelow(s.label, maxGapPx = gap)?.let { Cell(it.rawText.orEmpty().trim(), null, null) }
            }
            FieldKind.PICKER -> m.pickerValue(s.label)?.let { Cell(it.text, null, null) }
            FieldKind.SWITCH -> m.rowCheckable(s.label)?.let { Cell("", null, it.checked) }
        }
    }
}

object ImportUnits {
    private fun norm(u: String) = u.trim().replace('º', '°').replace(" ", "").lowercase()

    private fun tempIsC(u: String?): Boolean? = when (u?.let(::norm)) {
        "°f", "f" -> false
        "°c", "c" -> true
        else -> null
    }

    private fun factor(q: Quantity, unit: String): Double? {
        val u = norm(unit)
        return when (q) {
            Quantity.LENGTH -> mapOf("in" to 1.0, "\"" to 1.0, "cm" to 1 / 2.54, "mm" to 1 / 25.4)[u]
            Quantity.MASS -> mapOf("gr" to 1.0, "g" to 15.4323584)[u]
            Quantity.VELOCITY -> mapOf("fps" to 1.0, "ft/s" to 1.0, "m/s" to 3.2808399, "mps" to 3.2808399)[u]
            Quantity.DISTANCE -> mapOf("yd" to 1.0, "yds" to 1.0, "m" to 1.0936133)[u]
            Quantity.ALTITUDE -> mapOf("ft" to 1.0, "m" to 3.2808399)[u]
            Quantity.PRESSURE -> mapOf("inhg" to 1.0, "hpa" to 1 / 33.8638866, "mbar" to 1 / 33.8638866, "mb" to 1 / 33.8638866, "mmhg" to 1 / 25.4, "psi" to 2.03602)[u]
            Quantity.PERCENT -> if (u == "%") 1.0 else null
            else -> null
        }
    }

    fun toImperial(label: String, q: Quantity, v: Double, unit: String?, tempUnit: String? = null): Double {
        if (q == Quantity.NONE) return v
        if (unit.isNullOrBlank()) throw ImportProblem("No unit is shown next to \"$label\" in the calculator, so its value can't be read safely.")
        when (q) {
            Quantity.TEMPERATURE -> {
                val c = tempIsC(unit) ?: throw unknown(label, unit)
                return if (c) v * 9.0 / 5.0 + 32.0 else v
            }
            Quantity.VELOCITY_PER_DEGREE -> {
                val u = unit.trim()
                val slash = u.lastIndexOf('/')
                if (slash <= 0) throw unknown(label, unit)
                val vel = factor(Quantity.VELOCITY, u.substring(0, slash)) ?: throw unknown(label, unit)
                val deg = u.substring(slash + 1)
                val perC = when (norm(deg)) {
                    "°", "deg" -> tempIsC(tempUnit) ?: throw unknown(label, "$unit (temperature in ${tempUnit ?: "?"})")
                    else -> tempIsC(deg) ?: throw unknown(label, unit)
                }
                return v * vel / (if (perC) 1.8 else 1.0)
            }
            else -> return v * (factor(q, unit) ?: throw unknown(label, unit))
        }
    }

    private fun unknown(label: String, unit: String) =
        ImportProblem("The calculator shows \"$unit\" for \"$label\" — Skyward doesn't recognise that unit. Nothing was imported.")

    fun angleUnit(label: String, s: String): AngleUnit = when (s.trim().lowercase().replace(" ", "")) {
        "moa", "truemoa" -> AngleUnit.MOA
        "mil", "mils", "mrad", "mrads", "mils(mrad)" -> AngleUnit.MIL
        "iphy", "smoa", "in/100yd", "inches" -> AngleUnit.IPHY
        else -> throw ImportProblem("The calculator shows \"$s\" for \"$label\" — Skyward doesn't recognise that unit. Nothing was imported.")
    }

    fun turret(label: String, s: String): Double {
        val t = s.trim().split(' ').first()
        val v = if ('/' in t) {
            val (a, b) = t.split('/', limit = 2)
            val n = Values.parse(a); val d = Values.parse(b)
            if (n != null && d != null && d != 0.0) n / d else null
        } else Values.parse(t)
        if (v == null || v <= 0 || v > 10) throw ImportProblem("Can't read the \"$label\" value \"$s\". Nothing was imported.")
        return v
    }
}

object ProfileReader {
    private val U = AppImportUi
    private val A = CalcAppUi

    private fun Map<String, Cell>.need(label: String): Cell =
        this[label] ?: throw ImportProblem("Couldn't find \"$label\" on the calculator's form. Nothing was imported.")

    private fun num(label: String, c: Cell?): Double? {
        val t = c?.text?.trim().orEmpty()
        if (t.isEmpty() || t == "-" || t == "−") return null
        return Values.parse(t) ?: throw ImportProblem("Can't read \"$t\" in \"$label\" as a number. Nothing was imported.")
    }

    private fun conv(label: String, q: Quantity, c: Cell?, tempUnit: String? = null): Double? =
        num(label, c)?.let { ImportUnits.toImperial(label, q, it, c!!.unit, tempUnit) }

    private fun requireUnitIfShown(label: String, q: Quantity, c: Cell?) {
        if (c != null && q != Quantity.NONE && q != Quantity.VELOCITY_PER_DEGREE) ImportUnits.toImperial(label, q, 0.0, c.unit)
    }

    private fun checkRequired(acc: FormAccumulator, specs: List<FieldSpec>) {
        val missing = specs.filter { it.required && !acc.cells.containsKey(it.label) }.map { it.label }
        if (missing.isNotEmpty()) {
            throw ImportProblem("Couldn't find ${missing.joinToString { "\"$it\"" }} on the calculator's form. Nothing was imported.")
        }
    }

    fun firearm(acc: FormAccumulator, expectedName: String, listSubtitle: String = ""): AppFirearm {
        checkRequired(acc, FormSpecs.FIREARM)
        val c = acc.cells
        val name = c.need(A.PROFILE).text
        if (name != expectedName.trim()) throw ImportProblem("The firearm editor shows \"$name\", not \"${expectedName.trim()}\". Stopped.")
        for (s in FormSpecs.FIREARM) requireUnitIfShown(s.label, s.q, c[s.label])
        val dir = when (c.need(U.TWIST_DIRECTION).text.lowercase()) {
            "right", "rh", "r" -> TwistDirection.RIGHT
            "left", "lh", "l" -> TwistDirection.LEFT
            else -> throw ImportProblem("Can't read the twist direction \"${c.need(U.TWIST_DIRECTION).text}\". Nothing was imported.")
        }
        return AppFirearm(
            name = name,
            listSubtitle = listSubtitle,
            twistIn = conv(U.BARREL_TWIST, Quantity.LENGTH, c[U.BARREL_TWIST]),
            twistDirection = dir,
            sightHeightIn = conv(U.SIGHT_HEIGHT, Quantity.LENGTH, c[U.SIGHT_HEIGHT]),
            sightOffsetIn = conv(U.SIGHT_OFFSET, Quantity.LENGTH, c[U.SIGHT_OFFSET]) ?: 0.0,
            elevationUnit = ImportUnits.angleUnit(U.ELEVATION_UNIT, c.need(U.ELEVATION_UNIT).text),
            elevationClick = ImportUnits.turret(U.ELEVATION_TURRET, c.need(U.ELEVATION_TURRET).text),
            windageUnit = ImportUnits.angleUnit(U.WINDAGE_UNIT, c.need(U.WINDAGE_UNIT).text),
            windageClick = ImportUnits.turret(U.WINDAGE_TURRET, c.need(U.WINDAGE_TURRET).text),
            elevationCorrection = num(U.ELEVATION_CORRECTION, c[U.ELEVATION_CORRECTION]) ?: 1.0,
            windageCorrection = num(U.WINDAGE_CORRECTION, c[U.WINDAGE_CORRECTION]) ?: 1.0,
        )
    }

    fun ammo(acc: FormAccumulator, firearm: String, expectedName: String, listSubtitle: String = ""): AppAmmo {
        checkRequired(acc, FormSpecs.AMMO)
        val c = acc.cells
        val name = c.need(A.PROFILE).text
        if (name != expectedName.trim()) throw ImportProblem("The ammo editor shows \"$name\", not \"${expectedName.trim()}\". Stopped.")
        for (s in FormSpecs.AMMO) requireUnitIfShown(s.label, s.q, c[s.label])
        val tempUnit = c[A.POWDER_TEMP]?.unit

        fun required(label: String, q: Quantity): Double =
            conv(label, q, c[label]) ?: throw ImportProblem("\"$label\" is empty for $firearm / $name. Nothing was imported.")

        val dmText = c.need(A.DRAG_MODEL).text
        val dm = when (dmText.uppercase()) { "G1" -> DragModel.G1; "G7" -> DragModel.G7; else -> null }
        val weight = required(A.WEIGHT, Quantity.MASS)

        val velUnit = acc.minVelLabel?.substringAfter('(', "")?.substringBefore(')')?.trim()?.ifEmpty { null } ?: "fps"
        val bcs = runCatching {
            acc.bcRows.map { (bcT, mvT) ->
                val bc = Values.parse(bcT)
                if (bc == null || bc <= 0 || bc > 2) throw ImportProblem("Can't read the BC \"$bcT\" of $firearm / $name. Nothing was imported.")
                val mv = if (mvT.isBlank()) 0.0 else (Values.parse(mvT)
                    ?: throw ImportProblem("Can't read the min velocity \"$mvT\" of $firearm / $name. Nothing was imported."))
                BcPoint(bc, ImportUnits.toImperial("Min Vel", Quantity.VELOCITY, mv, velUnit))
            }.sortedByDescending { it.minVelFps }
        }.getOrElse { if (dm != null) throw it else emptyList() }
        if (bcs.isEmpty() && dm != null) throw ImportProblem("No BC rows found for $firearm / $name. Nothing was imported.")

        ImportScreens.ammoSummary(listSubtitle)?.takeIf { dm != null }?.let { s ->
            if (s.bcCount != bcs.size) throw ImportProblem("Read ${bcs.size} BC row(s) for $firearm / $name but the calculator's list says ${s.bcCount}. Stopped so nothing wrong is imported.")
            if (!s.dragModel.equals(dmText, true)) throw ImportProblem("Read drag model $dmText for $firearm / $name but the calculator's list says ${s.dragModel}. Stopped.")
            if (s.weightUnit.equals("gr", true) && abs(s.weight - weight) > 0.51) {
                throw ImportProblem("Read ${Values.fmt(weight)} gr for $firearm / $name but the calculator's list says ${s.weight} gr. Stopped.")
            }
        }

        val zeroOn = c.need(A.USE_ZERO_ATMOSPHERE).checked == true
        val zeroAtmo = if (!zeroOn) null else {
            val missing = FormSpecs.ZERO_ATMOSPHERE.map { it.label }.filter { !c.containsKey(it) }
            if (missing.isNotEmpty()) throw ImportProblem("Couldn't find ${missing.joinToString { "\"$it\"" }} on the calculator's form. Nothing was imported.")
            for (s in FormSpecs.ZERO_ATMOSPHERE) requireUnitIfShown(s.label, s.q, c[s.label])
            val d = Atmosphere()
            Atmosphere(
                altitudeFt = conv(A.ALTITUDE, Quantity.ALTITUDE, c[A.ALTITUDE]) ?: d.altitudeFt,
                pressureInHg = conv(A.PRESSURE, Quantity.PRESSURE, c[A.PRESSURE]) ?: d.pressureInHg,
                pressureIsAbsolute = c[A.PRESSURE_ABSOLUTE]?.checked == true,
                temperatureF = conv(A.TEMPERATURE, Quantity.TEMPERATURE, c[A.TEMPERATURE]) ?: d.temperatureF,
                humidityPct = conv(A.HUMIDITY, Quantity.PERCENT, c[A.HUMIDITY]) ?: 0.0,
            )
        }
        val powder = conv(A.POWDER_TEMP, Quantity.TEMPERATURE, c[A.POWDER_TEMP])
        return AppAmmo(
            firearm = firearm,
            name = name,
            listSubtitle = listSubtitle,
            diameterIn = required(A.DIAMETER, Quantity.LENGTH),
            weightGr = weight,
            lengthIn = conv(A.LENGTH, Quantity.LENGTH, c[A.LENGTH]),
            noseLengthIn = conv(A.NOSE_LENGTH, Quantity.LENGTH, c[A.NOSE_LENGTH]),
            muzzleVelocityFps = required(A.MUZZLE_VELOCITY, Quantity.VELOCITY),
            atmosphereStandard = c.need(A.ATMOSPHERE).text,
            dragModel = dm,
            dragModelText = dmText,
            dragScaleFactor = num(A.DRAG_SCALE, c[A.DRAG_SCALE]) ?: 1.0,
            bcPoints = bcs,
            powderTempF = powder,
            velocityPerDegFps = conv(A.VELOCITY_VARIATION, Quantity.VELOCITY_PER_DEGREE, c[A.VELOCITY_VARIATION], tempUnit) ?: 0.0,
            zeroRangeYd = required(A.ZERO_RANGE, Quantity.DISTANCE),
            zeroHeightIn = conv(A.ZERO_HEIGHT, Quantity.LENGTH, c[A.ZERO_HEIGHT]) ?: 0.0,
            zeroOffsetIn = conv(A.ZERO_OFFSET, Quantity.LENGTH, c[A.ZERO_OFFSET]) ?: 0.0,
            zeroAtmosphere = zeroAtmo,
            notes = c[A.NOTES]?.text?.trim().orEmpty(),
            problem = if (dm == null) "Drag model \"$dmText\" isn't supported (G1 or G7 only)." else null,
        )
    }
}
