package com.longrange.dragcurve.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.TwistDirection
import com.longrange.dragcurve.store.newId
import com.longrange.dragcurve.ui.calc.BcSelection
import com.longrange.dragcurve.ui.calc.BulletBook
import com.longrange.dragcurve.ui.calc.BulletSearch
import com.longrange.dragcurve.ui.calc.DragSource
import com.longrange.dragcurve.ui.calc.LoadFlags
import com.longrange.dragcurve.ui.calc.Num
import com.longrange.dragcurve.ui.calc.Quantity
import com.longrange.dragcurve.ui.calc.RifleText
import java.util.concurrent.atomic.AtomicLong

@Composable
fun GearScreen(st: AppState) {
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(st.newAmmoBullet) { if (st.newAmmoBullet != null) tab = 1 }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("My gear", "Your firearms, the ammo you shoot in them, and bullets you entered yourself. Everything is saved as soon as you press Save.")
        TabRow(selectedTabIndex = tab) {
            listOf("Firearms", "Ammo", "My bullets").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Gap(12)
        when (tab) {
            0 -> FirearmsTab(st)
            1 -> AmmoTab(st)
            else -> BulletsTab(st)
        }
    }
}

@Composable
private fun <T> MasterDetail(
    items: List<T>,
    id: (T) -> String,
    title: (T) -> String,
    subtitle: (T) -> String,
    selectedId: String?,
    onSelect: (T) -> Unit,
    addLabel: String,
    onAdd: (() -> Unit)?,
    empty: String,
    top: @Composable () -> Unit = {},
    editor: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onAdd != null) Button(onClick = onAdd) { Icon(Icons.Filled.Add, null); Text(" $addLabel") }
            top()
            if (items.isEmpty()) Note(empty)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { id(it) }) { item ->
                    val sel = id(item) == selectedId
                    Card(
                        Modifier.fillMaxWidth().clickable { onSelect(item) },
                        colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(title(item), fontWeight = FontWeight.SemiBold)
                            subtitle(item).takeIf { it.isNotBlank() }?.let { Note(it) }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { editor() }
    }
}

private class Bad {
    val map = mutableStateMapOf<String, String>()
    fun err(k: String) = map[k]
    fun num(k: String, v: Double?, ok: (Double) -> Boolean, message: String, set: (Double) -> Unit) {
        if (v != null && ok(v)) { map.remove(k); set(v) } else map[k] = message
    }
    val any get() = map.isNotEmpty()
}

@Composable
private fun EditorButtons(isNew: Boolean, onSave: () -> Unit, onDelete: (() -> Unit)?, extra: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSave) { Text(if (isNew) "Add" else "Save") }
        extra()
        if (!isNew && onDelete != null) TextButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null); Text(" Delete") }
    }
}

@Composable
private fun FirearmsTab(st: AppState) {
    val rifles = remember(st.rev) { st.store.rifles().sortedBy { it.name.lowercase() } }
    var current by remember { mutableStateOf<Rifle?>(null) }
    MasterDetail(
        rifles, { it.id }, { it.name },
        { r -> RifleText.subtitle(r) + " · " + st.store.loads(r.id).size + " ammo" },
        current?.id, { current = it }, "Add firearm", { current = Rifle(id = "", name = "") },
        "No firearms yet. Press Add firearm, or import your data from the phone under Backup & move data.",
    ) {
        val c = current
        if (c == null) Note("Choose a firearm on the left to change it, or add a new one.")
        else key(c.id) { RifleEditor(st, c, onSaved = { current = it }, onDeleted = { current = null }) }
    }
}

