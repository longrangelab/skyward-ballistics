package com.longrange.dragcurve.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longrange.dragcurve.exchange.Part
import com.longrange.dragcurve.exchange.PartKind
import com.longrange.dragcurve.exchange.PartStatus

class PartsChoice(
    val title: String,
    val intro: String,
    val parts: List<Part>,
    val action: String,
    val notes: List<String> = emptyList(),
    val along: (Set<String>) -> List<Part> = { emptyList() },
    val onGo: (Set<String>) -> Unit,
    val startAll: Boolean = true,
) {
    val allKeys: Set<String> get() = parts.map { it.key }.toSet()
    val startPicked: Set<String> get() = if (startAll) parts.filter { it.status != com.longrange.dragcurve.exchange.PartStatus.SAME }.map { it.key }.toSet() else emptySet()

    companion object {
        val startFolded = setOf(PartKind.LIBRARY)
    }
}

fun alongText(parts: List<Part>, perKind: Int = 6): String =
    parts.groupBy { it.kind }.entries.sortedBy { it.key.ordinal }.joinToString("\n") { (kind, list) ->
        "${kind.label}: " + list.take(perKind).joinToString(", ") { it.title } +
            (if (list.size > perKind) " and ${list.size - perKind} more" else "")
    }

@Composable
fun PartsPicker(c: PartsChoice, busy: String?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var picked by remember(c) { mutableStateOf(c.startPicked) }
    var folded by remember(c) { mutableStateOf(PartsChoice.startFolded) }
    val groups = remember(c) { c.parts.groupBy { it.kind }.entries.sortedBy { it.key.ordinal }.map { it.key to it.value } }
    val along = remember(c, picked) { if (picked.isEmpty()) emptyList() else c.along(picked) }

    Column(modifier.fillMaxHeight().widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle(c.title, c.intro)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { picked = c.allKeys }, enabled = busy == null) { Text("Select all") }
            TextButton(onClick = { picked = emptySet() }, enabled = busy == null) { Text("Select none") }
            Box(Modifier.weight(1f))
            Note("${picked.size} of ${c.parts.size} chosen", Modifier.padding(end = 20.dp))
        }
        Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            val list = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = list) {
                    groups.forEachIndexed { gi, (kind, parts) ->
                        item(key = "group-" + kind.name) {
                            if (gi > 0) HorizontalDivider()
                            val keys = parts.map { it.key }
                            val on = keys.count { it in picked }
                            val open = kind !in folded
                            fun fold() { folded = if (open) folded + kind else folded - kind }
                            Row(Modifier.fillMaxWidth().clickable { fold() }.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { fold() }) {
                                    Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        if (open) "Fold ${kind.label}" else "Unfold ${kind.label}")
                                }
                                TriStateCheckbox(
                                    state = when (on) { 0 -> ToggleableState.Off; keys.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                                    onClick = { picked = if (on == keys.size) picked - keys.toSet() else picked + keys },
                                    enabled = busy == null,
                                )
                                Text(kind.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Note("$on of ${keys.size} chosen", Modifier.padding(end = 8.dp))
                            }
                        }
                        if (kind !in folded) items(parts, key = { it.key }) { p ->
                            val checked = p.key in picked
                            fun flip() { picked = if (checked) picked - p.key else picked + p.key }
                            Row(Modifier.fillMaxWidth().clickable(enabled = busy == null) { flip() }.padding(start = 48.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, { flip() }, enabled = busy == null)
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(p.title)
                                    if (p.summary.isNotBlank()) Note(p.summary)
                                }
                                p.status?.let { s -> Text(s.label, style = MaterialTheme.typography.bodySmall, color = statusColor(s), modifier = Modifier.padding(start = 12.dp)) }
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        if (along.isNotEmpty()) {
            Text("Also included, because ticked items need them:", fontWeight = FontWeight.SemiBold)
            Note(alongText(along))
        }
        c.notes.forEach { Note(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { c.onGo(picked) }, enabled = busy == null && picked.isNotEmpty()) { Text(c.action) }
            OutlinedButton(onClick = onCancel, enabled = busy == null) { Text("Cancel") }
            busy?.let { Text(it, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun statusColor(s: PartStatus): Color = when (s) {
    PartStatus.NEW -> MaterialTheme.colorScheme.tertiary
    PartStatus.UPDATE, PartStatus.COPY -> MaterialTheme.colorScheme.secondary
    PartStatus.SAME, PartStatus.NEWER_HERE -> MaterialTheme.colorScheme.onSurfaceVariant
}
