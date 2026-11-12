package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.store.JsonProfileStore

enum class PartKind(val label: String) {
    FIREARM("Firearms"), AMMO("Ammo"), BULLET("My bullets"), LIBRARY("Bullet library"), CARD("Target cards"), ROUNDS("Round counts");
}

enum class PartStatus(val label: String) {
    NEW("New"), UPDATE("Will update yours"), SAME("Already the same"), NEWER_HERE("Yours is newer, kept"), COPY("Differs, added as a copy");
}

data class Part(
    val kind: PartKind,
    val id: String,
    val title: String,
    val summary: String,
    val status: PartStatus? = null,
) {
    val key: String get() = Parts.key(kind, id)
}

data class Selected(val libs: Libraries, val broughtAlong: List<Part>, val rifleNameHints: Map<String, String> = emptyMap()) {
    val isEmpty: Boolean get() = libs.rifles.isEmpty() && libs.loads.isEmpty() && libs.bullets.isEmpty() && libs.cards.isEmpty() && libs.shots.isEmpty()
}

object Parts {
    fun key(kind: PartKind, id: String) = "${kind.name}|$id"

    fun everything(store: ProfileStore): Libraries {
        val snap = store.snapshot()
        return snap.copy(bullets = snap.bullets + store.libraryBullets().map { if (it.source == "library") it else it.copy(source = "library") })
    }

    fun masterText(store: ProfileStore): String = SkywardFormat.export(LibraryKind.MASTER, everything(store)) { id -> store.bullet(id) }

    private fun isLibrary(b: Bullet) = b.source == "library"

    fun list(all: Libraries, here: ProfileStore? = null, kind: LibraryKind? = LibraryKind.MASTER): List<Part> {
        val rifles = all.rifles.associateBy { it.id }
        val bullets = all.bullets.associateBy { it.id }
        val mine = here?.let { Mine(it, kind) }
        fun n(v: Double) = BulletCsvFormat.num(Math.round(v * 1000.0) / 1000.0)
        val out = ArrayList<Part>()
        for (r in all.rifles.sortedBy { it.name.lowercase() }) {
            out += Part(PartKind.FIREARM, r.id, r.name.ifBlank { "Unnamed firearm" },
                listOfNotNull(r.cartridge.ifBlank { null }, "1:${n(r.twistIn)} twist").joinToString(" · "),
                mine?.status(mine.rifles[r.id], r.updatedAt, r.copy(updatedAt = 0) == mine.rifles[r.id]?.copy(updatedAt = 0)))
        }
        for (l in all.loads.sortedWith(compareBy({ rifles[it.rifleId]?.name?.lowercase() ?: "" }, { it.name.lowercase() }))) {
            val b = bullets[l.bulletId] ?: here?.bullet(l.bulletId)
            out += Part(PartKind.AMMO, l.id, l.name.ifBlank { "Unnamed ammo" },
                listOfNotNull(rifles[l.rifleId]?.name ?: here?.rifles()?.firstOrNull { it.id == l.rifleId }?.name, b?.name,
                    l.muzzleVelocityFps.takeIf { it > 0 }?.let { "${Math.round(it)} fps" }).joinToString(" · "),
                mine?.status(mine.loads[l.id], l.updatedAt, l.copy(updatedAt = 0) == mine.loads[l.id]?.copy(updatedAt = 0)))
        }
        val (lib, own) = all.bullets.partition(::isLibrary)
        for (b in own.sortedBy { it.name.lowercase() }) {
            val ex = mine?.bullets?.get(b.id)
            out += Part(PartKind.BULLET, b.id, b.name.ifBlank { "Unnamed bullet" }, bulletLine(b),
                mine?.status(ex, b.updatedAt, ex?.copy(updatedAt = 0) == b.copy(updatedAt = 0, source = "user", libraryOrder = null, collection = "")))
        }
        for (b in lib.sortedWith(compareBy<Bullet>({ it.libraryOrder ?: Int.MAX_VALUE }, { it.maker.lowercase() }, { it.name.lowercase() }))) {
            val ex = mine?.library?.get(b.id)
            val st = mine?.status(ex, b.updatedAt, ex?.copy(updatedAt = 0) == b.copy(updatedAt = 0))?.let { if (it == PartStatus.COPY) PartStatus.UPDATE else it }
            val title = if (b.maker.isBlank() || b.name.startsWith(b.maker, ignoreCase = true)) b.name else "${b.maker} ${b.name}"
            out += Part(PartKind.LIBRARY, b.id, title.ifBlank { "Unnamed bullet" }, bulletLine(b), st)
        }
        val loads = all.loads.associateBy { it.id }
        for (c in all.cards.sortedBy { it.name.lowercase() }) {
            out += Part(PartKind.CARD, c.id, c.name.ifBlank { "Target card" },
                listOfNotNull(rifles[c.rifleId]?.name, loads[c.loadId]?.name, "${c.targets.size} target${if (c.targets.size == 1) "" else "s"}").joinToString(" · "),
                mine?.status(mine.cards[c.id], c.updatedAt, c.copy(updatedAt = 0) == mine.cards[c.id]?.copy(updatedAt = 0)))
        }
        for ((rifleId, list) in all.shots.groupBy { it.rifleId }) {
            val name = rifles[rifleId]?.name ?: here?.rifles()?.firstOrNull { it.id == rifleId }?.name ?: "A firearm"
            val total = list.sumOf { it.count }
            val st = mine?.let { m ->
                val ex = list.map { s -> m.shots[s.id] to s }
                when {
                    ex.all { it.first == null } -> PartStatus.NEW
                    ex.all { (a, b) -> a != null && a.copy(updatedAt = 0) == b.copy(updatedAt = 0) } -> PartStatus.SAME
                    else -> PartStatus.UPDATE
                }
            }
            out += Part(PartKind.ROUNDS, rifleId, name, "$total rounds in ${list.size} entr${if (list.size == 1) "y" else "ies"}", st)
        }
        return out
    }