@Composable
private fun RifleEditor(st: AppState, initial: Rifle, onSaved: (Rifle) -> Unit, onDeleted: () -> Unit) {
    val fmt = Fmt(st.settings)
    var r by remember { mutableStateOf(initial) }
    val bad = remember { Bad() }
    val isNew = initial.id.isEmpty()
    fun defaultClick(u: AngleUnit) = when (u) { AngleUnit.MIL -> 0.1; AngleUnit.MOA -> 0.25; AngleUnit.IPHY -> 0.25 }
    ScrollColumn(Modifier.fillMaxSize()) {
        Text(if (isNew) "New firearm" else r.name.ifBlank { "Firearm" }, style = MaterialTheme.typography.titleLarge)
        Section("Firearm") {
            FieldRow {
                TextBox("Name", r.name, { r = r.copy(name = it) }, Modifier.weight(1f), error = bad.err("name"))
                TextBox("Cartridge (optional)", r.cartridge, { r = r.copy(cartridge = it) }, Modifier.weight(1f))
            }
            FieldRow {
                NumberField("Barrel twist: 1 turn in", r.twistIn, { v -> bad.num("twist", v, { it in 3.0..60.0 }, "From 3 to 60 in") { r = r.copy(twistIn = it) } },
                    Modifier.weight(1f), unit = "in", decimals = 2, error = bad.err("twist"))
                Column(Modifier.weight(1f)) {
                    Note("Twist direction")
                    Choice(listOf(TwistDirection.RIGHT, TwistDirection.LEFT), r.twistDirection, { if (it == TwistDirection.RIGHT) "Right" else "Left" }, { r = r.copy(twistDirection = it) })
                }
            }
            NumberField("Scope height above the bore (centre to centre)", r.sightHeightIn,
                { v -> bad.num("sight", v, { it >= 0 && it < 10 }, "Enter the height, for example 1.5 in") { r = r.copy(sightHeightIn = it) } },
                Modifier.fillMaxWidth(), fmt, Quantity.LENGTH, error = bad.err("sight"))
        }
        Section("Scope turrets") {
            FieldRow {
                Dropdown("Elevation turret", r.elevationUnit, AngleUnit.entries, { CalcCore.unitName(it) },
                    { u -> r = r.copy(elevationUnit = u, elevationClick = if (r.elevationClick == defaultClick(r.elevationUnit)) defaultClick(u) else r.elevationClick) },
                    Modifier.weight(1f))
                NumberField("One click is", r.elevationClick, { v -> bad.num("eclick", v, { it > 0 && it <= 5 }, "Enter the click value, e.g. 0.1") { r = r.copy(elevationClick = it) } },
                    Modifier.weight(1f), unit = com.longrange.dragcurve.ui.calc.Adjust.unitLabel(r.elevationUnit), decimals = 4, error = bad.err("eclick"))
            }
            FieldRow {
                Dropdown("Windage turret", r.windageUnit, AngleUnit.entries, { CalcCore.unitName(it) },
                    { u -> r = r.copy(windageUnit = u, windageClick = if (r.windageClick == defaultClick(r.windageUnit)) defaultClick(u) else r.windageClick) },
                    Modifier.weight(1f))
                NumberField("One click is", r.windageClick, { v -> bad.num("wclick", v, { it > 0 && it <= 5 }, "Enter the click value, e.g. 0.1") { r = r.copy(windageClick = it) } },
                    Modifier.weight(1f), unit = com.longrange.dragcurve.ui.calc.Adjust.unitLabel(r.windageUnit), decimals = 4, error = bad.err("wclick"))
            }
            Note("Most MIL scopes move 0.1 MIL per click; most MOA scopes move 1/4 MOA (0.25).")
        }
        Section("Notes") {
            TextBox("Notes", r.notes, { r = r.copy(notes = it) }, Modifier.fillMaxWidth(), singleLine = false)
        }
        EditorButtons(isNew, onSave = {
            if (r.name.isBlank()) bad.map["name"] = "Give the firearm a name" else bad.map.remove("name")
            if (bad.any) return@EditorButtons
            val toSave = r.copy(name = r.name.trim(), id = r.id.ifEmpty { newId() })
            st.store.saveRifle(toSave)
            st.changed()
            onSaved(st.store.rifles().first { it.id == toSave.id })
        }, onDelete = {
            val n = st.store.loads(r.id).size
            val shots = st.store.shotRecords(r.id).sumOf { it.count }
            st.ask("Delete ${r.name}?",
                "This also deletes its $n ammo" + (if (shots > 0) " and its round count ($shots rounds)" else "") +
                    ". This can't be undone.", "Delete") {
                st.store.deleteRifle(r.id); st.changed(); onDeleted()
            }
        })
    }
}

