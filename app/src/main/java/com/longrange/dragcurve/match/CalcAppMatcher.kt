package com.longrange.dragcurve.match

import kotlin.math.abs

object CalcAppUi {
    const val PACKAGE = "net.kndy.shooterfull"

    const val DESC_ADD_AMMO = "Add Ammo"
    const val DESC_BACK = "Back"
    const val DESC_SAVE = "Save"
    const val DESC_CANCEL = "Cancel"

    const val MENU_ADD_MANUALLY = "Add Manually"
    const val MENU_FROM_LIBRARY = "From Bullet Library"

    const val MAIN_SECTION = "Firearm & Ammo"
    const val MAIN_AMMO_ROW = "Ammo"

    const val TITLE_EDIT = "Edit Ammo"
    const val TITLE_ADD = "Add Ammo"

    const val PROFILE = "Profile"
    const val DIAMETER = "Bullet Diameter"
    const val WEIGHT = "Bullet Weight"
    const val LENGTH = "Bullet Length"
    const val ADVANCED = "Advanced"
    const val NOSE_LENGTH = "Nose Length"
    const val MUZZLE_VELOCITY = "Muzzle Velocity"
    const val ATMOSPHERE = "Atmosphere Standard"
    const val DRAG_MODEL = "Drag Model"
    const val DRAG_SCALE = "Drag Scale Factor"
    const val BC_HEADER = "Ballistic Coefficient"
    const val BC = "BC"
    const val MIN_VEL_PREFIX = "Min Vel"
    const val POWDER_TEMP = "Powder Temperature"
    const val VELOCITY_VARIATION = "Velocity Variation"
    const val ZERO_RANGE = "Zero Range"
    const val ZERO_HEIGHT = "Zero Height"
    const val ZERO_OFFSET = "Zero Offset"
    const val USE_ZERO_ATMOSPHERE = "Use Zero Atmosphere"
    const val ALTITUDE = "Altitude"
    const val PRESSURE = "Pressure"
    const val PRESSURE_ABSOLUTE = "Pressure is Absolute"
    const val TEMPERATURE = "Temperature"
    const val HUMIDITY = "Humidity"
    const val NOTES = "Notes"

    val FORM_ORDER = listOf(
        PROFILE, DIAMETER, WEIGHT, LENGTH, ADVANCED, NOSE_LENGTH, MUZZLE_VELOCITY, ATMOSPHERE,
        DRAG_MODEL, DRAG_SCALE, BC_HEADER, POWDER_TEMP, VELOCITY_VARIATION, ZERO_RANGE, ZERO_HEIGHT,
        ZERO_OFFSET, USE_ZERO_ATMOSPHERE, ALTITUDE, PRESSURE, PRESSURE_ABSOLUTE, TEMPERATURE,
        HUMIDITY, NOTES,
    )

    val FORBIDDEN_CLICK = setOf(
        "save", "delete", "duplicate", "edit", "cancel", "back", "from bullet library",
        "discard", "ok", "yes", "remove",
    )
}

const val ROW_TOLERANCE_PX = 20

enum class ScrollDir { FORWARD, BACKWARD }

data class BcRow(val index: Int, val bc: UiNode, val minVel: UiNode?)

data class BcRows(val anchored: Boolean, val rows: List<BcRow>, val minVelLabel: String?)

class EditorMatcher(val root: UiNode) {
    private val all: List<UiNode> = root.walk().toList()
    private val editTexts: List<UiNode> = all.filter { it.isEditText }

    fun labels(text: String): List<UiNode> =
        all.filter { !it.isEditText && it.text.equals(text, ignoreCase = true) }

    fun hasLabel(text: String): Boolean = labels(text).isNotEmpty()

    private fun sameRow(a: UiNode, b: UiNode): Boolean =
        abs(a.bounds.centerY - b.bounds.centerY) <= ROW_TOLERANCE_PX

    fun rowEditText(label: String): UiNode? {
        for (l in labels(label)) {
            val hit = editTexts
                .filter { it !== l && sameRow(it, l) && it.bounds.centerX > l.bounds.centerX && it.bounds.left >= l.bounds.left }
                .minByOrNull { abs(it.bounds.left - l.bounds.right) }
            if (hit != null) return hit
        }
        return null
    }

    fun editTextBelow(label: String, maxGapPx: Int = 48): UiNode? {
        for (l in labels(label)) {
            val hit = editTexts
                .filter {
                    it.bounds.top >= l.bounds.bottom - 2 &&
                        it.bounds.top - l.bounds.bottom <= maxGapPx &&
                        it.bounds.overlapsHorizontally(l.bounds)
                }
                .minByOrNull { it.bounds.top - l.bounds.bottom }
            if (hit != null) return hit
        }
        return null
    }

