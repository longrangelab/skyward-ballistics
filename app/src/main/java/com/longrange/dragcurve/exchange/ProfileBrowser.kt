package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.ProfileSource
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.model.Recipe
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.store.newId

enum class BrowseKind { FIREARM, AMMO }

enum class BrowseStatus(val label: String) {
    NEW("New"), UPDATE("Will update"), SAME("Already the same");
}

data class BrowseEntry(
    val key: String,
    val sourceId: String,
    val sourceName: String,
    val kind: BrowseKind,
    val recordId: String,
    val title: String,
    val summary: String,
    val status: BrowseStatus,
    val cartridge: String = "",
    val firearmRecordId: String? = null,
    val localRifleId: String? = null,
    val details: List<Pair<String, String>> = emptyList(),
    val bullet: EmpiricalLink.BulletMatch? = null,
    val firearmAmbiguous: Boolean = false,
    val searchText: String = "",
    val sourceStamp: Long = 0,
)

data class BrowsePick(
    val entries: List<BrowseEntry>,
    val bullets: Map<String, String> = emptyMap(),
    val defaultRifleId: String? = null,
    val targetRifleId: String? = null,
    val ambiguousRifleId: String? = null,
)

interface ProfileSourceReader {
    val formatName: String
    fun accepts(text: String): Boolean
    fun entries(source: ProfileSource, text: String, store: ProfileStore, targetRifleId: String? = null): List<BrowseEntry>
    fun import(text: String, store: ProfileStore, pick: BrowsePick): ImportReport
}

object ProfileBrowser {
    const val LINKED_EP_ID = "empirical-link"
    const val LINKED_EP_NAME = "Empirical Precision (linked backup)"

    val readers: List<ProfileSourceReader> = listOf(EmpiricalSourceReader, SkywardSourceReader)

    fun readerFor(text: String): ProfileSourceReader =
        readers.firstOrNull { runCatching { it.accepts(text) }.getOrDefault(false) }
            ?: throw IllegalArgumentException("This file is not a database this app can browse. Link an Empirical Precision backup, or a Skyward Ballistics library or backup file.")

    fun sources(store: ProfileStore): List<ProfileSource> {
        val own = store.profileSources()
        val ep = store.empiricalLink()
        return if (ep == null || own.any { it.uri == ep }) own else listOf(ProfileSource(LINKED_EP_ID, ep, LINKED_EP_NAME)) + own
    }

    fun add(store: ProfileStore, uri: String, name: String): ProfileSource {
        val all = store.profileSources()
        val ex = all.firstOrNull { it.uri == uri }
        val src = ex?.copy(name = name.ifBlank { ex.name }) ?: ProfileSource(newId(), uri, name.ifBlank { "Database" })
        store.saveProfileSources(if (ex == null) all + src else all.map { if (it.id == ex.id) src else it })
        return src
    }