@Composable
private fun AmmoTab(st: AppState) {
    val rifles = remember(st.rev) { st.store.rifles().sortedBy { it.name.lowercase() } }
    var filter by remember { mutableStateOf<Rifle?>(null) }
    val loads = remember(st.rev, filter) {
        st.store.loads(filter?.id).sortedWith(compareBy({ l -> rifles.firstOrNull { it.id == l.rifleId }?.name?.lowercase() ?: "" }, { it.name.lowercase() }))
    }
    var current by remember { mutableStateOf<Load?>(null) }
    LaunchedEffect(st.newAmmoBullet) {
        val b = st.newAmmoBullet ?: return@LaunchedEffect
        st.newAmmoBullet = null
        val r = filter ?: rifles.firstOrNull()
        if (r != null) current = newLoad(r.id, st.store.bullet(b))
    }
    MasterDetail(
        loads, { it.id }, { it.name },
        { l ->
            listOfNotNull(
                if (filter == null) rifles.firstOrNull { it.id == l.rifleId }?.name else null,
                BcSelection.summary(l, st.store.bullet(l.bulletId)),
                Num.trim(Fmt(st.settings).disp(Quantity.VELOCITY, l.muzzleVelocityFps), 0) + " " + Fmt(st.settings).label(Quantity.VELOCITY),
                LoadFlags.text(l),
            ).joinToString(" · ")
        },
        current?.id, { current = it }, "Add ammo",
        if (rifles.isEmpty()) null else ({ current = newLoad((filter ?: rifles.first()).id, null) }),
        if (rifles.isEmpty()) "Add a firearm first: ammo always belongs to a firearm." else "No ammo yet. Press Add ammo.",
        top = {
            if (rifles.isNotEmpty()) Dropdown("Show ammo for", filter, listOf<Rifle?>(null) + rifles, { it?.name ?: "All firearms" }, { filter = it }, Modifier.fillMaxWidth(), placeholder = "All firearms")
        },
    ) {
        val c = current
        if (c == null) Note("Choose ammo on the left to change it, or add new ammo.")
        else key(c.id, c.bulletId) { AmmoEditor(st, c, rifles, onSaved = { current = it }, onDeleted = { current = null }) }
    }
}

private fun newLoad(rifleId: String, bullet: Bullet?) = Load(
    id = "", rifleId = rifleId, name = bullet?.let { BulletSearch.title(it) } ?: "", bulletId = bullet?.id ?: "",
    muzzleVelocityFps = 2700.0, zeroRangeYd = 100.0,
)

data class EditRow(val key: Long, val bc: Double?, val minVel: Double?)

private val rowKeys = AtomicLong()
fun editRows(points: List<BcPoint>): List<EditRow> =
    (points.ifEmpty { listOf(BcPoint(0.0, 0.0)) }).sortedByDescending { it.minVelFps }
        .map { EditRow(rowKeys.incrementAndGet(), it.bc.takeIf { b -> b > 0 }, it.minVelFps) }

fun checkRows(rows: List<EditRow>): Pair<List<BcPoint>?, String?> {
    if (rows.any { it.bc == null || it.minVel == null }) return null to "Fill in every BC row, or remove the empty ones."
    val pts = rows.map { BcPoint(it.bc!!, it.minVel!!) }
    BcSelection.validate(pts)?.let { return null to it }
    if (pts.any { it.bc < BulletBook.MIN_BC || it.bc > BulletBook.MAX_BC }) return null to "A BC must be between 0.05 and 1.5"
    return pts to null
}

