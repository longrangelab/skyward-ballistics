package com.longrange.dragcurve.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longrange.dragcurve.analysis.HitSim
import com.longrange.dragcurve.analysis.TnfCompare
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.ui.calc.CalcGuard
import com.longrange.dragcurve.ui.calc.Num
import com.longrange.dragcurve.ui.calc.Quantity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

private object AnalysisMemory {
    var hit: HitSim.Inputs? = null
    var hitReport: HitSim.Report? = null
    val tnf = mutableStateListOf<TnfCompare.Entry>()
    var tnfRef: Int? = null
}

@Composable
fun AnalysisScreen(st: AppState) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Analysis", "How likely a hit is, and how different ammo compares in the wind.")
        TabRow(selectedTabIndex = tab, modifier = Modifier.width(520.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Hit probability") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Compare ammo in the wind (TNF)") })
        }
        Gap(12)
        if (tab == 0) HitScreen(st) else TnfScreen(st)
    }
}

@Composable
private fun HitScreen(st: AppState) {
    val store = st.store
    val fmt = Fmt(st.settings)
    val rifles = remember(st.rev) { store.rifles() }
    var sel by remember { mutableStateOf(store.selection()) }
    val rifle = rifles.firstOrNull { it.id == sel.first } ?: rifles.firstOrNull()
    val loads = remember(st.rev, rifle?.id) { rifle?.let { store.loads(it.id) } ?: emptyList() }
    val load = loads.firstOrNull { it.id == sel.second } ?: loads.firstOrNull()
    val last = remember { store.lastShot() }
    var i by remember { mutableStateOf(AnalysisMemory.hit ?: HitSim.Inputs(rangeYd = last.distanceYd, windMph = maxOf(last.windSpeed1Mph, 5.0), windFromDeg = last.windAngleDeg)) }
    var report by remember { mutableStateOf(AnalysisMemory.hitReport) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun upd(f: (HitSim.Inputs) -> HitSim.Inputs) { i = f(i); AnalysisMemory.hit = i }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ScrollColumn(Modifier.width(440.dp).fillMaxHeight()) {
            Section("Firearm and ammo") {
                if (rifles.isEmpty()) Note("Add a firearm and ammo under My gear first.")
                else {
                    Dropdown("Firearm", rifle, rifles, { it.name }, { r -> sel = r.id to store.loads(r.id).firstOrNull()?.id }, Modifier.fillMaxWidth())
                    Dropdown("Ammo", load, loads, { it.name }, { l -> sel = rifle?.id to l.id }, Modifier.fillMaxWidth())
                }
                Note("Uses the weather from the Calculator.")
            }
            Section("The shot") {
                FieldRow {
                    NumberField("Distance", i.rangeYd, { v -> if (v != null && v in 50.0..3000.0) upd { it.copy(rangeYd = v) } }, Modifier.weight(1f), fmt, Quantity.DISTANCE)
                    Dropdown("Target", i.round, listOf(false, true), { if (it) "Round" else "Square" }, { r -> upd { it.copy(round = r) } }, Modifier.weight(1f))
                }
                FieldRow {
                    NumberField("Target width", i.targetWidthIn, { v -> if (v != null && v > 0.5 && v < 200) upd { it.copy(targetWidthIn = v) } }, Modifier.weight(1f), fmt, Quantity.LENGTH)
                    NumberField("Target height", i.targetHeightIn, { v -> if (v != null && v > 0.5 && v < 200) upd { it.copy(targetHeightIn = v) } }, Modifier.weight(1f), fmt, Quantity.LENGTH)
                }
                FieldRow {
                    NumberField("Wind you call", i.windMph, { v -> if (v != null && v >= 0 && v < 60) upd { it.copy(windMph = v) } }, Modifier.weight(1f), fmt, Quantity.SPEED)
                    Dropdown("From", CalcCore.degToClock(i.windFromDeg), (1..12).toList(), { "$it o'clock" },
                        { h -> upd { it.copy(windFromDeg = CalcCore.clockToDeg(h.toDouble())) } }, Modifier.weight(1f))
                }
            }
            Section("How sure you are (typical error, ±)") {
                FieldRow {
                    NumberField("Distance", i.rangeSdYd, { v -> if (v != null && v >= 0 && v < 200) upd { it.copy(rangeSdYd = v) } }, Modifier.weight(1f), fmt, Quantity.DISTANCE, help = "Rangefinder error")
                    NumberField("Wind speed", i.windSpeedSdMph, { v -> if (v != null && v >= 0 && v < 30) upd { it.copy(windSpeedSdMph = v) } }, Modifier.weight(1f), fmt, Quantity.SPEED)
                }
                FieldRow {
                    NumberField("Wind direction", i.windDirSdDeg, { v -> if (v != null && v >= 0 && v <= 90) upd { it.copy(windDirSdDeg = v) } }, Modifier.weight(1f), unit = "°", decimals = 0)
                    NumberField("Muzzle velocity spread (SD)", i.mvSdFps, { v -> if (v != null && v >= 0 && v < 200) upd { it.copy(mvSdFps = v) } }, Modifier.weight(1f), fmt, Quantity.VELOCITY)
                }
                FieldRow {
                    NumberField("BC", i.bcSdPct, { v -> if (v != null && v >= 0 && v < 30) upd { it.copy(bcSdPct = v) } }, Modifier.weight(1f), unit = "%", decimals = 1)
                    NumberField("5-shot group size", i.groupMoa, { v -> if (v != null && v >= 0 && v < 10) upd { it.copy(groupMoa = v) } }, Modifier.weight(1f), unit = "MOA", decimals = 2)
                }
                NumberField("Your aim / wobble", i.aimSdMoa, { v -> if (v != null && v >= 0 && v < 10) upd { it.copy(aimSdMoa = v) } }, Modifier.fillMaxWidth(), unit = "MOA", decimals = 2)
            }
            Button(enabled = !busy && rifle != null && load != null, onClick = {
                val r = rifle ?: return@Button; val l = load ?: return@Button
                val b = store.bullet(l.bulletId)
                if (b == null) { error = "The bullet of this ammo is missing."; return@Button }
                CalcGuard.problem(r, l, b)?.let { error = it; return@Button }
                busy = true; error = null
                val inputs = i
                scope.launch {
                    val res = withContext(Dispatchers.Default) { runCatching { HitSim.run(r, l, b, store.lastShot(), inputs) } }
                    busy = false
                    res.onSuccess { report = it; AnalysisMemory.hitReport = it }.onFailure { error = it.message ?: it.toString() }
                }
            }) { Text(if (busy) "Calculating…" else "Calculate hit chance") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        ScrollColumn(Modifier.weight(1f).fillMaxHeight()) {
            val rep = report
            if (rep == null) Section("Result") { Note("Set the shot and how sure you are of each thing, then press Calculate. Thousands of shots are simulated, each a little different, to see how many hit.") }
            else HitResult(rep, i, fmt)
        }
    }
}

@Composable
private fun HitResult(rep: HitSim.Report, i: HitSim.Inputs, fmt: Fmt) {
    val m = rep.main
    Section("At " + fmt.withUnit(Quantity.DISTANCE, m.rangeYd)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Text("${Math.round(m.probability * 100)} %", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("chance of a first-round hit")
                Note("${CalcCore.count(m.hits)} of ${CalcCore.count(m.shots)} simulated shots hit")
                Gap(8)
                Text("Typical miss: ±${fmt.withUnit(Quantity.LENGTH, m.sdVIn, 1)} up/down, ±${fmt.withUnit(Quantity.LENGTH, m.sdHIn, 1)} left/right")
            }
            ImpactPlot(m, i, Modifier.size(260.dp))
        }
    }
    Section("What makes you miss") {
        val top = m.causes.maxOfOrNull { it.totalIn } ?: 1.0
        m.causes.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, Modifier.width(170.dp))
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((c.totalIn / top).toFloat().coerceIn(0.01f, 1f))
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(3.dp)))
                }
                Text("±${fmt.num(Quantity.LENGTH, c.vIn, 1)} up/down · ±${fmt.num(Quantity.LENGTH, c.hIn, 1)} left/right ${fmt.label(Quantity.LENGTH)}",
                    Modifier.width(300.dp).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (c.note.isNotBlank()) Note(c.note, Modifier.padding(start = 170.dp))
        }
    }
    Section("Hit chance by distance") {
        HitChart(rep.chart, m.rangeYd, fmt, Modifier.fillMaxWidth().height(260.dp))
        Note("Same target, wind and errors at every distance.")
    }
}

