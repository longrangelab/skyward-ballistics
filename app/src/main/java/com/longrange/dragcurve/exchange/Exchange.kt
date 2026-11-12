package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.store.JsonProfileStore

data class ImportResult(
    val libs: Libraries,
    val rifleNameHints: Map<String, String> = emptyMap(),
    val messages: List<String> = emptyList(),
    val kind: LibraryKind? = null,
)

interface DetailedImport {
    fun importDetailed(text: String): ImportResult
}

object LibraryFiles {
    fun select(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet? = { null }): Libraries {
        fun referenced(): List<Bullet> {
            val own = libs.bullets.associateBy { it.id }
            return libs.loads.map { it.bulletId }.distinct().mapNotNull { own[it] ?: resolveBullet(it) }
        }
        return when (kind) {
            LibraryKind.RIFLES -> Libraries(rifles = libs.rifles)
            LibraryKind.BULLETS -> Libraries(bullets = libs.bullets)
            LibraryKind.LOADS -> Libraries(loads = libs.loads, bullets = referenced())
            LibraryKind.MASTER -> Libraries(libs.rifles, libs.loads, (libs.bullets + referenced()).distinctBy { it.id }, libs.deleted, libs.cards, libs.shots)
        }
    }

    fun parse(format: LibraryFormat, text: String): ImportResult =
        if (format is DetailedImport) format.importDetailed(text) else ImportResult(format.import(text))

    fun importInto(store: ProfileStore, format: LibraryFormat, text: String): ImportReport = importResult(store, parse(format, text))

    fun importResult(store: ProfileStore, res: ImportResult): ImportReport {
        var libs = res.libs
        var libReport: ImportReport? = null
        if (res.kind == LibraryKind.BULLETS && libs.bullets.any { it.source == "library" }) {
            val (lib, own) = libs.bullets.partition { it.source == "library" }
            libReport = store.importBulletLibrary(Libraries(bullets = lib))
            libs = libs.copy(bullets = own)
        }
        val rep = if (store is JsonProfileStore) store.merge(libs, res.rifleNameHints, res.kind) else store.merge(libs)
        val all = res.messages + (libReport?.messages ?: emptyList()) + rep.messages
        return if (libReport == null) rep.copy(messages = all) else ImportReport(
            added = rep.added + libReport.added, updated = rep.updated + libReport.updated,
            renamed = rep.renamed + libReport.renamed, skipped = rep.skipped + libReport.skipped, messages = all,
        )
    }
}

object FormatRegistry {
    @Volatile var masterDbJson: () -> String? = { null }
    @Volatile var libraryBullets: () -> List<Bullet> = { emptyList() }

    val all: List<LibraryFormat>
        get() = listOf(
            SkywardFormat,
            EmpiricalSessionFormat(),
            EmpiricalBackupFormat(masterDbJson = { masterDbJson() }, libraryBullets = { libraryBullets() }),
            EmpiricalLibraryFormat,
            BulletCsvFormat,
            DragFileFormat,
        )

    fun byId(id: String): LibraryFormat? =
        if (id in SkywardFormat.ALIASES) SkywardFormat else all.firstOrNull { it.id == id }
    fun exporters(kind: LibraryKind): List<LibraryFormat> = all.filter { kind in it.exportKinds }
    fun importers(): List<LibraryFormat> = all.filter { it.importKinds.isNotEmpty() }

    data class Detected(val format: LibraryFormat, val result: ImportResult)

    fun detect(text: String): Detected {
        val errors = ArrayList<String>()
        for (f in importers()) {
            try {
                val res = LibraryFiles.parse(f, text)
                if (res.libs.rifles.isNotEmpty() || res.libs.loads.isNotEmpty() || res.libs.bullets.isNotEmpty()) return Detected(f, res)
                errors += "${f.displayName}: the file has no records."
            } catch (e: Exception) {
                errors += "${f.displayName}: ${e.message}"
            }
        }
        throw IllegalArgumentException("No supported format recognised this file.\n\n" + errors.joinToString("\n"))
    }
}
