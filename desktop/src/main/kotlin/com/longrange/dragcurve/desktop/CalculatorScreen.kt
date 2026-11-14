package com.longrange.dragcurve.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longrange.dragcurve.model.Atmosphere
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.ui.calc.BcSelection
import com.longrange.dragcurve.ui.calc.LoadFlags
import com.longrange.dragcurve.ui.calc.Num
import com.longrange.dragcurve.ui.calc.Quantity
import com.longrange.dragcurve.ui.calc.WindText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun CalculatorScreen(st: AppState) {
    val store = st.store
    val rev = st.rev
    val s = st.settings
    val fmt = remember(s) { Fmt(s) }
    val rifles = remember(rev) { store.rifles() }
    var sel by remember { mutableStateOf(store.selection()) }
    val rifle = rifles.firstOrNull { it.id == sel.first } ?: rifles.firstOrNull()
    val loads = remember(rev, rifle?.id) { rifle?.let { store.loads(it.id) } ?: emptyList() }
    val load = loads.firstOrNull { it.id == sel.second } ?: loads.firstOrNull()
    val bullet = remember(rev, load?.bulletId) { load?.let { store.bullet(it.bulletId) } }
    var shot by remember { mutableStateOf(store.lastShot()) }
    var clockMode by remember { mutableStateOf(true) }

    LaunchedEffect(shot, rifle?.id, load?.id) {
        delay(600)
        store.saveLastShot(shot)
        store.saveSelection(rifle?.id, load?.id)
    }

    var result by remember { mutableStateOf<Result<CalcResult>?>(null) }
    LaunchedEffect(rifle, load, bullet, shot, s) {
        result = when {
            rifle == null || load == null -> null
            bullet == null -> Result.failure(IllegalArgumentException("The bullet of '${load.name}' is missing. Open the ammo under My gear and choose a bullet."))
            else -> withContext(Dispatchers.Default) { runCatching { CalcCore.solve(rifle, load, bullet, shot, s) } }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ScrollColumn(Modifier.width(420.dp).fillMaxHeight()) {
            ScreenTitle("Calculator", "Pick your firearm and ammo, then set the distance, wind and weather.")
            Section("Firearm and ammo") {
                if (rifles.isEmpty()) {
                    Note("You have no firearms yet. Add one with its ammo under My gear, or bring your data over from the phone under Backup & move data.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { st.screen = Screen.GEAR }) { Text("Go to My gear") }
                        OutlinedButton(onClick = { st.screen = Screen.BACKUP }) { Text("Import from phone") }
                    }
                } else {
                    Dropdown("Firearm", rifle, rifles, { it.name }, { r -> sel = r.id to store.loads(r.id).firstOrNull()?.id }, Modifier.fillMaxWidth())
                    Dropdown("Ammo", load, loads, { it.name }, { l -> sel = rifle?.id to l.id }, Modifier.fillMaxWidth(),
                        placeholder = if (loads.isEmpty()) "No ammo for this firearm yet" else "")
                    if (load != null) {
                        Note(listOf(
                            BcSelection.summary(load, bullet),
                            "MV " + fmt.withUnit(Quantity.VELOCITY, load.muzzleVelocityFps),
                            "zero " + fmt.withUnit(Quantity.DISTANCE, load.zeroRangeYd),
                        ).joinToString(" · "))
                        LoadFlags.text(load, bullet)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    } else if (rifle != null) {
                        OutlinedButton(onClick = { st.screen = Screen.GEAR }) { Text("Add ammo under My gear") }
                    }
                }
            }
            Section("Distance") {
                FieldRow {
                    NumberField("Distance to target", shot.distanceYd, { v -> if (v != null && v > 0 && v <= 5000) shot = shot.copy(distanceYd = v) },
                        Modifier.weight(1f), fmt, Quantity.DISTANCE)
                    NumberField("Shot angle", shot.lookAngleDeg, { v -> if (v != null && abs(v) <= 80) shot = shot.copy(lookAngleDeg = v) },
                        Modifier.weight(1f), unit = "°", decimals = 1, help = "+ uphill, − downhill")
                }
            }
            Section("Wind") {
                NumberField("Wind speed", shot.windSpeed1Mph, { v -> if (v != null && v >= 0 && v < 200) shot = shot.copy(windSpeed1Mph = v) },
                    Modifier.fillMaxWidth(), fmt, Quantity.SPEED)
                Choice(listOf(true, false), clockMode, { if (it) "Clock" else "Degrees" }, { clockMode = it })
                if (clockMode) {
                    val hour = CalcCore.degToClock(shot.windAngleDeg)
                    Dropdown("Wind comes from", hour, (1..12).toList(), { h -> "$h o'clock" + clockHint(h) },
                        { h -> shot = shot.copy(windAngleDeg = CalcCore.clockToDeg(h.toDouble())) }, Modifier.fillMaxWidth())
                } else {
                    NumberField("Wind comes from", shot.windAngleDeg, { v -> if (v != null) shot = shot.copy(windAngleDeg = WindText.normalize(v)) },
                        Modifier.fillMaxWidth(), unit = "°", decimals = 1, help = "0° = straight at you, 90° = from the right, 270° = from the left")
                }
                Note("Now: " + WindText.clock(shot.windAngleDeg) + " (" + Num.trim(shot.windAngleDeg, 1) + "°)")
            }
            Section("Weather") {
                val a = shot.atmosphere
                fun atmo(f: (Atmosphere) -> Atmosphere) { shot = shot.copy(atmosphere = f(shot.atmosphere)) }
                FieldRow {
                    NumberField("Temperature", a.temperatureF, { v -> if (v != null && v > -80 && v < 160) atmo { it.copy(temperatureF = v) } },
                        Modifier.weight(1f), fmt, Quantity.TEMPERATURE)
                    NumberField("Humidity", a.humidityPct, { v -> if (v != null && v >= 0 && v <= 100) atmo { it.copy(humidityPct = v) } },
                        Modifier.weight(1f), unit = "%", decimals = 0)
                }
                FieldRow {
                    NumberField("Pressure", a.pressureInHg, { v -> if (v != null && v > 10 && v < 35) atmo { it.copy(pressureInHg = v) } },
                        Modifier.weight(1f), fmt, Quantity.PRESSURE)
                    NumberField("Altitude", a.altitudeFt, { v -> if (v != null && v > -2000 && v < 20000) atmo { it.copy(altitudeFt = v) } },
                        Modifier.weight(1f), fmt, Quantity.ALTITUDE)
                }
                Choice(listOf(true, false), a.pressureIsAbsolute, { if (it) "Pressure measured here" else "Sea-level pressure (weather report)" },
                    { v -> atmo { it.copy(pressureIsAbsolute = v) } }, vertical = true)
                Note(if (a.pressureIsAbsolute) "A pressure read where you shoot already includes the altitude." else "The altitude turns the weather report's sea-level pressure into the pressure where you shoot.")
                OutlinedButton(onClick = { shot = shot.copy(atmosphere = Atmosphere()) }) { Text("Standard day (59 °F, 29.92 inHg)") }
            }
        }

        ScrollColumn(Modifier.weight(1f).fillMaxHeight()) {
            Gap(4)
            val r = result
            when {
                rifle == null || load == null -> Section("Answer") { Note("Choose a firearm and ammo to see where to aim.") }
                r == null -> Section("Answer") { Note("Calculating…") }
                r.isFailure -> Section("Can't calculate") {
                    Text(r.exceptionOrNull()?.message ?: "Unknown problem", color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    val c = r.getOrThrow()
                    Answer(c, rifle, fmt)
                    RangeTable(st, c, rifle, fmt, shot.distanceYd, load.zeroRangeYd)
                }
            }
        }
    }
}

private fun clockHint(h: Int): String = when (h) {
    12 -> " (straight at you)"
    3 -> " (from the right)"
    6 -> " (from behind)"
    9 -> " (from the left)"
    else -> ""
}

@Composable
private fun Answer(c: CalcResult, rifle: Rifle, fmt: Fmt) {
    val row = c.row
    Section("At " + fmt.withUnit(Quantity.DISTANCE, row.rangeYd)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigValue("Elevation", c.elevation, Modifier.weight(1f), MaterialTheme.colorScheme.primaryContainer)
            BigValue("Windage", c.windage, Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer)
        }
        Gap(4)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Velocity", fmt.withUnit(Quantity.VELOCITY, row.velocityFps), Modifier.weight(1f))
            Stat("Energy", fmt.withUnit(Quantity.ENERGY, row.energyFtLb), Modifier.weight(1f))
            Stat("Time of flight", Num.fixed(row.tofS, 2) + " s", Modifier.weight(1f))
            Stat("Drop", fmt.withUnit(Quantity.LENGTH, abs(row.pathIn), 1) + if (row.pathIn > 0.05) " high" else "", Modifier.weight(1f))
            Stat("Wind drift", fmt.withUnit(Quantity.LENGTH, abs(row.windDriftIn), 1) +
                when { row.windDriftIn < -0.05 -> " left"; row.windDriftIn > 0.05 -> " right"; else -> "" }, Modifier.weight(1f))
        }
        val notes = buildList {
            add("Muzzle velocity today: " + fmt.withUnit(Quantity.VELOCITY, c.solution.muzzleVelocityFps))
            c.solution.transonicYd?.let { add("Slows to transonic (Mach 1.2) at about " + fmt.withUnit(Quantity.DISTANCE, it)) }
            c.solution.subsonicYd?.let { add("Goes subsonic at about " + fmt.withUnit(Quantity.DISTANCE, it)) }
            if (c.solution.stabilityMuzzle < 1.4) add("Stability ${Num.fixed(c.solution.stabilityMuzzle, 2)}: marginal for this twist (1.4 or more is best).")
            add("Turret: ${CalcCore.unitName(rifle.elevationUnit)}, ${Num.trim(rifle.elevationClick, 3)} per click up/down; " +
                "${CalcCore.unitName(rifle.windageUnit)}, ${Num.trim(rifle.windageClick, 3)} per click left/right.")
        }
        notes.forEach { Note(it) }
    }
}