    fun rename(store: ProfileStore, id: String, name: String) {
        if (name.isBlank()) return
        store.saveProfileSources(store.profileSources().map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun remove(store: ProfileStore, id: String) = store.saveProfileSources(store.profileSources().filterNot { it.id == id })

    fun markRead(store: ProfileStore, id: String, at: Long, error: String = "") {
        val all = store.profileSources()
        if (all.none { it.id == id }) return
        store.saveProfileSources(all.map { if (it.id == id) it.copy(lastReadAt = if (error.isEmpty()) at else it.lastReadAt, lastError = error) else it })
    }

    data class Filter(
        val kind: BrowseKind,
        val query: String = "",
        val sourceId: String? = null,
        val cartridge: String? = null,
        val fitsRifle: Rifle? = null,
    )

    fun filter(all: List<BrowseEntry>, f: Filter): List<BrowseEntry> {
        val words = f.query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cart = f.cartridge?.let { EpNames.cartridgeKey(it) }
        return (if (f.sourceId == null) newestCopies(all) else all).filter { e ->
            e.kind == f.kind &&
                (f.sourceId == null || e.sourceId == f.sourceId) &&
                (cart == null || EpNames.cartridgeKey(e.cartridge) == cart) &&
                (f.fitsRifle == null || e.kind != BrowseKind.AMMO || fits(e, f.fitsRifle)) &&
                words.all { e.searchText.contains(it) }
        }
    }

    fun newestCopies(all: List<BrowseEntry>): List<BrowseEntry> {
        val best = HashMap<Pair<BrowseKind, String>, BrowseEntry>()
        for (e in all) {
            val k = e.kind to e.recordId
            val cur = best[k]
            if (cur == null || e.sourceStamp > cur.sourceStamp) best[k] = e
        }
        return if (best.size == all.size) all else all.filter { best[it.kind to it.recordId] === it }
    }

    fun hiddenCopies(all: List<BrowseEntry>, kind: BrowseKind): Int = all.count { it.kind == kind } - newestCopies(all).count { it.kind == kind }

    fun stampOf(text: String): Long {
        val m = Regex("\"exportedAt\"\\s*:\\s*\"([^\"]+)\"").find(text.take(4000)) ?: return 0
        return runCatching { java.time.Instant.parse(m.groupValues[1]).toEpochMilli() }.getOrDefault(0)
    }

    fun fits(e: BrowseEntry, rifle: Rifle): Boolean {
        if (e.localRifleId == rifle.id) return true
        if (e.firearmRecordId != null && (rifle.externalRef == EmpiricalLink.PREFIX + e.firearmRecordId || rifle.id == e.firearmRecordId)) return true
        val a = EpNames.cartridgeKey(e.cartridge)
        return a.isNotEmpty() && a == EpNames.cartridgeKey(rifle.cartridge)
    }

    fun cartridges(entries: List<BrowseEntry>, kind: BrowseKind): List<String> =
        entries.filter { it.kind == kind && it.cartridge.isNotBlank() }
            .groupBy { EpNames.cartridgeKey(it.cartridge) }.values
            .sortedWith(compareByDescending<List<BrowseEntry>> { it.size }.thenBy { it[0].cartridge.lowercase() })
            .map { it[0].cartridge }

    internal fun haystack(vararg parts: String?): String = parts.filter { !it.isNullOrBlank() }.joinToString(" \n ").lowercase()

    internal fun key(source: ProfileSource, kind: BrowseKind, id: String) = "${source.id}|${kind.name}|$id"

    internal fun recipeDetails(r: Recipe?): List<Pair<String, String>> {
        if (r == null) return emptyList()
        fun n(v: Double, places: Int) = BulletCsvFormat.num(Math.round(v * Math.pow(10.0, places.toDouble())) / Math.pow(10.0, places.toDouble()))
        return listOfNotNull(
            r.cartridge.takeIf { it.isNotBlank() }?.let { "Cartridge" to it },
            r.bullet.takeIf { it.isNotBlank() }?.let { "Bullet (as loaded)" to it },
            r.powder.takeIf { it.isNotBlank() }?.let { "Powder" to it },
            r.chargeGr?.let { "Charge weight" to n(it, 2) + " gr" },
            r.primer.takeIf { it.isNotBlank() }?.let { "Primer" to it },
            r.brass.takeIf { it.isNotBlank() }?.let { "Case (brass)" to it },
            r.firings?.let { "Times fired" to it.toString() },
            r.coalIn?.let { "Overall length (COAL)" to n(it, 4) + " in" },
            r.cbtoIn?.let { "Base to ogive (CBTO)" to n(it, 4) + " in" },
            r.cbtoComparator.takeIf { it.isNotBlank() }?.let { "CBTO comparator" to it },
            r.shoulderBumpIn?.let { "Shoulder bump" to n(it, 4) + " in" },
            r.shoulderComparator.takeIf { it.isNotBlank() }?.let { "Shoulder comparator" to it },
        )
    }
}

object EmpiricalSourceReader : ProfileSourceReader {
    override val formatName = "Empirical Precision backup"

    override fun accepts(text: String): Boolean = text.contains("\"${Ep.BACKUP_FORMAT}\"")

    override fun entries(source: ProfileSource, text: String, store: ProfileStore, targetRifleId: String?): List<BrowseEntry> {
        val p = EmpiricalLink.preview(text, store.snapshot(), { id -> store.bullet(id) }, store.libraryBullets(), targetRifleId)
        val stamp = ProfileBrowser.stampOf(text)
        fun status(s: EmpiricalLink.Status) = when (s) {
            EmpiricalLink.Status.NEW -> BrowseStatus.NEW; EmpiricalLink.Status.UPDATE -> BrowseStatus.UPDATE; EmpiricalLink.Status.SAME -> BrowseStatus.SAME
        }
        val firearms = p.firearms.map { f ->
            BrowseEntry(
                key = ProfileBrowser.key(source, BrowseKind.FIREARM, f.epId), sourceId = source.id, sourceName = source.name,
                kind = BrowseKind.FIREARM, recordId = f.epId, title = f.title, summary = f.detail, status = status(f.status),
                cartridge = f.cartridge, localRifleId = f.localRifleId, details = f.details,
                searchText = ProfileBrowser.haystack(f.title, f.detail, f.cartridge), sourceStamp = stamp,
            )
        }
        val loads = p.loads.map { l ->
            val mv = l.mvFps?.let { BulletCsvFormat.num(Math.round(it).toDouble()) + " fps" }
            val bulletName = l.bullet?.matched?.name ?: l.bullet?.epName
            val r = l.recipe
            val charge = r?.chargeGr?.let { BulletCsvFormat.num(Math.round(it * 10.0) / 10.0) + " gr" + (r.powder.takeIf { p -> p.isNotBlank() }?.let { " $it" } ?: "") }
                ?: r?.powder?.takeIf { it.isNotBlank() }
            val mvLine = when {
                l.mvFps == null -> "not in the file: enter it after importing"
                l.mvSource.startsWith(EmpiricalLink.MV_CHRONO) -> "$mv, measured: chronograph string ${l.mvSource.removePrefix(EmpiricalLink.MV_CHRONO)}"
                else -> "$mv, Empirical Precision's prediction, not measured"
            }
            BrowseEntry(
                key = ProfileBrowser.key(source, BrowseKind.AMMO, l.epId), sourceId = source.id, sourceName = source.name,
                kind = BrowseKind.AMMO, recordId = l.epId, title = l.title,
                summary = listOfNotNull(l.firearmName.ifBlank { null }, l.cartridge.ifBlank { null }, bulletName, charge, mv).joinToString(" · "),
                status = status(l.status), cartridge = l.cartridge, firearmRecordId = l.epFirearmId,
                localRifleId = l.localRifleId,
                details = listOfNotNull(
                    ("Firearm" to l.firearmName.ifBlank { "none in the file" }),
                    l.bullet?.let { "Bullet used here" to (it.matched?.let { b -> b.name } ?: it.fallbackLabel) },
                    "Muzzle velocity" to mvLine,
                    "Zero" to "not in Empirical Precision: 100 yd until you confirm it",
                ) + ProfileBrowser.recipeDetails(r) + listOfNotNull(l.notes.takeIf { it.isNotBlank() }?.let { "Notes" to it }),
                bullet = l.bullet, firearmAmbiguous = l.firearmGuessed,
                searchText = ProfileBrowser.haystack(l.title, l.firearmName, l.cartridge, bulletName, l.bullet?.epName, r?.bullet, r?.powder, r?.primer, r?.brass, l.notes),
                sourceStamp = stamp,
            )
        }
        return firearms + loads
    }

    override fun import(text: String, store: ProfileStore, pick: BrowsePick): ImportReport {
        val choice = EmpiricalLink.Choice(
            loadIds = pick.entries.filter { it.kind == BrowseKind.AMMO }.map { it.recordId }.toSet(),
            firearmIds = pick.entries.filter { it.kind == BrowseKind.FIREARM }.map { it.recordId }.toSet(),
            bullets = pick.bullets, defaultRifleId = pick.defaultRifleId,
            targetRifleId = pick.targetRifleId, ambiguousRifleId = pick.ambiguousRifleId,
        )
        val (libs, report) = EmpiricalLink.importSelected(text, store.snapshot(), { id -> store.bullet(id) }, store.libraryBullets(), choice)
        store.replaceAll(libs)
        runCatching { store.saveEmpiricalCatalog(EpCatalog.fromJson(store.empiricalCatalog()).plus(EmpiricalLink.catalog(text)).toJson()) }
        return report
    }
}

object SkywardSourceReader : ProfileSourceReader {
    override val formatName = "Skyward Ballistics library"

    override fun accepts(text: String): Boolean =
        (listOf(SkywardFormat.FORMAT) + SkywardFormat.LEGACY_FORMATS).any { text.contains("\"$it\"") }

    private fun placed(l: Load, target: String?, mine: Map<String, Load>): Load {
        if (target == null) return l
        val ex = mine[l.id]
        return l.copy(id = if (ex == null || ex.rifleId == target) l.id else com.longrange.dragcurve.store.stableId("${l.id}@$target"), rifleId = target)
    }

    override fun entries(source: ProfileSource, text: String, store: ProfileStore, targetRifleId: String?): List<BrowseEntry> {
        val res = SkywardFormat.importDetailed(text)
        val stamp = ProfileBrowser.stampOf(text)
        val libs = res.libs
        val here = store.snapshot()
        val myRifles = here.rifles.associateBy { it.id }
        val myLoads = here.loads.associateBy { it.id }
        val fileRifles = libs.rifles.associateBy { it.id }
        val fileBullets = libs.bullets.associateBy { it.id }
        fun <T> status(mine: T?, theirs: T, bare: (T) -> T) = when {
            mine == null -> BrowseStatus.NEW
            bare(mine) == bare(theirs) -> BrowseStatus.SAME
            else -> BrowseStatus.UPDATE
        }
        fun n(v: Double) = BulletCsvFormat.num(Math.round(v * 1000.0) / 1000.0)
        val firearms = libs.rifles.map { r ->
            val summary = listOfNotNull(r.cartridge.ifBlank { null }, "1:${n(r.twistIn)} twist", r.barrelLengthIn?.let { "${n(it)} in barrel" }).joinToString(" · ")
            BrowseEntry(
                key = ProfileBrowser.key(source, BrowseKind.FIREARM, r.id), sourceId = source.id, sourceName = source.name,
                kind = BrowseKind.FIREARM, recordId = r.id, title = r.name, summary = summary,
                status = status(myRifles[r.id], r) { it.copy(updatedAt = 0) },
                cartridge = r.cartridge, localRifleId = myRifles[r.id]?.id,
                details = listOfNotNull(
                    r.cartridge.takeIf { it.isNotBlank() }?.let { "Cartridge" to it },
                    "Barrel twist" to "1:${n(r.twistIn)} in, ${r.twistDirection.name.lowercase()} hand",
                    r.barrelLengthIn?.let { "Barrel length" to "${n(it)} in" },
                    "Sight height" to "${n(r.sightHeightIn)} in",
                    "Elevation clicks" to "${n(r.elevationClick)} ${r.elevationUnit.name}",
                    "Windage clicks" to "${n(r.windageClick)} ${r.windageUnit.name}",
                    r.notes.takeIf { it.isNotBlank() }?.let { "Notes" to it },
                ),
                searchText = ProfileBrowser.haystack(r.name, r.cartridge, r.notes), sourceStamp = stamp,
            )
        }
        val target = targetRifleId?.takeIf { myRifles.containsKey(it) }
        val loads = libs.loads.map { l0 ->
            val l = placed(l0, target, myLoads)
            val rifle = if (target != null) myRifles[target] else fileRifles[l.rifleId] ?: myRifles[l.rifleId]
            val rifleName = rifle?.name ?: res.rifleNameHints[l.id] ?: ""
            val b: Bullet? = fileBullets[l.bulletId] ?: store.bullet(l.bulletId)
            val rc = l.recipe
            val cartridge = rc?.cartridge?.takeIf { it.isNotBlank() } ?: rifle?.cartridge ?: ""
            val charge = rc?.chargeGr?.let { n(it) + " gr" + (rc.powder.takeIf { p -> p.isNotBlank() }?.let { " $it" } ?: "") } ?: rc?.powder?.takeIf { it.isNotBlank() }
            val mv = l.muzzleVelocityFps.takeIf { it > 0 }?.let { "${n(Math.round(it).toDouble())} fps" }
            BrowseEntry(
                key = ProfileBrowser.key(source, BrowseKind.AMMO, l0.id), sourceId = source.id, sourceName = source.name,
                kind = BrowseKind.AMMO, recordId = l0.id, title = l.name,
                summary = listOfNotNull(rifleName.ifBlank { null }, cartridge.ifBlank { null }, b?.name, charge, mv).joinToString(" · "),
                status = status(myLoads[l.id], l) { it.copy(updatedAt = 0) },
                cartridge = cartridge, firearmRecordId = l0.rifleId,
                localRifleId = myLoads[l.id]?.rifleId ?: if (target == null) myRifles[l.rifleId]?.id else null,
                details = listOfNotNull(
                    "Firearm" to rifleName.ifBlank { "not in the file" },
                    "Bullet" to (b?.name ?: "not in the file"),
                    "Muzzle velocity" to (mv ?: "not set"),
                    "Zero" to "${n(l.zeroRangeYd)} yd" + (if (l.zeroConfirmed) "" else " (not confirmed)"),
                ) + ProfileBrowser.recipeDetails(rc) + listOfNotNull(l.notes.takeIf { it.isNotBlank() }?.let { "Notes" to it }),
                searchText = ProfileBrowser.haystack(l.name, rifleName, cartridge, b?.name, b?.maker, rc?.bullet, rc?.powder, rc?.primer, rc?.brass, l.notes),
                sourceStamp = stamp,
            )
        }
        return firearms + loads
    }

    override fun import(text: String, store: ProfileStore, pick: BrowsePick): ImportReport {
        val res = SkywardFormat.importDetailed(text)
        val loadIds = pick.entries.filter { it.kind == BrowseKind.AMMO }.map { it.recordId }.toSet()
        val mine = store.loads(null).associateBy { it.id }
        val target = pick.targetRifleId?.takeIf { id -> store.rifles().any { it.id == id } }
        val loads: List<Load> = res.libs.loads.filter { it.id in loadIds }.map { placed(it, target, mine) }
        val rifleIds = pick.entries.filter { it.kind == BrowseKind.FIREARM }.map { it.recordId }.toSet() + (if (target == null) loads.map { it.rifleId } else emptyList())
        val bulletIds = loads.map { it.bulletId }.toSet()
        val part = Libraries(
            rifles = res.libs.rifles.filter { it.id in rifleIds },
            loads = loads,
            bullets = res.libs.bullets.filter { it.id in bulletIds },
        )
        val hints = if (target == null) res.rifleNameHints.filterKeys { it in loadIds } else emptyMap()
        val before = store.snapshot()
        val rep = LibraryFiles.importResult(store, ImportResult(part, hints, kind = LibraryKind.MASTER))
        val after = store.snapshot()
        fun <T> changed(a: List<T>, b: List<T>, id: (T) -> String): Pair<Int, Int> {
            val old = a.associateBy(id)
            return b.count { old[id(it)] == null } to b.count { o -> old[id(o)].let { it != null && it != o } }
        }
        val (ra, ru) = changed(before.rifles, after.rifles) { it.id }
        val (la, lu) = changed(before.loads, after.loads) { it.id }
        val (ba, _) = changed(before.bullets, after.bullets) { it.id }
        fun n(c: Int, one: String) = if (c == 1) "1 $one" else "$c ${one}s"
        val parts = listOfNotNull(
            ra.takeIf { it > 0 }?.let { n(it, "firearm") + " added" }, ru.takeIf { it > 0 }?.let { n(it, "firearm") + " updated" },
            la.takeIf { it > 0 }?.let { "$it ammo added" }, lu.takeIf { it > 0 }?.let { "$it ammo updated" },
            ba.takeIf { it > 0 }?.let { n(it, "bullet") + " added to My bullets" },
        )
        return rep.copy(summary = if (parts.isEmpty()) "Nothing to change: everything was already up to date." else parts.joinToString(". ") + ".")
    }
}
