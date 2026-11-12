package com.longrange.dragcurve.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longrange.dragcurve.exchange.ImportResult
import com.longrange.dragcurve.exchange.LibraryIngest
import com.longrange.dragcurve.sync.SharedFileSync
import com.longrange.dragcurve.sync.SyncFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop

@Composable
fun BackupScreen(st: AppState) {
    val data = st.data
    val store = st.store
    val snap = remember(st.rev) { store.snapshot() }
    val copies = remember(st.rev) { data.safetyCopies() }
    var busy by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<PartsChoice?>(null) }
    val scope = rememberCoroutineScope()

    fun export() {
        val f = FilePicker.save("Save a backup of everything", data.defaultExportName("master")) ?: return
        busy = "Saving…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { data.exportMaster(f) } }
            busy = null
            r.onSuccess { what -> st.tell("Backup saved", "Saved to\n${f.absolutePath}\n\n$what\n\nTo put it on the phone: copy the file to the phone, then on the phone open More > Backup & sync > Import files… and pick it.") }
                .onFailure { e -> st.tell("Couldn't save the backup", e.message ?: e.toString()) }
        }
    }

    fun saveParts(picked: Set<String>) {
        val f = FilePicker.save("Save the chosen parts", data.defaultExportName("parts")) ?: return
        busy = "Saving…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { data.exportParts(f, picked) } }
            busy = null
            r.onSuccess { what ->
                choice = null
                st.tell("Saved", "Saved to\n${f.absolutePath}\n\n$what\n\nImport it on the phone or another computer with Import files…. It only adds and updates there: nothing is deleted.")
            }.onFailure { e -> st.tell("Couldn't save the file", e.message ?: e.toString()) }
        }
    }

    fun exportParts() {
        busy = "Reading…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { data.partsOfDatabase() } }
            busy = null
            r.onSuccess { parts ->
                if (parts.isEmpty()) { st.tell("Nothing to export", "There are no firearms, ammo, bullets, target cards or round counts on this computer yet."); return@onSuccess }
                choice = PartsChoice(
                    title = "Export chosen parts",
                    intro = "Tick what to put in the file. Ammo takes its firearm and bullet along, a target card its firearm and ammo, round counts their firearm.",
                    parts = parts, action = "Export…", startAll = false,
                    along = { data.exportAlong(it) },
                    onGo = { saveParts(it) },
                )
            }.onFailure { e -> st.tell("Can't read your data", e.message ?: e.toString()) }
        }
    }

    fun importPicked(r: ImportResult, picked: Set<String>) {
        busy = "Importing…"
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { data.importParts(r, picked) } }
            busy = null
            st.changed()
            res.onSuccess { rep -> choice = null; st.tell("Imported", DesktopData.reportText(rep)) }
                .onFailure { e -> st.tell("Import failed", (e.message ?: e.toString()) + "\n\nNothing was lost: a safety copy was taken before the import.") }
        }
    }

    fun confirm(p: LibraryIngest.Prepared) {
        val what = p.formatNames.singleOrNull() ?: "${p.items.size} files"
        st.ask("Import $what?",
            (if (p.items.size > 1) "${p.items.size} files. " else "") +
                "${p.rifles} firearms, ${p.loads} ammo, ${p.bullets} bullets." +
                (if (p.skipped.isNotEmpty()) "\n\n${p.skipped.size} file(s) could not be read and will be left out." else "") +
                "\n\nRecords you already have are matched up: the newer change wins, nothing is duplicated. A safety copy of your data is taken first.",
            "Import") {
            busy = "Importing…"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { data.commitImport(p) } }
                busy = null
                st.changed()
                r.onSuccess { rep -> st.tell("Imported", DesktopData.reportText(rep)) }
                    .onFailure { e -> st.tell("Import failed", (e.message ?: e.toString()) + "\n\nNothing was lost: a safety copy was taken before the import.") }
            }
        }
    }

    fun import() {
        val files = FilePicker.open("Import Skyward or Empirical Precision files", multiple = true)
        if (files.isEmpty()) return
        busy = "Reading…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { data.prepareImport(files).let { p -> p to p.items.singleOrNull()?.let { data.partsOfFile(it.result) } } }
            }
            busy = null
            r.onSuccess { (p, parts) ->
                val item = p.items.singleOrNull()
                if (item == null || parts.isNullOrEmpty()) { confirm(p); return@onSuccess }
                choice = PartsChoice(
                    title = "Import from ${item.name}",
                    intro = "${item.format.displayName}. Everything is ticked: untick what you don't want.",
                    parts = parts, action = "Import",
                    notes = listOf("Only adds and updates: nothing here is deleted. A safety copy of your data is taken first.") +
                        (if (p.skipped.isNotEmpty()) listOf("${p.skipped.size} file(s) could not be read and are left out.") else emptyList()),
                    along = { data.importAlong(item.result, it) },
                    onGo = { importPicked(item.result, it) },
                )
            }.onFailure { e -> st.tell("Can't import", e.message ?: e.toString()) }
        }
    }

    fun sync(allowDeletions: Int = 0, allowEmpty: Boolean = false) {
        busy = "Syncing…"
        scope.launch {
            val o = withContext(Dispatchers.IO) { data.syncShared(allowDeletions, allowEmpty) }
            busy = null
            st.changed()
            when (o) {
                is SharedFileSync.Outcome.Done -> st.tell("Synced", o.text)
                is SharedFileSync.Outcome.Failed -> st.tell("Sync didn't finish", o.text)
                is SharedFileSync.Outcome.NeedsConfirmation -> st.ask("Remove ${o.count} record(s) on this computer?",
                    o.text + "\n\nA safety copy is taken first (Safety copies, below).", "Remove and sync") { sync(o.count, allowEmpty) }
                is SharedFileSync.Outcome.FileEmptied -> st.ask(if (o.missing) "The shared file is missing" else "The shared file is empty",
                    o.text, "Write this computer's data into it") { sync(allowDeletions, true) }
            }
        }
    }

    fun chooseShared() {
        val f = FilePicker.open("Choose the shared sync file (e.g. skyward-sync.json in your Google Drive folder)").firstOrNull() ?: return
        data.sharedSync.choose(f); st.changed(); sync()
    }

    fun createShared() {
        val f = FilePicker.save("Create the shared sync file in your Google Drive folder", SyncFile.FILE_NAME) ?: return
        if (f.exists()) { st.tell("That file already exists", "Use \"Choose the shared file…\" to sync with an existing file."); return }
        data.sharedSync.choose(f); st.changed(); sync()
    }

    val open = choice
    if (open != null) PartsPicker(open, busy, onCancel = { choice = null })
    else ScrollColumn(Modifier.fillMaxHeight().widthIn(max = 900.dp)) {
        ScreenTitle("Backup & move data", "Your data lives on this computer. A shared sync file keeps it the same as on your phone and tablet.")
        Section("Sync with a shared file (Google Drive folder)") {
            val path = remember(st.rev) { data.sharedSync.path }
            if (path == null) {
                Note("Every device that uses the same file shows the same firearms, ammo, bullets, target cards and round counts after Sync. " +
                    "Put the file in a Google Drive folder: with Google Drive for desktop installed it is on this computer too, for example G:\\My Drive\\Skyward\\skyward-sync.json.")
                Note("First device: create the file. Other devices: choose that same file.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { chooseShared() }, enabled = busy == null) { Text("Choose the shared file…") }
                    OutlinedButton(onClick = { createShared() }, enabled = busy == null) { Text("Create a new shared file…") }
                }
            } else {
                Text("File: $path")
                val s = data.sharedSync
                Note(when {
                    busy == "Syncing…" -> "Syncing…"
                    s.lastResult == null -> "Not synced yet."
                    !s.lastOk -> "⚠ " + s.lastResult
                    else -> "Synced ${CalcCore.time(s.lastSyncAt)} · ${s.lastResult}"
                })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { sync() }, enabled = busy == null) { Text("Sync") }
                    OutlinedButton(onClick = { chooseShared() }, enabled = busy == null) { Text("Change file…") }
                    TextButton(onClick = {
                        st.ask("Stop using this file?", "Everything stays on this computer, and the file stays where it is.", "Stop") { data.sharedSync.stop(); st.changed() }
                    }, enabled = busy == null) { Text("Stop using this file") }
                }
                Note("Sync reads the file, adds what is new on either side, applies deletions, keeps the newest change of each record and writes the result back. " +
                    "It also runs when the app starts. Settings are not synced.")
            }
        }
        Section("What is on this computer") {
            Text("${snap.rifles.size} firearms · ${snap.loads.size} ammo · ${snap.bullets.size} of your own bullets · " +
                "${store.libraryBullets().size} library bullets · ${snap.shots.size} round-count entries")
            Note("Data folder: ${data.baseDir.absolutePath}")
            OutlinedButton(onClick = {
                runCatching { Desktop.getDesktop().open(data.baseDir) }.onFailure { st.tell("Can't open the folder", data.baseDir.absolutePath) }
            }) { Text("Open data folder") }
            if (data.store.problems.isNotEmpty()) {
                Text("Some files could not be read and were moved aside (nothing was deleted):", color = MaterialTheme.colorScheme.error)
                data.store.problems.forEach { Note(it) }
            }
        }
        Section("Save a backup (to keep, or to put on the phone)") {
            Note("One file with all your firearms, ammo, own bullets, target cards and round counts. It is the same file the phone makes with More > Backup & sync > Export everything (backup).")
            Note("Export chosen parts… saves only what you tick, for example one firearm with its ammo.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { export() }, enabled = busy == null) { Text("Export everything (backup)…") }
                OutlinedButton(onClick = { exportParts() }, enabled = busy == null) { Text("Export chosen parts…") }
            }
        }
        Section("Import (from the phone or another app)") {
            Note("Pick one or more files: a Skyward Ballistics backup or export from the phone, an Empirical Precision backup, or a bullet table (.csv) or drag file — or a .zip of them. " +
                "On the phone, make the file with More > Backup & sync > Export everything (backup), then copy it to this computer (USB cable, e-mail, or a cloud folder).")
            Note("When you pick one file, its contents are listed first so you can choose what to bring in.")
            Button(onClick = { import() }, enabled = busy == null) { Text("Import files…") }
            busy?.let { Text(it, fontWeight = FontWeight.SemiBold) }
        }
        Section("Moving data between the phone and this computer") {
            Note("Phone → computer: on the phone, More > Backup & sync > Export everything (backup). Copy the file over, then press Import files… here.")
            Note("Computer → phone: press Export everything (backup)… here. Copy the file to the phone, then on the phone More > Backup & sync > Import files….")
            Note("Importing the same file twice is safe: matching records are updated, not doubled. When both sides changed the same record, the newer change wins.")
            Note("To keep both sides the same without moving files by hand, use \"Sync with a shared file\" above on both.")
        }
        Section("Safety copies") {
            Note("Taken automatically before every import and library change, in ${data.snapshots.dir.absolutePath}. The newest 10 are kept.")
            if (copies.isEmpty()) Note("None yet.")
            copies.forEach { c ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${CalcCore.time(c.takenAt)} · ${c.reason}")
                        Note("${c.counts.firearms} firearms, ${c.counts.ammo} ammo, ${c.counts.bullets} own bullets, ${c.counts.library} library bullets")
                    }
                    TextButton(onClick = {
                        st.ask("Go back to this copy?", "Your data is replaced with the copy from ${CalcCore.time(c.takenAt)}. A copy of how it is now is taken first, so you can come back.", "Restore") {
                            runCatching { data.restoreSafetyCopy(c.id) }
                                .onSuccess { st.settings = store.settings(); st.changed(); st.tell("Restored", "Your data is back as it was on ${CalcCore.time(c.takenAt)}.") }
                                .onFailure { e -> st.tell("Couldn't restore", e.message ?: e.toString()) }
                        }
                    }) { Text("Restore…") }
                }
            }
        }
    }
}
