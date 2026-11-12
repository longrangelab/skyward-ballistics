package com.longrange.dragcurve.fill

import com.longrange.dragcurve.match.EditorMatcher
import com.longrange.dragcurve.match.CalcAppUi
import com.longrange.dragcurve.match.UiNode

sealed class ImportAction {
    abstract val waitMs: Long

    data class Click(
        val node: UiNode, val what: String, override val waitMs: Long = 900,
        val allowCancelIcon: Boolean = false, val allowDiscard: Boolean = false,
    ) : ImportAction()
    data class LongClick(val node: UiNode, val what: String, override val waitMs: Long = 1200) : ImportAction()
    data class Scroll(val node: UiNode, val forward: Boolean, override val waitMs: Long = 800) : ImportAction()
    data class Back(val why: String, override val waitMs: Long = 1000) : ImportAction()
    data class Wait(override val waitMs: Long) : ImportAction()
    data class Finished(val result: AppImport) : ImportAction() { override val waitMs = 0L }
    data class Failed(val message: String, val partial: AppImport) : ImportAction() { override val waitMs = 0L }
}

data class Observation(val roots: List<UiNode>, val foreground: Boolean, val now: Long)

object ImportSafety {
    val FORBIDDEN = setOf(
        "save", "done", "delete", "duplicate", "remove", "add firearm", "add ammo", "add manually",
        "from bullet library", "use bc calculator", "ok", "yes", "apply", "confirm", "reset", "clear",
        "+/−", "+/-", "calc", "get", "share", "export", "import", "back", "cancel", "keep editing",
    )
    val DISCARD_OPTIONS = setOf("discard", "discard changes", "don't save", "do not save", "leave without saving", "exit without saving")

    fun violation(node: UiNode, allowCancelIcon: Boolean = false, allowDiscard: Boolean = false): String? {
        for (n in node.walk()) {
            if (n.isEditText) return "a text box"
            if (n.checkable) return "a switch"
            for (s in listOf(n.text, n.desc)) {
                val l = s.trim().lowercase()
                if (l.isEmpty()) continue
                if (l == "cancel" && allowCancelIcon && n.desc.equals("cancel", true)) continue
                if (l in DISCARD_OPTIONS) { if (allowDiscard) continue else return "\"$s\"" }
                if (l in FORBIDDEN) return "\"$s\""
            }
        }
        return null
    }

    fun isTap(a: ImportAction) = a is ImportAction.Click || a is ImportAction.LongClick
}