@Composable
private fun ImpactPlot(r: HitSim.Result, i: HitSim.Inputs, modifier: Modifier) {
    val hitColor = MaterialTheme.colorScheme.tertiary
    val missColor = MaterialTheme.colorScheme.error
    val line = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))) {
        val extent = max(max(i.targetWidthIn, i.targetHeightIn) * 0.75, 3 * max(r.sdHIn, r.sdVIn)).coerceAtLeast(1.0)
        val scale = (size.minDimension / 2 * 0.95f) / extent.toFloat()
        val c = Offset(size.width / 2, size.height / 2)
        drawLine(grid, Offset(0f, c.y), Offset(size.width, c.y))
        drawLine(grid, Offset(c.x, 0f), Offset(c.x, size.height))
        val w = (i.targetWidthIn * scale).toFloat(); val h = (i.targetHeightIn * scale).toFloat()
        val tl = Offset(c.x - w / 2, c.y - h / 2)
        if (i.round) drawOval(line, tl, Size(w, h), style = Stroke(2f)) else drawRect(line, tl, Size(w, h), style = Stroke(2f))
        r.impacts.forEachIndexed { k, (x, y) ->
            drawCircle(if (r.hitFlags[k]) hitColor else missColor, radius = 2.5f, center = Offset(c.x + (x * scale).toFloat(), c.y - (y * scale).toFloat()))
        }
    }
}

