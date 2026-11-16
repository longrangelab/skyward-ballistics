package com.longrange.dragcurve.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longrange.dragcurve.model.BarrelCare
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CleanCore {
    fun times(n: Int) = if (n == 1) "once" else if (n == 2) "twice" else "${CalcCore.count(n)} times"

    fun line(s: BarrelCare.Status): String? {
        if (s.lastCleaned == null && s.every == null) return null
        val since = if (s.lastCleaned == null) "${CalcCore.count(s.sinceCleaning)} rounds, no cleaning logged"
            else "${CalcCore.count(s.sinceCleaning)} rounds since cleaning"
        return since + when {
            s.due -> " · due for a cleaning"
            s.left != null -> " · ${CalcCore.count(s.left!!)} to go"
            else -> ""
        }
    }

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    fun dayText(at: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).format(DAY)
    fun clockText(at: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).format(CLOCK)

    fun parse(day: String, clock: String): Long? = runCatching {
        LocalDateTime.of(LocalDate.parse(day.trim(), DAY), LocalTime.parse(clock.trim(), CLOCK)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

@Composable
fun BarrelCleaningSection(st: AppState, rifle: Rifle, list: List<ShotRecord>) {
    val store = st.store
    val care = BarrelCare.status(list, rifle.cleanEveryRounds)
    var dialog by remember(rifle.id) { mutableStateOf<ShotRecord?>(null) }
    var adding by remember(rifle.id) { mutableStateOf(false) }
    var every by remember(rifle.id, rifle.cleanEveryRounds) { mutableStateOf(rifle.cleanEveryRounds?.toString() ?: "") }

    Section("Barrel cleaning") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(CalcCore.count(care.sinceCleaning), fontSize = 56.sp, fontWeight = FontWeight.Bold,
                color = if (care.due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(care.lastCleaned?.let { "rounds since the last cleaning, ${CalcCore.time(it.recordedAt)}" } ?: "rounds fired. No cleaning logged yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            care.every?.let { e ->
                Text(if (care.due) "Due for a cleaning. You clean it every ${CalcCore.count(e)} rounds."
                    else "${CalcCore.count(care.left!!)} rounds until the next cleaning (every ${CalcCore.count(e)}).",
                    color = if (care.due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (care.due) FontWeight.SemiBold else null, textAlign = TextAlign.Center)
            }
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { store.saveShotRecord(BarrelCare.cleaning(rifle.id, System.currentTimeMillis())); st.changed() },
                    modifier = Modifier.width(230.dp).height(56.dp)) { Text("Cleaned now", fontSize = 18.sp) }
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.width(230.dp).height(56.dp)) { Text("Cleaned earlier…") }
            }
        }
        Gap(8)
        Note("Rounds since cleaning counts the rounds above logged after the latest cleaning. Each cleaning keeps its date and time and travels with your backups.")
        Gap(4)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val v = BarrelCare.interval(every)
            val bad = every.isNotBlank() && v == null
            TextBox("Clean every … rounds", every, { every = it.filter { c -> c.isDigit() } }, Modifier.width(320.dp),
                error = if (bad) "A whole number from 1 to 100,000" else null)
            TextButton(onClick = {
                val cur = store.rifles().firstOrNull { it.id == rifle.id } ?: return@TextButton
                store.saveRifle(cur.copy(cleanEveryRounds = v)); st.changed()
            }, enabled = !bad && v != rifle.cleanEveryRounds) { Text("Save") }
            Note("Shows when the barrel is due for a cleaning. Leave it empty for no reminder.", Modifier.weight(1f))
        }
        val cleans = BarrelCare.cleanings(list).sortedByDescending { it.recordedAt }
        if (cleans.isNotEmpty()) {
            Gap(8)
            Text("Cleaned ${CleanCore.times(cleans.size)}", fontWeight = FontWeight.SemiBold)
        }
        cleans.forEach { c ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Cleaned", Modifier.width(90.dp), fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f)) {
                    Text(CalcCore.time(c.recordedAt))
                    Note(listOfNotNull(c.note.takeIf { it.isNotBlank() }, "${CalcCore.count(BarrelCare.roundsBefore(list, c))} rounds before").joinToString(" · "))
                }
                TextButton(onClick = { dialog = c }) { Text("Change") }
                TextButton(onClick = {
                    st.ask("Delete this cleaning?", "Cleaned ${CalcCore.time(c.recordedAt)}.", "Delete") { store.deleteShotRecord(c.id); st.changed() }
                }) { Text("Delete") }
            }
        }
    }
    if (adding) CleaningDialog(null) { at, note ->
        adding = false
        if (at != null) { store.saveShotRecord(BarrelCare.cleaning(rifle.id, at, note)); st.changed() }
    }
    dialog?.let { old ->
        CleaningDialog(old) { at, note ->
            dialog = null
            if (at != null) { store.saveShotRecord(old.copy(recordedAt = at, note = note.trim())); st.changed() }
        }
    }
}

@Composable
private fun CleaningDialog(old: ShotRecord?, onDone: (Long?, String) -> Unit) {
    val start = old?.recordedAt ?: System.currentTimeMillis()
    var day by remember { mutableStateOf(CleanCore.dayText(start)) }
    var clock by remember { mutableStateOf(CleanCore.clockText(start)) }
    var note by remember { mutableStateOf(old?.note ?: "") }
    val at = CleanCore.parse(day, clock)
    val future = at != null && at > System.currentTimeMillis() + 60_000
    AlertDialog(
        onDismissRequest = { onDone(null, "") },
        title = { Text(if (old == null) "When did you clean it?" else "Change this cleaning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note("Rounds logged after this date and time count as rounds since cleaning.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBox("Date (YYYY-MM-DD)", day, { day = it }, Modifier.weight(1f),
                        error = if (at == null) "Use a date like 2026-11-14 and a time like 17:30" else if (future) "That's in the future" else null)
                    TextBox("Time (HH:MM)", clock, { clock = it }, Modifier.width(140.dp))
                }
                TextBox("Note (optional), e.g. copper solvent, 20 patches", note, { note = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onDone(at, note) }, enabled = at != null && !future) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onDone(null, "") }) { Text("Cancel") } },
    )
}
