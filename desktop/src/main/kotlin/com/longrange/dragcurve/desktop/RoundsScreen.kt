package com.longrange.dragcurve.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotRecord

private val QUICK = listOf(5, 10, 20, 50)

@Composable
fun RoundsScreen(st: AppState) {
    val store = st.store
    val rifles = remember(st.rev) { store.rifles().sortedBy { it.name.lowercase() } }
    val allShots = remember(st.rev) { store.shotRecords(null) }
    var selId by remember { mutableStateOf(store.selection().first) }
    val rifle = rifles.firstOrNull { it.id == selId } ?: rifles.firstOrNull()
    var editing by remember { mutableStateOf<ShotRecord?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Round count", "Log the rounds you fire through each firearm. Each entry keeps its date and time and travels with your backups.")
        if (rifle == null) { Note("Add a firearm under My gear first."); return@Column }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rifles, key = { it.id }) { r ->
                    val total = allShots.filter { it.rifleId == r.id }.sumOf { it.count }
                    Card(Modifier.fillMaxWidth().clickable { selId = r.id },
                        colors = CardDefaults.cardColors(containerColor = if (r.id == rifle.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold)
                            Note("${CalcCore.count(total)} rounds")
                        }
                    }
                }
            }
            RifleCounter(st, rifle, onExact = { adding = true }, onEdit = { editing = it })
        }
    }
    if (adding && rifle != null) CountDialog(null, onDone = { n, note ->
        adding = false
        if (n != null) { store.saveShotRecord(ShotRecord(id = "", rifleId = rifle.id, count = n, recordedAt = System.currentTimeMillis(), note = note.trim())); st.changed() }
    })
    editing?.let { old ->
        CountDialog(old, onDone = { n, note ->
            editing = null
            if (n != null) { store.saveShotRecord(old.copy(count = n, note = note.trim())); st.changed() }
        })
    }
}

@Composable
private fun RifleCounter(st: AppState, rifle: Rifle, onExact: () -> Unit, onEdit: (ShotRecord) -> Unit) {
    val store = st.store
    val list = remember(st.rev, rifle.id) { store.shotRecords(rifle.id) }
    val total = list.sumOf { it.count }
    ScrollColumn(Modifier.fillMaxHeight()) {
        Section(rifle.name) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(CalcCore.count(total), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text(if (list.isEmpty()) "rounds fired. Nothing logged yet." else "rounds fired · last logged ${CalcCore.time(list.maxOf { it.recordedAt })}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Gap(12)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QUICK.forEach { n ->
                        Button(onClick = {
                            store.saveShotRecord(ShotRecord(id = "", rifleId = rifle.id, count = n, recordedAt = System.currentTimeMillis()))
                            st.changed()
                        }, modifier = Modifier.width(110.dp).height(56.dp)) { Text("+$n", fontSize = 20.sp) }
                    }
                }
                Gap(8)
                OutlinedButton(onClick = onExact, modifier = Modifier.width(470.dp)) { Text("Exact count…") }
            }
        }
        Section("History") {
            if (list.isEmpty()) Note("Press a button above after a range session. For a firearm that has been shot before, use Exact count with the count so far.")
            var running = total
            list.sortedByDescending { it.recordedAt }.forEach { s ->
                val after = running
                running -= s.count
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(CalcCore.signed(s.count), Modifier.width(90.dp), fontWeight = FontWeight.SemiBold)
                    Column(Modifier.weight(1f)) {
                        Text(CalcCore.time(s.recordedAt))
                        Note(listOfNotNull(s.note.takeIf { it.isNotBlank() }, "total ${CalcCore.count(after)}").joinToString(" · "))
                    }
                    TextButton(onClick = { onEdit(s) }) { Text("Change") }
                    TextButton(onClick = {
                        st.ask("Delete this entry?", "${CalcCore.signed(s.count)} rounds logged ${CalcCore.time(s.recordedAt)}.", "Delete") {
                            store.deleteShotRecord(s.id); st.changed()
                        }
                    }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun CountDialog(old: ShotRecord?, onDone: (Int?, String) -> Unit) {
    var count by remember { mutableStateOf(old?.count?.toString() ?: "") }
    var note by remember { mutableStateOf(old?.note ?: "") }
    val n = count.trim().toIntOrNull()
    val ok = n != null && n in 1..1_000_000
    AlertDialog(
        onDismissRequest = { onDone(null, "") },
        title = { Text(if (old == null) "Add an exact count" else "Change this entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note(if (old == null) "Logged with today's date and time." else "Logged ${CalcCore.time(old.recordedAt)}. The time stays the same.")
                TextBox("Rounds", count, { count = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(),
                    error = if (count.isNotEmpty() && !ok) "Type a whole number of rounds, 1 or more." else null)
                TextBox("Note (optional), e.g. count when bought", note, { note = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onDone(n, note) }, enabled = ok) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onDone(null, "") }) { Text("Cancel") } },
    )
}