@Composable
private fun HitChart(points: List<Pair<Double, Double>>, markYd: Double, fmt: Fmt, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val mark = MaterialTheme.colorScheme.secondary
    val labelStyle = TextStyle(fontSize = 11.sp, color = axis)
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val left = 44f; val bottom = 26f; val top = 8f; val right = 12f
        val w = size.width - left - right; val h = size.height - top - bottom
        val minX = points.first().first; val maxX = points.last().first
        fun px(x: Double) = left + ((x - minX) / (maxX - minX)).toFloat() * w
        fun py(p: Double) = top + (1f - p.toFloat()) * h
        for (p in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            drawLine(grid, Offset(left, py(p)), Offset(left + w, py(p)))
            drawText(measurer, "${(p * 100).toInt()}%", Offset(2f, py(p) - 8f), labelStyle)
        }
        val ticks = 6
        for (k in 0..ticks) {
            val x = minX + (maxX - minX) * k / ticks
            drawText(measurer, fmt.num(Quantity.DISTANCE, x, 0), Offset(px(x) - 14f, top + h + 6f), labelStyle)
        }
        drawLine(mark, Offset(px(markYd), top), Offset(px(markYd), top + h), strokeWidth = 2f)
        val path = Path()
        points.forEachIndexed { k, (x, p) -> if (k == 0) path.moveTo(px(x), py(p)) else path.lineTo(px(x), py(p)) }
        drawPath(path, lineColor, style = Stroke(3f))
        points.forEach { (x, p) -> drawCircle(lineColor, 3.5f, Offset(px(x), py(p))) }
    }
}