class ImportPlanner(
    private val overallTimeoutMs: Long = 20 * 60_000L,
) {
    private enum class Phase {
        START, OPEN_FIREARMS, SCAN_LIST, FIND_ROW, WAIT_MENU, WAIT_EDITOR, READ_FORM, LEAVE_EDITOR,
        WAIT_SELECTED, OPEN_AMMO, LEAVE_AMMO_LIST, RESTORE, BAIL_OUT, DONE,
    }

    private enum class Goal { READ_FIREARM, SELECT_FIREARM, READ_AMMO, RESTORE_FIREARM, RESTORE_AMMO }

    private enum class Kind(val list: ImportScreenKind, val editor: ImportScreenKind) {
        FIREARM(ImportScreenKind.FIREARM_LIST, ImportScreenKind.FIREARM_EDITOR),
        AMMO(ImportScreenKind.AMMO_LIST, ImportScreenKind.AMMO_EDITOR),
    }

    private data class Row(val name: String, val subtitle: String)

    private var phase = Phase.START
    private var phaseStart = -1L
    private var startedAt = -1L
    private var now = 0L
    private var goal = Goal.READ_FIREARM

    private var origFirearm: String? = null
    private var origAmmo: String? = null
    private var selectionChanged = false
    private var restoreStage = 0
    private val warnings = mutableListOf<String>()

    private var firearms: List<Row>? = null
    private var fi = 0
    private var ammoRows: List<Row> = emptyList()
    private var ai = 0
    private val readFirearms = mutableListOf<AppFirearm>()
    private val readAmmo = mutableListOf<AppAmmo>()

    private var clicks = 0
    private var lastActAt = 0L
    private var backs = 0
    private var scanKind = Kind.FIREARM
    private var scanForward = true
    private var lastSig: String? = null
    private var sameCount = 0
    private var scrolls = 0
    private val scanned = mutableListOf<Row>()
    private var acc: FormAccumulator? = null
    private var advancedTapped = false
    private var cancelTaps = 0
    private var discardTaps = 0
    private var notForegroundSince = 0L
    private var unexpectedSince = 0L
    private var pendingFail: String? = null
    private var editorOpenedByUs = false

    var progress: String = "Opening the calculator…"
        private set
    val firearmsFound: Int get() = firearms?.size ?: 0
    val firearmsRead: Int get() = readFirearms.size
    val ammoRead: Int get() = readAmmo.size

    fun partial() = AppImport(readFirearms.toList(), readAmmo.toList(), warnings.toList())

    fun requestCancel(reason: String) {
        if (phase == Phase.DONE) return
        pendingFail = reason
        go(Phase.BAIL_OUT)
    }

    private fun go(p: Phase) {
        phase = p
        phaseStart = now
        clicks = 0; backs = 0; lastActAt = 0
        lastSig = null; sameCount = 0; scrolls = 0; scanForward = true
        unexpectedSince = 0
        cancelTaps = 0; discardTaps = 0
    }

    private fun since() = now - phaseStart

    private fun selectionNote(): String =
        if (!selectionChanged) "" else
            "\nThe calculator's selected firearm may have changed — it was \"${origFirearm.orEmpty()}\"" +
                (origAmmo?.let { " / \"$it\"" } ?: "") + "."

    private fun restoring() = goal == Goal.RESTORE_FIREARM || goal == Goal.RESTORE_AMMO || phase == Phase.RESTORE

    private fun fail(msg: String): ImportAction {
        if (restoring()) {
            warnings += "Couldn't put the calculator's selection back to \"${origFirearm.orEmpty()}\"" +
                (origAmmo?.let { " / \"$it\"" } ?: "") + " ($msg). Select it again there."
            phase = Phase.DONE
            return ImportAction.Finished(partial())
        }
        phase = Phase.DONE
        return ImportAction.Failed(msg + selectionNote(), partial())
    }

    private fun bail(msg: String): ImportAction {
        pendingFail = msg
        go(Phase.BAIL_OUT)
        return ImportAction.Wait(100)
    }

    private fun click(node: UiNode?, what: String, waitMs: Long = 900, allowCancelIcon: Boolean = false, allowDiscard: Boolean = false): ImportAction {
        if (node == null) return bail("Couldn't find \"$what\" on the calculator's screen. Stopped.")
        ImportSafety.violation(node, allowCancelIcon, allowDiscard)?.let {
            return bail("Refused to tap \"$what\": it would touch $it. Stopped; nothing was changed.")
        }
        lastActAt = now
        return ImportAction.Click(node, what, waitMs, allowCancelIcon, allowDiscard)
    }

    private fun longClick(node: UiNode, what: String): ImportAction {
        ImportSafety.violation(node)?.let { return bail("Refused to long-press \"$what\": it would touch $it. Stopped; nothing was changed.") }
        lastActAt = now
        return ImportAction.LongClick(node, what)
    }

    private fun back(why: String): ImportAction {
        backs++
        lastActAt = now
        return ImportAction.Back(why)
    }

    private fun describe(s: ImportScreen): String = when (s.kind) {
        ImportScreenKind.MAIN -> "the main screen"
        ImportScreenKind.FIREARM_LIST -> "the firearm list"
        ImportScreenKind.AMMO_LIST -> "the ammo list of \"${s.title.orEmpty()}\""
        ImportScreenKind.ITEM_MENU -> "the Edit / Duplicate / Delete menu"
        ImportScreenKind.FIREARM_EDITOR, ImportScreenKind.AMMO_EDITOR, ImportScreenKind.OTHER_EDITOR -> "the \"${s.title.orEmpty()}\" editor"
        ImportScreenKind.OTHER -> "an unrecognised screen" + visibleText(s).let { if (it.isEmpty()) "" else " (\"$it\")" }
    }

    private fun visibleText(s: ImportScreen): String =
        s.roots.firstOrNull()?.walk()?.filter { it.text.isNotEmpty() && !it.isEditText }?.take(4)?.joinToString(" · ") { it.text }.orEmpty()

    private fun unexpected(s: ImportScreen, waitingFor: String): ImportAction {
        if (unexpectedSince == 0L) unexpectedSince = now
        if (now - unexpectedSince > 1800) {
            return bail("Expected $waitingFor but the calculator shows ${describe(s)}. Stopped; nothing was changed.")
        }
        return ImportAction.Wait(300)
    }

    private fun timeout(ms: Long, what: String): ImportAction? =
        if (since() > ms) bail("Timed out waiting for $what. Stopped; nothing was changed.") else null

    fun next(o: Observation): ImportAction {
        now = o.now
        if (startedAt < 0) { startedAt = now; phaseStart = now }
        if (phase == Phase.DONE) return ImportAction.Wait(1000)
        if (now - startedAt > overallTimeoutMs && phase != Phase.BAIL_OUT) return bail("The import took too long and was stopped.")

        if (!o.foreground) {
            if (notForegroundSince == 0L) notForegroundSince = now
            val limit = if (phase == Phase.START) 25_000L else 6_000L
            if (now - notForegroundSince > limit) {
                return if (phase == Phase.START) fail("The calculator didn't open. Nothing was imported.")
                else fail("The calculator left the screen, so the import stopped. Nothing was changed there; nothing was imported.")
            }
            return ImportAction.Wait(400)
        }
        notForegroundSince = 0
        val s = ImportScreens.classify(o.roots)

        return when (phase) {
            Phase.START -> start(s)
            Phase.OPEN_FIREARMS -> openFirearms(s)
            Phase.SCAN_LIST -> scanList(s)
            Phase.FIND_ROW -> findRow(s)
            Phase.WAIT_MENU -> waitMenu(s)
            Phase.WAIT_EDITOR -> waitEditor(s)
            Phase.READ_FORM -> readForm(s)
            Phase.LEAVE_EDITOR -> leaveEditor(s)
            Phase.WAIT_SELECTED -> waitSelected(s)
            Phase.OPEN_AMMO -> openAmmo(s)
            Phase.LEAVE_AMMO_LIST -> leaveAmmoList(s)
            Phase.RESTORE -> restore(s)
            Phase.BAIL_OUT -> bailOut(s)
            Phase.DONE -> ImportAction.Wait(1000)
        }
    }

    private fun start(s: ImportScreen): ImportAction {
        progress = "Opening the calculator…"
        return when (s.kind) {
            ImportScreenKind.MAIN -> {
                val root = s.main!!
                origFirearm = ImportScreens.mainValue(root, AppImportUi.MAIN_FIREARM_ROW)
                origAmmo = ImportScreens.mainValue(root, CalcAppUi.MAIN_AMMO_ROW)
                goal = Goal.READ_FIREARM
                go(Phase.OPEN_FIREARMS)
                ImportAction.Wait(100)
            }
            ImportScreenKind.FIREARM_LIST, ImportScreenKind.AMMO_LIST, ImportScreenKind.ITEM_MENU -> {
                if (backs >= 4) fail("Couldn't get to the calculator's main screen. Open it there and try again.")
                else if (now - lastActAt < 900) ImportAction.Wait(300) else back("to the main screen")
            }
            ImportScreenKind.FIREARM_EDITOR, ImportScreenKind.AMMO_EDITOR, ImportScreenKind.OTHER_EDITOR ->
                fail("The calculator has an editor open (\"${s.title.orEmpty()}\"). Finish or close it there yourself, go to its main screen, then start again. Nothing was changed.")
            ImportScreenKind.OTHER ->
                if (since() > 8_000) fail("Open the calculator's main screen (the one with \"Firearm & Ammo\"), then start again. Nothing was changed.")
                else ImportAction.Wait(400)
        }
    }

    private fun openFirearms(s: ImportScreen): ImportAction {
        timeout(9_000, "the firearm list")?.let { return it }
        return when (s.kind) {
            ImportScreenKind.FIREARM_LIST -> {
                when {
                    firearms == null -> startScan(Kind.FIREARM)
                    goal == Goal.RESTORE_FIREARM -> { go(Phase.FIND_ROW); ImportAction.Wait(100) }
                    else -> { goal = Goal.READ_FIREARM; go(Phase.FIND_ROW); ImportAction.Wait(100) }
                }
            }
            ImportScreenKind.MAIN -> {
                if (clicks == 0 || (now - lastActAt > 3500 && clicks < 2)) {
                    clicks++
                    click(ImportScreens.mainRow(s.main!!, AppImportUi.MAIN_FIREARM_ROW), "Firearm row")
                } else ImportAction.Wait(300)
            }
            ImportScreenKind.OTHER -> ImportAction.Wait(300)
            else -> unexpected(s, "the firearm list")
        }
    }

    private fun startScan(k: Kind): ImportAction {
        scanKind = k
        scanned.clear()
        go(Phase.SCAN_LIST)
        scanForward = true
        progress = if (k == Kind.FIREARM) "Reading the firearm list…" else "Reading the ammo list of ${firearms!![fi].name}…"
        return ImportAction.Wait(300)
    }

    private fun scanList(s: ImportScreen): ImportAction {
        timeout(30_000, "the list to finish scrolling")?.let { return it }
        if (s.kind != scanKind.list) {
            return if (s.kind == ImportScreenKind.OTHER) ImportAction.Wait(300) else unexpected(s, "the list")
        }
        val root = s.main!!
        val rows = ImportScreens.listRows(root)
        val dup = rows.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (dup != null) {
            return fail("Two profiles in the calculator's list are named \"$dup\". Rename one there so they can be told apart, then try again. Nothing was imported.")
        }
        for (r in rows) if (scanned.none { it.name == r.name }) scanned += Row(r.name, r.subtitle)
        val sig = ImportScreens.signature(root)
        val sc = ImportScreens.scrollable(root)
        if (sig != lastSig && sc != null && scrolls < 60) {
            lastSig = sig; scrolls++
            return ImportAction.Scroll(sc, scanForward)
        }
        if (scanForward && sc != null) {
            scanForward = false
            lastSig = null
            return ImportAction.Wait(100)
        }
        return if (scanKind == Kind.FIREARM) {
            firearms = scanned.toList()
            fi = 0
            if (firearms!!.isEmpty()) { go(Phase.RESTORE); back("to the main screen") }
            else { goal = Goal.READ_FIREARM; go(Phase.FIND_ROW); ImportAction.Wait(100) }
        } else {
            ammoRows = scanned.toList()
            val fa = firearms!![fi]
            ImportScreens.ammoCount(fa.subtitle)?.let { n ->
                if (n != ammoRows.size) {
                    return fail("Found ${ammoRows.size} ammo profile(s) for \"${fa.name}\" but the calculator's firearm list says $n. Stopped so nothing is missed.")
                }
            }
            ai = 0
            if (ammoRows.isEmpty()) { go(Phase.LEAVE_AMMO_LIST); ImportAction.Wait(100) }
            else { goal = Goal.READ_AMMO; go(Phase.FIND_ROW); ImportAction.Wait(100) }
        }
    }

    private fun target(): Pair<Kind, String> = when (goal) {
        Goal.READ_FIREARM, Goal.SELECT_FIREARM -> Kind.FIREARM to firearms!![fi].name
        Goal.READ_AMMO -> Kind.AMMO to ammoRows[ai].name
        Goal.RESTORE_FIREARM -> Kind.FIREARM to origFirearm!!
        Goal.RESTORE_AMMO -> Kind.AMMO to origAmmo!!
    }

    private fun updateProgress() {
        val fl = firearms ?: return
        val fa = fl.getOrNull(fi)?.name ?: return
        progress = when (goal) {
            Goal.READ_FIREARM -> "Firearm ${fi + 1} of ${fl.size}: $fa"
            Goal.SELECT_FIREARM -> "Firearm ${fi + 1} of ${fl.size}: $fa — opening its ammo"
            Goal.READ_AMMO -> "Firearm ${fi + 1} of ${fl.size}: $fa — ammo ${ai + 1} of ${ammoRows.size}: ${ammoRows[ai].name}"
            Goal.RESTORE_FIREARM, Goal.RESTORE_AMMO -> "Putting the calculator's selection back…"
        }
    }

    private fun findRow(s: ImportScreen): ImportAction {
        timeout(30_000, "the profile in the list")?.let { return it }
        val (k, name) = target()
        updateProgress()
        if (s.kind != k.list) {
            return if (s.kind == ImportScreenKind.OTHER) ImportAction.Wait(300) else unexpected(s, "the list with \"$name\"")
        }
        val root = s.main!!
        val row = ImportScreens.listRows(root).firstOrNull { it.name == name }
        if (row != null) {
            val select = goal == Goal.SELECT_FIREARM || goal == Goal.RESTORE_FIREARM || goal == Goal.RESTORE_AMMO
            if (select) {
                go(Phase.WAIT_SELECTED)
                if (goal != Goal.RESTORE_FIREARM && goal != Goal.RESTORE_AMMO) selectionChanged = true
                return click(row.node, name, waitMs = 1200)
            }
            go(Phase.WAIT_MENU)
            clicks = 1
            return longClick(row.node, name)
        }
        val sig = ImportScreens.signature(root)
        val sc = ImportScreens.scrollable(root)
            ?: return fail("\"$name\" isn't in the calculator's list any more. Stopped; nothing was imported.")
        if (sig != lastSig && scrolls < 60) {
            lastSig = sig; scrolls++
            return ImportAction.Scroll(sc, scanForward)
        }
        if (scanForward) { scanForward = false; lastSig = null; return ImportAction.Wait(100) }
        return fail("\"$name\" isn't in the calculator's list any more. Stopped; nothing was imported.")
    }

    private fun waitMenu(s: ImportScreen): ImportAction {
        timeout(6_000, "the Edit menu")?.let { return it }
        val (k, name) = target()
        return when (s.kind) {
            ImportScreenKind.ITEM_MENU -> {
                go(Phase.WAIT_EDITOR)
                editorOpenedByUs = true
                click(ImportScreens.menuItem(s.main!!, AppImportUi.MENU_EDIT), "Edit", waitMs = 1300)
            }
            k.list -> {
                if (now - lastActAt > 3000 && clicks < 2) {
                    val row = ImportScreens.listRows(s.main!!).firstOrNull { it.name == name }
                        ?: return bail("\"$name\" disappeared from the list. Stopped.")
                    clicks++
                    longClick(row.node, name)
                } else ImportAction.Wait(300)
            }
            ImportScreenKind.OTHER -> ImportAction.Wait(300)
            else -> unexpected(s, "the Edit menu of \"$name\"")
        }
    }

    private fun waitEditor(s: ImportScreen): ImportAction {
        timeout(8_000, "the editor to open")?.let { return it }
        val (k, name) = target()
        return when (s.kind) {
            k.editor -> {
                acc = FormAccumulator(if (k == Kind.FIREARM) FormSpecs.FIREARM else FormSpecs.AMMO)
                advancedTapped = false
                go(Phase.READ_FORM)
                ImportAction.Wait(200)
            }
            ImportScreenKind.ITEM_MENU, k.list, ImportScreenKind.OTHER -> ImportAction.Wait(300)
            else -> unexpected(s, "the editor of \"$name\"")
        }
    }

    private fun readForm(s: ImportScreen): ImportAction {
        timeout(40_000, "the form to be read")?.let { return it }
        val (k, name) = target()
        if (s.kind != k.editor) {
            return if (s.kind == ImportScreenKind.OTHER) ImportAction.Wait(300) else bail("The editor of \"$name\" closed while it was being read. Stopped.")
        }
        val root = s.main!!
        val a = acc!!
        val m = EditorMatcher(root)
        if (k == Kind.AMMO && !advancedTapped && m.hasLabel(CalcAppUi.ADVANCED) && !m.hasLabel(CalcAppUi.NOSE_LENGTH) &&
            m.hasLabel(CalcAppUi.MUZZLE_VELOCITY)
        ) {
            advancedTapped = true
            val adv = m.labels(CalcAppUi.ADVANCED).firstOrNull()?.let { EditorMatcher.clickableSelfOrAncestor(it) }
            if (adv != null) return click(adv, "Advanced", waitMs = 800)
        }
        a.add(root)
        if (a.pages == 1) {
            val shown = a.firstProfile
                ?: return bail("Couldn't read the Profile name in the editor of \"$name\". Stopped.")
            if (shown != name) return bail("The editor shows \"$shown\", not \"$name\". Stopped; nothing was changed.")
        }
        if (a.pages > 25) return bail("The form of \"$name\" didn't end after 25 pages. Stopped.")
        val sig = ImportScreens.signature(root)
        val sc = ImportScreens.scrollable(root)
        if (sig != lastSig && sc != null) {
            lastSig = sig; sameCount = 0
            return ImportAction.Scroll(sc, forward = true)
        }
        if (sc != null && sameCount == 0) {
            sameCount = 1
            return ImportAction.Wait(600)
        }
        try {
            if (k == Kind.FIREARM) {
                val row = firearms!![fi]
                readFirearms += ProfileReader.firearm(a, row.name, row.subtitle)
            } else {
                val row = ammoRows[ai]
                readAmmo += ProfileReader.ammo(a, firearms!![fi].name, row.name, row.subtitle)
            }
        } catch (e: ImportProblem) {
            return bail(e.message ?: "Couldn't read \"$name\".")
        }
        acc = null
        go(Phase.LEAVE_EDITOR)
        return back("leave the editor of \"$name\" without saving")
    }

    private fun leaveEditor(s: ImportScreen): ImportAction {
        val (k, name) = target()
        if (since() > 9_000) return fail("The editor of \"$name\" didn't close. Close it there with ← (don't save). Nothing was changed.")
        if (s.kind == k.list) {
            editorOpenedByUs = false
            return afterEditorLeft()
        }
        val discard = s.roots.asSequence().flatMap { it.walk() }
            .filter { !it.isEditText && it.text.trim().lowercase() in ImportSafety.DISCARD_OPTIONS }
            .mapNotNull { EditorMatcher.clickableSelfOrAncestor(it) }
            .firstOrNull()
        if (discard != null) {
            if (discardTaps > 0) return ImportAction.Wait(300)
            discardTaps++
            return click(discard, discard.walk().first { it.text.isNotEmpty() }.text, allowDiscard = true)
        }
        val popup = s.roots.firstOrNull { r -> r !== s.main && r.walk().any { it.clickable } }
        if (popup != null || s.kind != k.editor) {
            if (since() < 4_000) return ImportAction.Wait(300)
            val txt = (popup ?: s.roots.firstOrNull())?.walk()?.filter { it.text.isNotEmpty() && !it.isEditText }?.take(4)
                ?.joinToString(" · ") { it.text }.orEmpty()
            return fail("After leaving the editor of \"$name\" the calculator shows ${if (popup != null) "a question" else describe(s)}" +
                (if (txt.isNotEmpty()) " (\"$txt\")" else "") +
                ". Stopped without answering; nothing was changed — choose its option to discard there.")
        }
        if (now - lastActAt > 2500 && cancelTaps == 0) {
            cancelTaps++
            val icon = s.main!!.walk().firstOrNull { it.desc.equals(CalcAppUi.DESC_CANCEL, true) }
                ?.let { EditorMatcher.clickableSelfOrAncestor(it) }
            return click(icon, "✕ (close without saving)", allowCancelIcon = true)
        }
        return ImportAction.Wait(300)
    }

    private fun afterEditorLeft(): ImportAction = when (goal) {
        Goal.READ_FIREARM -> {
            goal = Goal.SELECT_FIREARM
            go(Phase.FIND_ROW)
            ImportAction.Wait(300)
        }
        Goal.READ_AMMO -> {
            ai++
            if (ai < ammoRows.size) { go(Phase.FIND_ROW); ImportAction.Wait(300) }
            else { go(Phase.LEAVE_AMMO_LIST); ImportAction.Wait(300) }
        }
        else -> fail("Internal: unexpected state after leaving an editor.")
    }

    private fun waitSelected(s: ImportScreen): ImportAction {
        val (k, name) = target()
        if (since() > 7_000) return bail("Selecting \"$name\" in the calculator didn't take effect. Stopped.")
        val label = if (k == Kind.FIREARM) AppImportUi.MAIN_FIREARM_ROW else CalcAppUi.MAIN_AMMO_ROW
        return when (s.kind) {
            ImportScreenKind.MAIN -> {
                val shown = ImportScreens.mainValue(s.main!!, label)
                if (shown != name) return ImportAction.Wait(300)
                when (goal) {
                    Goal.SELECT_FIREARM -> { go(Phase.OPEN_AMMO); ImportAction.Wait(200) }
                    Goal.RESTORE_FIREARM -> { restoreStage = 1; go(Phase.RESTORE); ImportAction.Wait(200) }
                    Goal.RESTORE_AMMO -> { restoreStage = 2; go(Phase.RESTORE); ImportAction.Wait(200) }
                    else -> fail("Internal: unexpected state after selecting.")
                }
            }
            k.list -> if (now - lastActAt > 1500 && backs == 0) back("to the main screen") else ImportAction.Wait(300)
            ImportScreenKind.OTHER -> ImportAction.Wait(300)
            else -> unexpected(s, "the main screen")
        }
    }

    private fun openAmmo(s: ImportScreen): ImportAction {
        timeout(9_000, "the ammo list")?.let { return it }
        val faName = if (goal == Goal.RESTORE_AMMO) origFirearm!! else firearms!![fi].name
        return when (s.kind) {
            ImportScreenKind.AMMO_LIST -> {
                if (s.title != faName) return bail("Expected the ammo of \"$faName\" but the calculator shows the ammo of \"${s.title.orEmpty()}\". Stopped.")
                if (goal == Goal.RESTORE_AMMO) { go(Phase.FIND_ROW); ImportAction.Wait(100) } else startScan(Kind.AMMO)
            }
            ImportScreenKind.MAIN -> {
                if (clicks == 0 || (now - lastActAt > 3500 && clicks < 2)) {
                    clicks++
                    click(ImportScreens.mainRow(s.main!!, CalcAppUi.MAIN_AMMO_ROW), "Ammo row")
                } else ImportAction.Wait(300)
            }
            ImportScreenKind.OTHER -> ImportAction.Wait(300)
            else -> unexpected(s, "the ammo list")
        }
    }

    private fun leaveAmmoList(s: ImportScreen): ImportAction {
        timeout(8_000, "the main screen")?.let { return it }
        return when (s.kind) {
            ImportScreenKind.AMMO_LIST -> if (backs == 0 || now - lastActAt > 3000 && backs < 2) back("to the main screen") else ImportAction.Wait(300)
            ImportScreenKind.MAIN -> {
                fi++
                if (fi < firearms!!.size) { goal = Goal.READ_FIREARM; go(Phase.OPEN_FIREARMS) } else go(Phase.RESTORE)
                ImportAction.Wait(200)
            }
            ImportScreenKind.OTHER -> ImportAction.Wait(300)
            else -> unexpected(s, "the main screen")
        }
    }

    private fun restore(s: ImportScreen): ImportAction {
        progress = "Putting the calculator's selection back…"
        if (since() > 9_000) return fail("timed out")
        if (s.kind != ImportScreenKind.MAIN) {
            if (s.kind == ImportScreenKind.FIREARM_LIST && backs == 0) return back("to the main screen")
            return ImportAction.Wait(300)
        }
        val root = s.main!!
        if (restoreStage == 0) {
            restoreStage = 1
            val cur = ImportScreens.mainValue(root, AppImportUi.MAIN_FIREARM_ROW)
            val known = firearms?.any { it.name == origFirearm } == true
            if (origFirearm != null && cur != origFirearm && known) {
                goal = Goal.RESTORE_FIREARM
                go(Phase.OPEN_FIREARMS)
                return ImportAction.Wait(100)
            }
        }
        if (restoreStage == 1) {
            restoreStage = 2
            val cur = ImportScreens.mainValue(root, CalcAppUi.MAIN_AMMO_ROW)
            val known = readAmmo.any { it.firearm == origFirearm && it.name == origAmmo }
            if (origAmmo != null && cur != origAmmo && known) {
                goal = Goal.RESTORE_AMMO
                go(Phase.OPEN_AMMO)
                return ImportAction.Wait(100)
            }
        }
        phase = Phase.DONE
        progress = "Done"
        return ImportAction.Finished(partial())
    }

    private fun bailOut(s: ImportScreen): ImportAction {
        val ours = s.kind == ImportScreenKind.ITEM_MENU ||
            (editorOpenedByUs && (s.kind == ImportScreenKind.FIREARM_EDITOR || s.kind == ImportScreenKind.AMMO_EDITOR))
        if (ours && backs < 2) {
            if (now - lastActAt < 1200) return ImportAction.Wait(300)
            return back("leave without saving")
        }
        editorOpenedByUs = false
        return fail(pendingFail ?: "Stopped.")
    }
}
