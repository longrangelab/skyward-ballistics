package com.longrange.dragcurve.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.store.newId
import com.longrange.dragcurve.ui.calc.BulletBook
import com.longrange.dragcurve.ui.calc.BulletSearch
import com.longrange.dragcurve.ui.calc.Num
import com.longrange.dragcurve.ui.calc.Quantity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(st: AppState) {
    val store = st.store
    val lib = remember(st.rev) { store.libraryBullets().filter { it.replacedBy.isEmpty() } }
    var q by remember { mutableStateOf("") }
    val shown = remember(lib, q) { lib.filter { BulletSearch.matches(it, q) } }
    var current by remember { mutableStateOf<Bullet?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fmt = Fmt(st.settings)

    fun import() {
        val f = FilePicker.open("Import a bullet library file").firstOrNull() ?: return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { st.data.importBulletLibrary(f) } }
            busy = false
            st.changed()
            r.onSuccess { rep -> st.tell("Bullet library imported", "${store.libraryBullets().size} bullets in the library now.\n\n" + DesktopData.reportText(rep)) }
                .onFailure { e -> st.tell("Couldn't import the bullet library", e.message ?: e.toString()) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Bullet library", "Bullets with measured drag data to pick for your ammo. The library is not built in: import the Skyward bullet library file (the same file the phone uses).")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { import() }, enabled = !busy) { Text(if (busy) "Importing…" else if (lib.isEmpty()) "Import bullet library file…" else "Import or update library…") }
            if (lib.isNotEmpty()) {
                OutlinedButton(onClick = {
                    val f = FilePicker.save("Save the bullet library", st.data.defaultExportName("bullet-library")) ?: return@OutlinedButton
                    runCatching { st.data.exportLibrary(f) }
                        .onSuccess { st.tell("Library saved", "Saved ${lib.size} bullets to\n${f.absolutePath}") }
                        .onFailure { e -> st.tell("Couldn't save", e.message ?: e.toString()) }
                }) { Text("Save library to a file…") }
                TextButton(onClick = {
                    st.ask("Remove the bullet library?", "All ${lib.size} library bullets are removed from this computer. A safety copy is taken first. You can import the file again at any time.", "Remove") {
                        if (store.clearBulletLibrary()) { current = null; st.changed() }
                        else st.tell("Can't remove the library", "Some of your ammo uses library bullets. Change or delete that ammo first.")
                    }
                }) { Text("Remove library") }
            }
        }
        Gap(12)
        if (lib.isEmpty()) {
            Section("No bullet library yet") {
                Note("Use the same library file you imported on the phone (on the phone: More > Libraries > Export bullet library… saves a copy), then press Import. " +
                    "You can still enter bullets yourself under My gear > My bullets.")
            }
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBox("Search: maker, name, calibre or weight (e.g. 6.5 140)", q, { q = it }, Modifier.fillMaxWidth())
                Note("${shown.size} of ${lib.size} bullets")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.id }) { b ->
                        val sel = b.id == current?.id
                        Card(Modifier.fillMaxWidth().clickable { current = b },
                            colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(BulletSearch.title(b), fontWeight = FontWeight.SemiBold)
                                Note(BulletSearch.subtitle(b))
                            }
                        }
                    }
                }
            }
            ScrollColumn(Modifier.weight(1f).fillMaxHeight()) {
                val b = current
                if (b == null) Note("Choose a bullet to see its data and make ammo with it.")
                else {
                    Text(BulletSearch.title(b), style = MaterialTheme.typography.titleLarge)
                    Section("Bullet") {
                        Note(BulletSearch.sourceLabel(b))
                        Text("Diameter ${Num.trim(b.diameterIn, 4)} in · weight ${Num.trim(b.weightGr, 1)} gr" +
                            (b.lengthIn?.let { " · length ${Num.trim(it, 3)} in" } ?: ""))
                        if (b.caliber.isNotBlank()) Text("Calibre: ${b.caliber}")
                        if (b.dragCurve.size >= 2) Text("Measured drag curve: ${b.dragCurve.size} points, from Mach ${Num.fixed(b.dragCurve.minOf { it.mach }, 2)} to ${Num.fixed(b.dragCurve.maxOf { it.mach }, 2)}")
                        if (b.bcPoints.isNotEmpty()) {
                            val m = if (b.dragModel == DragModel.CUSTOM) "G7" else b.dragModel.name
                            Text("$m BC: " + b.bcPoints.sortedByDescending { it.minVelFps }.joinToString(", ") { p ->
                                Num.trim(p.bc, 3) + (if (p.minVelFps > 0) " from " + fmt.withUnit(Quantity.VELOCITY, p.minVelFps) else "")
                            })
                        }
                        if (b.seasonalBcPoints.isNotEmpty()) Note("Also has cold, mild and hot weather BCs.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            if (store.rifles().isEmpty()) st.tell("Add a firearm first", "Ammo belongs to a firearm. Add one under My gear > Firearms, then come back.")
                            else { st.newAmmoBullet = b.id; st.screen = Screen.GEAR }
                        }) { Text("Make ammo with this bullet") }
                        OutlinedButton(onClick = {
                            val copy = BulletBook.copyOf(b, newId(), store.userBullets().map { it.name })
                            store.saveUserBullet(copy); st.changed()
                            st.tell("Copied", "\"${copy.name}\" is now under My gear > My bullets, where you can change it.")
                        }) { Text("Copy to My bullets") }
                    }
                }
            }
        }
    }
}