    fun pickerValue(label: String): UiNode? {
        for (l in labels(label)) {
            val hit = all
                .filter {
                    it !== l && !it.isEditText && it.text.isNotEmpty() && sameRow(it, l) &&
                        it.bounds.left >= l.bounds.right - 4 && !it.isDescendantOf(l)
                }
                .minByOrNull { it.bounds.left - l.bounds.right }
            if (hit != null) return hit
        }
        return null
    }

    fun rowCheckable(label: String): UiNode? {
        for (l in labels(label)) {
            val hit = all.filter { it.checkable && it !== l && sameRow(it, l) && it.bounds.centerX > l.bounds.centerX }
                .minByOrNull { it.bounds.left - l.bounds.right }
            if (hit != null) return hit
        }
        return null
    }

    fun rowUnit(label: String): String? {
        val field = rowEditText(label) ?: return null
        return all
            .filter {
                !it.isEditText && it.text.isNotEmpty() && sameRow(it, field) &&
                    it.bounds.left >= field.bounds.right - 4 && !isSignToggle(it) && !it.isDescendantOf(field)
            }
            .minByOrNull { it.bounds.left }
            ?.text
    }

    private fun isSignToggle(n: UiNode) = n.text == "+/−" || n.text == "+/-"

    fun bcRows(): BcRows {
        val header = labels(CalcAppUi.BC_HEADER).firstOrNull()
        val bcLabels = labels(CalcAppUi.BC)
        val minVelLabels = all.filter { !it.isEditText && it.text.startsWith(CalcAppUi.MIN_VEL_PREFIX, ignoreCase = true) }

        fun fieldBelow(l: UiNode): UiNode? = editTexts
            .filter {
                it.bounds.top >= l.bounds.bottom - 4 && it.bounds.top - l.bounds.bottom <= 30 &&
                    abs(it.bounds.left - l.bounds.left) <= 12
            }
            .minByOrNull { it.bounds.top }

        val bcFields = bcLabels.mapNotNull { fieldBelow(it) }.distinct().sortedBy { it.bounds.top }
        val rows = bcFields.mapIndexed { k, bc ->
            val minVel = minVelLabels.mapNotNull { fieldBelow(it) }.firstOrNull { sameRow(it, bc) }
                ?: editTexts.filter { it !== bc && sameRow(it, bc) && it.bounds.left > bc.bounds.right - 4 }
                    .minByOrNull { it.bounds.left }
            BcRow(k, bc, minVel)
        }

        val firstTop = rows.firstOrNull()?.bc?.bounds?.top ?: Int.MAX_VALUE
        val orphanAbove = rows.isNotEmpty() && editTexts.any {
            it.bounds.top < firstTop && abs(it.bounds.left - rows[0].bc.bounds.left) <= 12 &&
                it.bounds.width == rows[0].bc.bounds.width &&
                (header == null || it.bounds.top > header.bounds.bottom)
        }
        val anchored = header != null && header.bounds.bottom <= firstTop && !orphanAbove
        return BcRows(anchored, rows, minVelLabels.firstOrNull()?.text)
    }

    fun title(): String? {
        val cancel = all.firstOrNull { it.desc.equals(CalcAppUi.DESC_CANCEL, true) } ?: return null
        return all.filter {
            it.text.isNotEmpty() && !it.isEditText && sameRow(it, cancel) && it.bounds.left >= cancel.bounds.right
        }.minByOrNull { it.bounds.left }?.text
    }

    fun scrollContainer(): UiNode? =
        all.filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }

    fun scrollDirectionToward(label: String): ScrollDir? {
        if (hasLabel(label)) return null
        val target = CalcAppUi.FORM_ORDER.indexOf(label)
        require(target >= 0) { "unknown label $label" }
        val visible = CalcAppUi.FORM_ORDER.withIndex().filter { hasLabel(it.value) }.map { it.index }
        if (visible.isEmpty()) return null
        return if (target < visible.min()) ScrollDir.BACKWARD else ScrollDir.FORWARD
    }

    fun profileField(): UiNode? = editTextBelow(CalcAppUi.PROFILE)

    companion object {
        fun clickableSelfOrAncestor(n: UiNode): UiNode? =
            if (n.clickable) n else n.ancestors().firstOrNull { it.clickable }

        fun isForbiddenClickTarget(n: UiNode): Boolean = n.walk().any {
            it.text.lowercase() in CalcAppUi.FORBIDDEN_CLICK || it.desc.lowercase() in CalcAppUi.FORBIDDEN_CLICK
        }
    }
}

enum class ScreenKind { ADD_MENU, EDITOR, AMMO_LIST, MAIN, OTHER }

data class Screen(val kind: ScreenKind, val main: UiNode?, val others: List<UiNode>, val title: String? = null)