    private fun bulletLine(b: Bullet): String {
        fun n(v: Double) = BulletCsvFormat.num(Math.round(v * 1000.0) / 1000.0)
        return listOfNotNull("${n(b.diameterIn)} in", "${n(b.weightGr)} gr", b.dragModel.name.takeIf { b.dragModel.name != "CUSTOM" } ?: "drag curve").joinToString(" · ")
    }

    private class Mine(store: ProfileStore, val kind: LibraryKind?) {
        val snap = store.snapshot()
        val rifles = snap.rifles.associateBy { it.id }
        val loads = snap.loads.associateBy { it.id }
        val bullets = snap.bullets.associateBy { it.id }
        val library = store.libraryBullets().associateBy { it.id }
        val cards = snap.cards.associateBy { it.id }
        val shots = snap.shots.associateBy { it.id }
        fun status(local: Any?, stampThere: Long, same: Boolean): PartStatus {
            if (local == null) return PartStatus.NEW
            if (same) return PartStatus.SAME
            val stampHere = when (local) {
                is com.longrange.dragcurve.model.Rifle -> local.updatedAt
                is com.longrange.dragcurve.model.Load -> local.updatedAt
                is Bullet -> local.updatedAt
                is com.longrange.dragcurve.model.TargetCard -> local.updatedAt
                else -> 0L
            }
            return when {
                stampThere == 0L && kind != LibraryKind.MASTER -> PartStatus.COPY
                stampThere > stampHere -> PartStatus.UPDATE
                else -> PartStatus.NEWER_HERE
            }
        }
    }