@Composable
fun BcRowsEditor(rows: List<EditRow>, onChange: (List<EditRow>) -> Unit, fmt: Fmt, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { i, row ->
            key(row.key) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("BC", row.bc, { v -> onChange(rows.toMutableList().also { it[i] = row.copy(bc = v) }) }, Modifier.weight(1f), decimals = 4)
                    NumberField("Used from velocity", row.minVel, { v -> onChange(rows.toMutableList().also { it[i] = row.copy(minVel = v) }) },
                        Modifier.weight(1f), fmt, Quantity.VELOCITY)
                    IconButton(onClick = { onChange(rows.filterIndexed { j, _ -> j != i }) }, enabled = rows.size > 1) { Icon(Icons.Filled.Delete, "Remove row") }
                }
            }
        }
        TextButton(onClick = { onChange(rows + EditRow(rowKeys.incrementAndGet(), null, null)) }) { Icon(Icons.Filled.Add, null); Text(" Add a BC row") }
        Note("With one BC, leave its velocity at 0. With several (for example from the bullet maker's velocity bands), each BC is used from its velocity up, and one row must start at 0.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AmmoEditor(st: AppState, initial: Load, rifles: List<Rifle>, onSaved: (Load) -> Unit, onDeleted: () -> Unit) {
    val fmt = Fmt(st.settings)
    val store = st.store
    val isNew = initial.id.isEmpty()
    var l by remember { mutableStateOf(initial) }
    var bullet by remember { mutableStateOf(store.bullet(initial.bulletId)) }
    var src by remember { mutableStateOf(bullet?.let { b -> if (isNew) BcSelection.default(b) else BcSelection.detect(initial, b) }) }
    var customModel by remember { mutableStateOf((initial.dragModelOverride ?: bullet?.dragModel)?.takeIf { it != DragModel.CUSTOM } ?: DragModel.G7) }
    var rows by remember { mutableStateOf(editRows(initial.bcPointsOverride ?: bullet?.bcPoints.orEmpty())) }
    var rowsError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val bad = remember { Bad() }

    ScrollColumn(Modifier.fillMaxSize()) {
        Text(if (isNew) "New ammo" else l.name.ifBlank { "Ammo" }, style = MaterialTheme.typography.titleLarge)
        Section("Ammo") {
            FieldRow {
                TextBox("Name", l.name, { l = l.copy(name = it) }, Modifier.weight(1f), error = bad.err("name"))
                Dropdown("Firearm", rifles.firstOrNull { it.id == l.rifleId }, rifles, { it.name }, { l = l.copy(rifleId = it.id) }, Modifier.weight(1f))
            }
        }
        Section("Bullet") {
            val b = bullet
            if (b == null) Text(if (l.bulletId.isEmpty()) "No bullet chosen yet." else "This ammo's bullet is missing.", color = MaterialTheme.colorScheme.error)
            else {
                Text(BulletSearch.title(b), fontWeight = FontWeight.SemiBold)
                Note(BulletSearch.sourceLabel(b) + " · " + BulletSearch.subtitle(b))
            }
            OutlinedButton(onClick = { picking = true }) { Text(if (b == null) "Choose bullet…" else "Change bullet…") }
            if (b != null) {
                val choices = BcSelection.choices(b)
                Dropdown("Drag data to use", src, choices, { it.label }, { src = it }, Modifier.fillMaxWidth())
                if (src == DragSource.CUSTOM) {
                    Choice(listOf(DragModel.G7, DragModel.G1), customModel, { it.name }, { customModel = it })
                    BcRowsEditor(rows, { rows = it; rowsError = null }, fmt, rowsError)
                }
            }
        }
        Section("Speed and zero") {
            FieldRow {
                NumberField("Muzzle velocity", l.muzzleVelocityFps, { v -> bad.num("mv", v, { it in 300.0..5000.0 }, "Enter the muzzle velocity") { l = l.copy(muzzleVelocityFps = it) } },
                    Modifier.weight(1f), fmt, Quantity.VELOCITY, error = bad.err("mv"))
                NumberField("Zero distance", l.zeroRangeYd, { v -> bad.num("zero", v, { it in 10.0..1500.0 }, "Enter the distance you zeroed at") { l = l.copy(zeroRangeYd = it) } },
                    Modifier.weight(1f), fmt, Quantity.DISTANCE, error = bad.err("zero"))
            }
            FieldRow {
                NumberField("Hits above aim at the zero", l.zeroHeightIn, { v -> bad.num("zh", v, { it > -50 && it < 50 }, "Enter a number (0 = dead on)") { l = l.copy(zeroHeightIn = it) } },
                    Modifier.weight(1f), fmt, Quantity.LENGTH, error = bad.err("zh"), help = "0 if it hits where you aim")
                NumberField("Hits right of aim at the zero", l.zeroOffsetIn, { v -> bad.num("zo", v, { it > -50 && it < 50 }, "Enter a number (0 = dead on)") { l = l.copy(zeroOffsetIn = it) } },
                    Modifier.weight(1f), fmt, Quantity.LENGTH, error = bad.err("zo"), help = "Left is negative")
            }
        }
        Section("Powder temperature (optional)") {
            Note("If your muzzle velocity changes with temperature, enter the temperature it was measured at and how much it changes per degree. Leave the change at 0 to ignore temperature.")
            FieldRow {
                NumberField("Velocity measured at", l.powderTempF, { v -> bad.num("pt", v, { it > -60 && it < 160 }, "Enter a temperature") { l = l.copy(powderTempF = it) } },
                    Modifier.weight(1f), fmt, Quantity.TEMPERATURE, error = bad.err("pt"))
                NumberField("Change per degree", l.velocityPerDegFps, { v -> bad.num("vpd", v, { it > -20 && it < 20 }, "Enter a number (0 = off)") { l = l.copy(velocityPerDegFps = it) } },
                    Modifier.weight(1f), fmt, Quantity.VEL_PER_DEG, error = bad.err("vpd"))
            }
        }
        Section("Notes") { TextBox("Notes", l.notes, { l = l.copy(notes = it) }, Modifier.fillMaxWidth(), singleLine = false) }
        EditorButtons(isNew, onSave = {
            if (l.name.isBlank()) bad.map["name"] = "Give the ammo a name" else bad.map.remove("name")
            val b = bullet
            if (b == null) { st.tell("Choose a bullet", "Ammo needs a bullet: choose one of your own bullets or one from the bullet library."); return@EditorButtons }
            if (bad.any) return@EditorButtons
            val s = src ?: BcSelection.default(b)
            var custom = emptyList<BcPoint>()
            if (s == DragSource.CUSTOM) {
                val (pts, err) = checkRows(rows)
                if (pts == null) { rowsError = err; return@EditorButtons }
                custom = pts
            }
            val toSave = BcSelection.apply(l.copy(name = l.name.trim(), bulletId = b.id, id = l.id.ifEmpty { newId() }, zeroConfirmed = true), b, s, customModel, custom)
            store.saveLoad(toSave)
            st.changed()
            onSaved(store.loads(null).first { it.id == toSave.id })
        }, onDelete = {
            st.ask("Delete ${l.name}?", "The ammo is removed from this computer. Its bullet stays.", "Delete") {
                store.deleteLoad(l.id); st.changed(); onDeleted()
            }
        })
    }
    if (picking) BulletPicker(st, onPick = { b ->
        picking = false
        bullet = b
        l = l.copy(bulletId = b.id, name = l.name.ifBlank { BulletSearch.title(b) })
        src = BcSelection.default(b)
        customModel = b.dragModel.takeIf { it != DragModel.CUSTOM } ?: DragModel.G7
        rows = editRows(b.bcPoints)
    }, onClose = { picking = false })
}