@Composable
private fun BigValue(title: String, d: Dial, modifier: Modifier, bg: Color) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(d.value, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(" " + d.unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text("${d.clicks} clicks", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RangeTable(st: AppState, c: CalcResult, rifle: Rifle, fmt: Fmt, distanceYd: Double, zeroYd: Double) {
    val s = fmt.s
    Section("Range table") {
        val distUnit = fmt.label(Quantity.DISTANCE)
        val steps = listOf(10.0, 25.0, 50.0, 100.0)
        val maxes = listOf(500.0, 800.0, 1000.0, 1200.0, 1500.0, 2000.0, 2500.0, 3000.0)
        val curStep = Math.rint(fmt.disp(Quantity.DISTANCE, s.tableStepYd))
        val curMax = Math.rint(fmt.disp(Quantity.DISTANCE, s.tableMaxYd))
        FieldRow {
            Dropdown("Every", curStep, (steps + curStep).distinct().sorted(), { Num.trim(it, 0) + " " + distUnit },
                { v -> st.saveSettings(s.copy(tableStepYd = fmt.model(Quantity.DISTANCE, v))) }, Modifier.width(160.dp))
            Dropdown("Out to", curMax, (maxes + curMax).distinct().sorted(), { Num.trim(it, 0) + " " + distUnit },
                { v -> st.saveSettings(s.copy(tableMaxYd = fmt.model(Quantity.DISTANCE, v))) }, Modifier.width(160.dp))
        }
        val rows = CalcCore.tableRows(c.solution, s)
        val eu = com.longrange.dragcurve.ui.calc.Adjust.unitLabel(rifle.elevationUnit)
        val wu = com.longrange.dragcurve.ui.calc.Adjust.unitLabel(rifle.windageUnit)
        val headers = listOf("Distance\n$distUnit", "Elevation\n$eu", "Clicks\nup/down", "Windage\n$wu", "Clicks\nleft/right",
            "Velocity\n${fmt.label(Quantity.VELOCITY)}", "Energy\n${fmt.label(Quantity.ENERGY)}", "Time\ns")
        TableRow(headers, header = true)
        HorizontalDivider()
        rows.forEach { r ->
            val e = CalcCore.elevation(r, rifle, s)
            val w = CalcCore.windage(r, rifle, s)
            val mark = abs(r.rangeYd - distanceYd) < 0.5 || abs(r.rangeYd - zeroYd) < 0.5
            TableRow(listOf(fmt.num(Quantity.DISTANCE, r.rangeYd, 0), e.value, e.clicks, w.value, w.clicks,
                fmt.num(Quantity.VELOCITY, r.velocityFps, 0), fmt.num(Quantity.ENERGY, r.energyFtLb, 0), Num.fixed(r.tofS, 2)),
                highlight = mark, subsonic = r.mach < 1.0)
        }
        Note("Highlighted: your distance and the zero. Grey rows: the bullet is below the speed of sound.")
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, highlight: Boolean = false, subsonic: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val color = if (subsonic && !header) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(vertical = 3.dp)) {
        cells.forEach { t ->
            Text(t, Modifier.weight(1f), textAlign = TextAlign.Center, color = color,
                fontFamily = FontFamily.Default,
                fontWeight = if (header || highlight) FontWeight.SemiBold else FontWeight.Normal,
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium)
        }
    }
}