@Composable
private fun TnfScreen(st: AppState) {
    val store = st.store
    val fmt = Fmt(st.settings)
    val entries = AnalysisMemory.tnf
    val rifles = remember(st.rev) { store.rifles() }
    val loads = remember(st.rev) { store.loads(null) }
    fun loadTitle(l: Load) = l.name + (rifles.firstOrNull { it.id == l.rifleId }?.let { " (${it.name})" } ?: "")
    var pick by remember { mutableStateOf<Load?>(null) }
    var label by remember { mutableStateOf("") }
    var mv by remember { mutableStateOf<Double?>(2800.0) }
    var bc by remember { mutableStateOf<Double?>(0.3) }
    var ref by remember { mutableStateOf(AnalysisMemory.tnfRef) }
    val calc = remember { TnfCompare.Calc() }
    var rows by remember { mutableStateOf<List<TnfCompare.Row>?>(null) }

    fun flight(e: TnfCompare.Entry): TnfCompare.Flight? = when (e.kind) {
        TnfCompare.Kind.CUSTOM -> TnfCompare.customFlight(e.label, e.mvFps ?: 0.0, e.g7Bc ?: 0.0)
            .copy(subtitle = "Typed in at " + fmt.withUnit(Quantity.VELOCITY, e.mvFps ?: 0.0))
        TnfCompare.Kind.AMMO -> store.loads(null).firstOrNull { it.id == e.refId }?.let { l ->
            store.bullet(l.bulletId)?.let { b -> TnfCompare.Flight(l.name, listOfNotNull(rifles.firstOrNull { it.id == l.rifleId }?.name,
                "ammo at " + fmt.withUnit(Quantity.VELOCITY, l.muzzleVelocityFps)).joinToString(" · "), b, l) }
        }
        TnfCompare.Kind.BULLET -> store.bullet(e.refId)?.let { b -> TnfCompare.Flight(b.name, "Bullet", b, TnfCompare.load(b, e.mvFps ?: 0.0)) }
    }

    LaunchedEffect(entries.toList(), ref, st.rev, st.settings) {
        val es = entries.toList()
        if (es.isEmpty()) { rows = null; return@LaunchedEffect }
        val flights = es.associateWith { flight(it) }
        rows = withContext(Dispatchers.Default) {
            runCatching { synchronized(calc) { TnfCompare.compare(es, { flights[it] }, calc, ref) } }.getOrNull()
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ScrollColumn(Modifier.width(400.dp).fillMaxHeight()) {
            Section("What is TNF?") {
                Note("TNF = muzzle velocity × G7 BC. One number for how well ammo bucks the wind: higher means less wind drift. " +
                    "Ammo with several BCs, a G1 BC or a drag curve gets the single G7 BC that drifts the same at 1000 yd.")
            }
            val full = entries.size >= TnfCompare.MAX_ENTRIES
            Section("Add your ammo") {
                if (loads.isEmpty()) Note("You have no ammo yet (My gear > Ammo).")
                else {
                    Dropdown("Ammo", pick, loads, { loadTitle(it) }, { pick = it }, Modifier.fillMaxWidth())
                    OutlinedButton(enabled = pick != null && !full, onClick = {
                        pick?.let { l -> if (entries.none { it.kind == TnfCompare.Kind.AMMO && it.refId == l.id }) entries += TnfCompare.Entry(TnfCompare.Kind.AMMO, refId = l.id) }
                    }) { Text("Add to the comparison") }
                }
            }
            Section("Or type one in") {
                TextBox("Name", label, { label = it }, Modifier.fillMaxWidth())
                FieldRow {
                    NumberField("Muzzle velocity", mv, { mv = it?.takeIf { v -> v in TnfCompare.MV_LOW..TnfCompare.MV_HIGH } }, Modifier.weight(1f), fmt, Quantity.VELOCITY)
                    NumberField("G7 BC", bc, { bc = it?.takeIf { v -> v in 0.05..1.5 } }, Modifier.weight(1f), decimals = 3)
                }
                OutlinedButton(enabled = mv != null && bc != null && !full, onClick = {
                    entries += TnfCompare.Entry(TnfCompare.Kind.CUSTOM, label = label.trim().ifBlank { "Typed in ${entries.size + 1}" }, mvFps = mv, g7Bc = bc)
                    label = ""
                }) { Text("Add to the comparison") }
            }
            if (full) Note("The list is full (${TnfCompare.MAX_ENTRIES}). Remove one to add another.")
        }
        ScrollColumn(Modifier.weight(1f).fillMaxHeight()) {
            Section("Comparison") {
                val rs = rows
                if (entries.isEmpty()) Note("Add two or more to compare them. The one you mark as reference is what the others are measured against.")
                else if (rs == null) Note("Calculating…")
                else {
                    val headers = listOf("Ref.", "Ammo", "MV", "G7 BC", "TNF", "Drift*", "± TNF", "MV to match", "")
                    val widths = listOf(0.8f, 2.6f, 0.9f, 0.9f, 0.8f, 1f, 0.8f, 1.3f, 0.6f)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        headers.forEachIndexed { k, t -> Text(t, Modifier.weight(widths[k]), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                    }
                    HorizontalDivider()
                    rs.forEachIndexed { idx, r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(widths[0])) { RadioButton(r.isReference, { ref = idx; AnalysisMemory.tnfRef = idx }) }
                            Column(Modifier.weight(widths[1])) {
                                Text(r.title, fontWeight = FontWeight.SemiBold)
                                Note(r.problem.ifBlank { r.subtitle })
                            }
                            Text(r.mvFps?.let { fmt.num(Quantity.VELOCITY, it, 0) } ?: "—", Modifier.weight(widths[2]))
                            Text(r.g7Bc?.let { (if (r.bcIsEquivalent) "≈" else "") + Num.fixed(it, 3) } ?: "—", Modifier.weight(widths[3]))
                            Text(r.tnf?.let { Num.fixed(it, 0) } ?: "—", Modifier.weight(widths[4]), fontWeight = FontWeight.SemiBold)
                            Text(r.driftMil?.let { Num.fixed(it, 2) + " mil" } ?: "—", Modifier.weight(widths[5]))
                            Text(if (r.isReference) "ref." else r.deltaTnf?.let { (if (it >= 0) "+" else "−") + Num.fixed(abs(it), 0) } ?: "—", Modifier.weight(widths[6]))
                            Text(when (val mm = r.matchMv) {
                                is TnfCompare.MatchMv.At -> if (r.isReference) "—" else fmt.withUnit(Quantity.VELOCITY, Math.ceil(mm.mvFps / 10) * 10)
                                TnfCompare.MatchMv.AboveMax -> "can't"
                                TnfCompare.MatchMv.NotAvailable -> "—"
                            }, Modifier.weight(widths[7]))
                            IconButton(onClick = {
                                entries.removeAt(idx)
                                ref = ref?.let { if (it == idx) null else if (it > idx) it - 1 else it }; AnalysisMemory.tnfRef = ref
                            }, Modifier.weight(widths[8])) { Icon(Icons.Filled.Delete, "Remove") }
                        }
                    }
                    Gap(4)
                    Note("* Drift: how far a 10 mph full-value wind (from 3 or 9 o'clock) pushes the bullet at 1000 yd, standard weather. " +
                        "MV to match: the muzzle velocity this ammo would need to drift no more than the reference. Velocities in ${fmt.label(Quantity.VELOCITY)}.")
                }
            }
        }
    }
}