object ScreenDetector {
    private fun hasDesc(root: UiNode, d: String) = root.walk().any { it.desc.equals(d, ignoreCase = true) }
    private fun hasText(root: UiNode, t: String) = root.walk().any { it.text.equals(t, ignoreCase = true) }

    fun isEditor(root: UiNode): Boolean {
        if (!hasDesc(root, CalcAppUi.DESC_SAVE) || !hasDesc(root, CalcAppUi.DESC_CANCEL)) return false
        val title = EditorMatcher(root).title() ?: return false
        return title.contains("Ammo", ignoreCase = true)
    }

    fun isAmmoList(root: UiNode): Boolean =
        hasDesc(root, CalcAppUi.DESC_ADD_AMMO) && hasDesc(root, CalcAppUi.DESC_BACK)

    fun isMainScreen(root: UiNode): Boolean =
        hasText(root, CalcAppUi.MAIN_SECTION) && mainAmmoRow(root) != null

    fun mainAmmoRow(root: UiNode): UiNode? =
        root.walk()
            .filter { !it.isEditText && it.text.equals(CalcAppUi.MAIN_AMMO_ROW, ignoreCase = true) }
            .mapNotNull { EditorMatcher.clickableSelfOrAncestor(it) }
            .firstOrNull { !EditorMatcher.isForbiddenClickTarget(it) && it.walk().none { n -> n.isEditText || n.desc.isNotEmpty() } }

    fun isAddMenu(root: UiNode): Boolean =
        hasText(root, CalcAppUi.MENU_ADD_MANUALLY) && hasText(root, CalcAppUi.MENU_FROM_LIBRARY)

    fun detect(roots: List<UiNode>): Screen {
        roots.firstOrNull { isAddMenu(it) }?.let { m -> return Screen(ScreenKind.ADD_MENU, m, roots - m) }
        roots.firstOrNull { isEditor(it) }?.let { e ->
            return Screen(ScreenKind.EDITOR, e, roots - e, EditorMatcher(e).title())
        }
        roots.firstOrNull { isAmmoList(it) }?.let { a -> return Screen(ScreenKind.AMMO_LIST, a, roots - a) }
        roots.firstOrNull { isMainScreen(it) }?.let { m -> return Screen(ScreenKind.MAIN, m, roots - m) }
        return Screen(ScreenKind.OTHER, null, roots)
    }

    fun addAmmoButton(root: UiNode): UiNode? =
        root.walk().firstOrNull { it.desc.equals(CalcAppUi.DESC_ADD_AMMO, true) }
            ?.let { EditorMatcher.clickableSelfOrAncestor(it) }

    fun ammoListNames(root: UiNode): List<String> =
        root.walk()
            .filter { it.clickable && !EditorMatcher.isForbiddenClickTarget(it) && it.walk().none { n -> n.desc.isNotEmpty() } }
            .mapNotNull { row -> row.walk().firstOrNull { it !== row && !it.isEditText && it.text.isNotEmpty() }?.text }
            .toList()

    fun addManuallyItem(root: UiNode): UiNode? =
        root.walk().firstOrNull { it.text.equals(CalcAppUi.MENU_ADD_MANUALLY, true) }
            ?.let { EditorMatcher.clickableSelfOrAncestor(it) }
}

object PickerMatcher {
    fun optionMatches(text: String, wanted: String): Boolean {
        val t = text.trim()
        if (t.equals(wanted, ignoreCase = true)) return true
        if (t.length > wanted.length && t.startsWith(wanted, ignoreCase = true)) {
            val next = t[wanted.length]
            return !next.isLetterOrDigit()
        }
        return false
    }

    fun findOption(editor: UiNode?, popups: List<UiNode>, wanted: String): UiNode? {
        for (p in popups) {
            val n = p.walk().firstOrNull { !it.isEditText && optionMatches(it.text, wanted) }
            if (n != null) return EditorMatcher.clickableSelfOrAncestor(n)
        }
        if (editor != null) {
            val form = EditorMatcher(editor).scrollContainer()
            val n = editor.walk().firstOrNull {
                !it.isEditText && optionMatches(it.text, wanted) && (form == null || (it !== form && !it.isDescendantOf(form)))
            }
            if (n != null) return EditorMatcher.clickableSelfOrAncestor(n)
        }
        return null
    }
}

object Values {
    fun fmt(v: Double): String {
        if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
        return java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
    }

    fun parse(s: String?): Double? =
        s?.trim()?.replace(',', '.')?.replace('−', '-')?.toDoubleOrNull()

    fun numbersEqual(actual: String?, expected: String, tol: Double = 1e-6): Boolean {
        val a = parse(actual) ?: return false
        val e = parse(expected) ?: return false
        return abs(a - e) <= tol * maxOf(1.0, abs(e))
    }
}