@Composable
fun BulletPicker(st: AppState, onPick: (Bullet) -> Unit, onClose: () -> Unit) {
    val all = remember(st.rev) { BulletBook.ordered(st.store.userBullets() + st.store.libraryBullets()) }
    var q by remember { mutableStateOf("") }
    val shown = remember(all, q) { all.filter { BulletSearch.matches(it, q) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Choose a bullet") },
        text = {
            Column(Modifier.width(620.dp).height(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBox("Search: maker, name, calibre or weight (e.g. 140 eld)", q, { q = it }, Modifier.fillMaxWidth())
                if (all.isEmpty()) Note("No bullets yet. Add your own under My gear > My bullets, or import a bullet library under Bullet library.")
                else Note("${shown.size} of ${all.size} bullets")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown.take(500), key = { it.id }) { b ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                            Text(BulletSearch.title(b), fontWeight = FontWeight.SemiBold)
                            Note(BulletSearch.sourceLabel(b) + " · " + BulletSearch.subtitle(b))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun BulletsTab(st: AppState) {
    val bullets = remember(st.rev) { BulletBook.ordered(st.store.userBullets()) }
    var current by remember { mutableStateOf<Bullet?>(null) }
    MasterDetail(
        bullets, { it.id }, { BulletSearch.title(it) }, { BulletSearch.subtitle(it) },
        current?.id, { current = it }, "Add bullet",
        { current = Bullet(id = "", name = "", diameterIn = 0.264, weightGr = 140.0, dragModel = DragModel.G7) },
        "No bullets of your own yet. You only need them for bullets that are not in the bullet library.",
    ) {
        val c = current
        if (c == null) Note("Choose a bullet on the left to change it, or add one with its BC from the box or the maker's website.")
        else key(c.id) { BulletEditor(st, c, onSaved = { current = it }, onDeleted = { current = null }) }
    }
}

@Composable
private fun BulletEditor(st: AppState, initial: Bullet, onSaved: (Bullet) -> Unit, onDeleted: () -> Unit) {
    val fmt = Fmt(st.settings)
    val isNew = initial.id.isEmpty()
    var b by remember { mutableStateOf(initial) }
    var rows by remember { mutableStateOf(editRows(initial.bcPoints)) }
    var rowsError by remember { mutableStateOf<String?>(null) }
    var lengthText by remember { mutableStateOf(initial.lengthIn) }
    val bad = remember { Bad() }
    val models = buildList { add(DragModel.G7); add(DragModel.G1); if (initial.dragCurve.size >= 2) add(DragModel.CUSTOM) }
    ScrollColumn(Modifier.fillMaxSize()) {
        Text(if (isNew) "New bullet" else BulletSearch.title(b), style = MaterialTheme.typography.titleLarge)
        Section("Bullet") {
            FieldRow {
                TextBox("Maker", b.maker, { b = b.copy(maker = it) }, Modifier.weight(1f))
                TextBox("Name", b.name, { b = b.copy(name = it) }, Modifier.weight(1f), error = bad.err("name"))
            }
            FieldRow {
                TextBox("Calibre (optional)", b.caliber, { b = b.copy(caliber = it) }, Modifier.weight(1f))
                NumberField("Diameter", b.diameterIn, { v -> bad.num("dia", v, { BulletBook.diameterProblem(it) == null }, BulletBook.diameterProblem(v) ?: "") { b = b.copy(diameterIn = it) } },
                    Modifier.weight(1f), unit = "in", decimals = 4, error = bad.err("dia"))
            }
            FieldRow {
                NumberField("Weight", b.weightGr, { v -> bad.num("wt", v, { BulletBook.weightProblem(it) == null }, BulletBook.weightProblem(v) ?: "") { b = b.copy(weightGr = it) } },
                    Modifier.weight(1f), unit = "gr", decimals = 1, error = bad.err("wt"))
                NumberField("Length (optional)", lengthText, { v -> lengthText = v; if (v == null || v in 0.2..3.0) bad.map.remove("len") else bad.map["len"] = "From 0.2 to 3 in, or leave blank" },
                    Modifier.weight(1f), unit = "in", decimals = 4, error = bad.err("len"), help = "Improves spin drift; estimated when blank")
            }
        }
        Section("Ballistic coefficient") {
            Dropdown("Drag model", b.dragModel, models, { if (it == DragModel.CUSTOM) "Measured drag curve (${initial.dragCurve.size} points)" else it.name }, { b = b.copy(dragModel = it) }, Modifier.width(320.dp))
            if (b.dragModel == DragModel.CUSTOM) Note("This bullet flies on its measured drag curve. Switch to G7 or G1 to use BC rows instead.")
            else {
                Note("G7 suits long boat-tail match bullets; G1 is what most boxes print. Use the model your BC is for.")
                BcRowsEditor(rows, { rows = it; rowsError = null }, fmt, rowsError)
            }
        }
        Section("Notes") { TextBox("Notes", b.notes, { b = b.copy(notes = it) }, Modifier.fillMaxWidth(), singleLine = false) }
        EditorButtons(isNew, onSave = {
            if (b.name.isBlank()) bad.map["name"] = "Give the bullet a name" else bad.map.remove("name")
            if (bad.any) return@EditorButtons
            var pts = b.bcPoints
            if (b.dragModel != DragModel.CUSTOM) {
                val (p, err) = checkRows(rows)
                if (p == null) { rowsError = err; return@EditorButtons }
                pts = p
            }
            val saved = st.store.saveUserBullet(b.copy(id = b.id.ifEmpty { newId() }, name = b.name.trim(), maker = b.maker.trim(),
                lengthIn = lengthText, bcPoints = pts, source = "user"))
            st.changed()
            onSaved(st.store.userBullets().firstOrNull { it.id == saved.id } ?: saved)
        }, onDelete = {
            st.ask("Delete ${BulletSearch.title(b)}?", "The bullet is removed from My bullets.", "Delete") {
                if (st.store.deleteBullet(b.id)) { st.changed(); onDeleted() }
                else st.tell("Can't delete this bullet", "Some of your ammo uses it. Change or delete that ammo first.")
            }
        })
    }
}