    fun select(all: Libraries, picked: Set<String>, here: ProfileStore? = null, rifleNameHints: Map<String, String> = emptyMap()): Selected {
        fun on(kind: PartKind, id: String) = key(kind, id) in picked
        val rifleIds = LinkedHashSet<String>(); val loadIds = LinkedHashSet<String>(); val bulletIds = LinkedHashSet<String>()
        val cardIds = LinkedHashSet<String>(); val shotRifles = LinkedHashSet<String>()
        all.rifles.filter { on(PartKind.FIREARM, it.id) }.forEach { rifleIds += it.id }
        all.loads.filter { on(PartKind.AMMO, it.id) }.forEach { loadIds += it.id }
        all.bullets.filter { on(if (isLibrary(it)) PartKind.LIBRARY else PartKind.BULLET, it.id) }.forEach { bulletIds += it.id }
        all.cards.filter { on(PartKind.CARD, it.id) }.forEach { cardIds += it.id }
        all.shots.map { it.rifleId }.distinct().filter { on(PartKind.ROUNDS, it) }.forEach { shotRifles += it }
        val ticked = Triple(rifleIds.toSet(), loadIds.toSet(), bulletIds.toSet())

        val hereRifles = here?.rifles()?.map { it.id }?.toSet()
        val hereLoads = here?.loads(null)?.map { it.id }?.toSet()
        fun hasRifle(id: String) = hereRifles?.contains(id) == true
        fun hasLoad(id: String) = hereLoads?.contains(id) == true
        fun hasBullet(id: String) = here?.bullet(id) != null
        val fileRifles = all.rifles.map { it.id }.toSet()
        val fileLoads = all.loads.associateBy { it.id }
        val fileBullets = all.bullets.map { it.id }.toSet()

        for (c in all.cards.filter { it.id in cardIds }) {
            if (c.rifleId in fileRifles && !hasRifle(c.rifleId)) rifleIds += c.rifleId
            if (c.loadId in fileLoads && !hasLoad(c.loadId)) loadIds += c.loadId
        }
        for (rid in shotRifles) if (rid in fileRifles && !hasRifle(rid)) rifleIds += rid
        for (lid in loadIds.toList()) {
            val l = fileLoads[lid] ?: continue
            if (l.rifleId in fileRifles && !hasRifle(l.rifleId)) rifleIds += l.rifleId
            if (l.bulletId in fileBullets && !hasBullet(l.bulletId)) bulletIds += l.bulletId
        }
        val libs = Libraries(
            rifles = all.rifles.filter { it.id in rifleIds },
            loads = all.loads.filter { it.id in loadIds },
            bullets = all.bullets.filter { it.id in bulletIds },
            cards = all.cards.filter { it.id in cardIds },
            shots = all.shots.filter { it.rifleId in shotRifles },
        )
        val extra = list(Libraries(
            rifles = libs.rifles.filter { it.id !in ticked.first },
            loads = libs.loads.filter { it.id !in ticked.second },
            bullets = libs.bullets.filter { it.id !in ticked.third },
        ))
        return Selected(libs, extra, rifleNameHints.filterKeys { it in loadIds })
    }

    fun exportText(store: ProfileStore, picked: Set<String>): String =
        SkywardFormat.export(LibraryKind.MASTER, select(everything(store), picked).libs) { null }

    fun import(store: ProfileStore, file: ImportResult, picked: Set<String>): ImportReport {
        val sel = select(file.libs, picked, store, file.rifleNameHints)
        val tickedLibrary = sel.libs.bullets.filter { isLibrary(it) && key(PartKind.LIBRARY, it.id) in picked }
        val hereLibrary = store.libraryBullets().associateBy { it.id }
        val newer = tickedLibrary.filter { b -> hereLibrary[b.id].let { it == null || (b.updatedAt == 0L || b.updatedAt > it.updatedAt) } }
        val libReport = if (newer.isNotEmpty()) store.importBulletLibrary(Libraries(bullets = newer)) else null
        var libs = sel.libs.copy(bullets = sel.libs.bullets - tickedLibrary.toSet(), deleted = emptyList())
        val tombs = (store as? JsonProfileStore)?.tombstones()?.associateBy { it.kind to it.id } ?: emptyMap()
        if (tombs.isNotEmpty()) {
            fun newer(kind: String, id: String, t: Long): Long = tombs[kind to id]?.let { maxOf(t, it.deletedAt + 1) } ?: t
            libs = libs.copy(
                rifles = libs.rifles.map { it.copy(updatedAt = newer(JsonProfileStore.RIFLE, it.id, it.updatedAt)) },
                loads = libs.loads.map { it.copy(updatedAt = newer(JsonProfileStore.LOAD, it.id, it.updatedAt)) },
                bullets = libs.bullets.map { if (isLibrary(it)) it else it.copy(updatedAt = newer(JsonProfileStore.BULLET, it.id, it.updatedAt)) },
                cards = libs.cards.map { it.copy(updatedAt = newer(JsonProfileStore.CARD, it.id, it.updatedAt)) },
                shots = libs.shots.map { it.copy(updatedAt = newer(JsonProfileStore.SHOT, it.id, it.updatedAt)) },
            )
        }
        val rep = LibraryFiles.importResult(store, ImportResult(libs, sel.rifleNameHints, file.messages, kind = file.kind ?: LibraryKind.MASTER))
        if (libReport == null) return rep
        return ImportReport(
            added = rep.added + libReport.added, updated = rep.updated + libReport.updated,
            renamed = rep.renamed, skipped = rep.skipped + libReport.skipped, messages = libReport.messages + rep.messages,
        )
    }
}
