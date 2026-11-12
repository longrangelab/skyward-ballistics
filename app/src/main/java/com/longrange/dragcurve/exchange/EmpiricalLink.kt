package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Recipe
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.TwistDirection
import com.longrange.dragcurve.store.newId
import com.longrange.dragcurve.store.optDN
import com.longrange.dragcurve.store.optSN
import com.longrange.dragcurve.store.stableId
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

object EmpiricalLink {
    const val PREFIX = "empirical:"
    const val UNASSIGNED_REF = "empirical:unassigned"
    const val UNASSIGNED_NAME = "Empirical (unassigned)"
    const val REMOVED = " (removed in Empirical)"
    const val RECIPE_EDIT_WINDOW_MS = 5_000L
    const val CREATE = "*create*"
    const val EP_BULLET_CATEGORY = "Empirical single BC"
    const val NO_BC_LABEL = "No BC in file: choose a bullet"
    const val MV_PREDICTED = "Empirical Precision's predicted velocity (not measured)"
    const val MV_CHRONO = "Empirical Precision chronograph string "

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    data class Choice(
        val loadIds: Set<String> = emptySet(),
        val firearmIds: Set<String> = emptySet(),
        val bullets: Map<String, String> = emptyMap(),
        val defaultRifleId: String? = null,
        val targetRifleId: String? = null,
        val ambiguousRifleId: String? = null,
    )

    enum class Status { NEW, UPDATE, SAME }

    data class FirearmItem(
        val epId: String, val title: String, val detail: String, val status: Status,
        val cartridge: String = "",
        val localRifleId: String? = null,
        val details: List<Pair<String, String>> = emptyList(),
    )

    data class BulletMatch(
        val epBulletId: String,
        val epName: String,
        val matched: Bullet?,
        val candidates: List<Bullet>,
        val epBc: String,
        val canCreate: Boolean,
    ) {
        val noBc: Boolean get() = epBc.isEmpty()

        val fallbackLabel: String get() = if (noBc) NO_BC_LABEL else "Made from Empirical's $epBc"

        val createOption: String? get() = when {
            !canCreate -> null
            noBc -> "Make a placeholder bullet (no BC in file: you enter it)"
            else -> "Make a bullet from Empirical's $epBc"
        }
    }

    data class LoadItem(
        val epId: String, val title: String, val detail: String, val status: Status,
        val firearmEpId: String?, val firearmName: String,
        val bullet: BulletMatch?,
        val mvFps: Double?, val mvSource: String,
        val firearmGuessed: Boolean = false,
        val epFirearmId: String? = null,
        val cartridge: String = "",
        val recipe: Recipe? = null,
        val notes: String = "",
        val localRifleId: String? = null,
    )

    data class Preview(val firearms: List<FirearmItem>, val loads: List<LoadItem>, val exportedAt: String)

    fun refresh(
        backupText: String,
        current: Libraries,
        resolveBullet: (String) -> Bullet?,
        libraryBullets: List<Bullet> = emptyList(),
        masterDbJson: String? = null,
    ): Pair<Libraries, ImportReport> = Run(EpBackup.parse(backupText), current, resolveBullet, libraryBullets, masterDbJson, null).go()

    fun importSelected(
        backupText: String,
        current: Libraries,
        resolveBullet: (String) -> Bullet?,
        libraryBullets: List<Bullet>,
        choice: Choice,
    ): Pair<Libraries, ImportReport> = Run(EpBackup.parse(backupText), current, resolveBullet, libraryBullets, null, choice).go()

    fun preview(
        backupText: String, current: Libraries, resolveBullet: (String) -> Bullet?, libraryBullets: List<Bullet>,
        targetRifleId: String? = null,
    ): Preview =
        Run(EpBackup.parse(backupText), current, resolveBullet, libraryBullets, null, targetRifleId?.let { Choice(targetRifleId = it) }).preview()

    fun catalog(backupText: String): EpCatalog =
        Run(EpBackup.parse(backupText), Libraries(), { null }, emptyList(), null, null).catalog()

    private class Run(
        val bk: EpBackup,
        val current: Libraries,
        val resolveBullet: (String) -> Bullet?,
        val library: List<Bullet>,
        masterText: String?,
        val choice: Choice?,
    ) {
        val msgs = mutableListOf<String>()
        var added = 0; var updated = 0; var skipped = 0
        var riflesAdded = 0; var riflesUpdated = 0; var loadsAdded = 0; var loadsUpdated = 0
        var bulletsMade = 0; var placeholdersMade = 0
        val master: EpMaster? = masterText?.let {
            try { EpMaster.parse(it) } catch (e: Exception) { msgs += "master-db.json could not be read (${e.message}); EP library bullets become placeholders."; null }
        }
        val rifles = current.rifles.toMutableList()
        val loads = current.loads.toMutableList()
        val bullets = current.bullets.toMutableList()
        val usedIds = HashSet<String>().apply { current.rifles.forEach { add(it.id) }; current.loads.forEach { add(it.id) }; current.bullets.forEach { add(it.id) } }
        val epFirearms = bk.live("firearms")
        val epSessions = bk.live("sessions")
        val epLoads = bk.live("loads")
        val liveFirearmIds: Set<String> by lazy { epFirearms.mapTo(HashSet()) { it.getString("id") } }
        val sessionsByLoad: Map<String, List<JSONObject>> by lazy { epSessions.filter { it.optSN("loadId") != null }.groupBy { it.getString("loadId") } }
        val chronoStrings: Map<String, JSONObject> by lazy { bk.live("chronoSessions").associateBy { it.getString("id") } }
        val shotsByString: Map<String, List<JSONObject>> by lazy { bk.live("shots").filter { it.optSN("chronoSessionId") != null }.groupBy { it.getString("chronoSessionId") } }
        val firearmsByCartridge: Map<String, List<String>> by lazy {
            epFirearms.filter { it.optSN("cartridgeId") != null }.groupBy({ it.getString("cartridgeId") }, { it.getString("id") })
        }
        val infoCache = HashMap<String, BulletInfo?>()
        val candidateCache = HashMap<String, List<Bullet>>()

        fun freshId(key: String): String { val s = stableId(key); return (if (s in usedIds) newId() else s).also { usedIds += it } }

        fun go(): Pair<Libraries, ImportReport> {
            val firearmRifle = syncFirearms()
            syncLoads(firearmRifle)
            return Libraries(rifles, loads, bullets, current.deleted, current.cards, current.shots) to ImportReport(added, updated, 0, skipped, msgs, summary = summary())
        }

        fun summary(): String {
            fun n(count: Int, one: String, many: String = one + "s") = if (count == 1) "1 $one" else "$count $many"
            val parts = listOfNotNull(
                riflesAdded.takeIf { it > 0 }?.let { n(it, "firearm") + " added" },
                riflesUpdated.takeIf { it > 0 }?.let { n(it, "firearm") + " updated" },
                loadsAdded.takeIf { it > 0 }?.let { "$it ammo added" },
                loadsUpdated.takeIf { it > 0 }?.let { "$it ammo updated" },
                bulletsMade.takeIf { it > 0 }?.let { n(it, "bullet") + " created from Empirical's single BC" },
                placeholdersMade.takeIf { it > 0 }?.let { n(it, "placeholder bullet") + " created (no BC)" },
                skipped.takeIf { it > 0 }?.let { "$it skipped" },
            )
            return if (parts.isEmpty()) "Nothing to change: everything was already up to date." else parts.joinToString(". ") + "."
        }

        fun JSONObject.id(k: String): String? = optSN(k)?.takeIf { it.isNotBlank() }

        fun cartridge(id: String): JSONObject? = bk.byId("cartridges", id) ?: master?.cartridges?.get(id)
        fun cartridgeName(id: String): String = cartridge(id)?.optSN("name") ?: EpNames.cartridge(id)

        fun diameter(id: String): Pair<Double, String>? {
            val r = bk.byId("diameters", id) ?: master?.diameters?.get(id)
            r?.optDN("bulletDiameterMm")?.let { return it / Ep.MM to (r.optSN("imperialName") ?: "") }
            return Ep.DIAMETERS.firstOrNull { it.id == id }?.let { it.bulletDiameterMm / Ep.MM to it.imperialName }
        }

        fun makerName(id: String?): String {
            if (id == null) return ""
            return (bk.byId("manufacturers", id) ?: master?.manufacturers?.get(id))?.optSN("name")
                ?: Ep.MANUFACTURERS[id] ?: id
        }

        fun powderOrPrimerName(table: String, id: String): String {
            val r = bk.byId(table, id) ?: return EpNames.powderOrPrimer(id)
            val maker = makerName(r.optSN("manufacturerId"))
            val name = r.optSN("name") ?: id
            return if (maker.isEmpty() || name.startsWith(maker, ignoreCase = true)) name else "$maker $name"
        }

        fun brassName(id: String): String {
            val r = bk.byId("brass", id) ?: return EpNames.brass(id)
            return makerName(r.optSN("manufacturerId")).ifEmpty { r.optSN("name") ?: EpNames.brass(id) }
        }

        fun epBulletName(info: BulletInfo): String {
            val wt = info.weightGr?.let { round(it, 1) }?.let { if (it == Math.rint(it)) it.toInt().toString() else it.toString() }
            val name = if (wt == null || Regex("\\bgr\\b", RegexOption.IGNORE_CASE).containsMatchIn(info.name)) info.name else "$wt gr ${info.name}"
            return if (info.maker.isEmpty() || name.startsWith(info.maker, ignoreCase = true)) name else "${info.maker} $name"
        }

        fun mark(name: String) = if (name.endsWith(REMOVED)) name else name + REMOVED
        fun unmark(name: String) = name.removeSuffix(REMOVED)

        fun round(v: Double, places: Int): Double { val f = Math.pow(10.0, places.toDouble()); return Math.round(v * f) / f }

        fun firearmOf(l: JSONObject): Pair<String?, Boolean> {
            val lid = l.getString("id")
            val cid = l.id("cartridgeId")
            val used = sessionsByLoad[lid].orEmpty().mapNotNull { it.optSN("firearmId") }.filter { it in liveFirearmIds }.distinct()
            if (used.size == 1) return used[0] to false
            val sameCart = cid?.let { firearmsByCartridge[it] }.orEmpty()
            val pick = sameCart.firstOrNull { it in used } ?: sameCart.firstOrNull()
            return pick to (pick != null && (sameCart.size > 1 || used.size > 1))
        }

        fun firearmTitle(f: JSONObject): String =
            f.optSN("nickname")?.trim()?.takeIf { it.isNotEmpty() } ?: f.id("cartridgeId")?.let { cartridgeName(it) } ?: "Empirical firearm"

        fun rifleUp(ex: Rifle, f: JSONObject): Rifle {
            val cid = f.id("cartridgeId")
            val twist = f.optDN("twistRateMm")?.div(Ep.MM) ?: cid?.let { cartridge(it)?.optDN("twistRateMm") }?.div(Ep.MM)
            val dir = if (f.optSN("twistDirection")?.lowercase() == "left") TwistDirection.LEFT else TwistDirection.RIGHT
            return ex.copy(
                name = firearmTitle(f),
                twistIn = twist?.let { Ep.round3(it) } ?: ex.twistIn,
                twistDirection = if (f.has("twistDirection")) dir else ex.twistDirection,
                sightHeightIn = f.optDN("sightOverBoreMm")?.div(Ep.MM)?.let { Ep.round3(it) } ?: ex.sightHeightIn,
                barrelLengthIn = f.optDN("barrelLengthMm")?.div(Ep.MM)?.let { Ep.round3(it) } ?: ex.barrelLengthIn,
                cartridge = cid?.let { cartridgeName(it) } ?: ex.cartridge,
                externalIds = if (cid != null) ex.externalIds + (Ep.KEY_CARTRIDGE to cid) else ex.externalIds,
            )
        }

        fun loadUp(ex: Load, l: JSONObject, name: String, bulletId: String?, mv: Pair<Double, String>?, rifleId: String): Load = ex.copy(
            name = name,
            bulletId = bulletId ?: ex.bulletId,
            muzzleVelocityFps = mv?.first ?: ex.muzzleVelocityFps,
            muzzleVelocitySource = if (mv != null) mv.second else ex.muzzleVelocitySource,
            notes = l.optSN("notes") ?: ex.notes,
            rifleId = rifleId,
            recipe = recipeOf(l),
            externalIds = ex.externalIds - RECIPE_KEYS + epIdsOf(l),
        )

        fun syncFirearms(): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            val wanted: Set<String>? = choice?.let { c ->
                c.firearmIds + epLoads.filter { c.targetRifleId == null && it.getString("id") in c.loadIds && loads.none { d -> d.externalRef == PREFIX + it.getString("id") } }
                    .mapNotNull { firearmOf(it).first }
            }
            for (f in epFirearms) {
                val fid = f.getString("id")
                val ref = PREFIX + fid
                val i = rifles.indexOfFirst { it.externalRef == ref }
                if (wanted != null && fid !in wanted) { if (i >= 0) map[fid] = rifles[i].id; continue }
                val cid = f.id("cartridgeId")
                val name = firearmTitle(f)
                val twist = f.optDN("twistRateMm")?.div(Ep.MM)
                    ?: cid?.let { cartridge(it)?.optDN("twistRateMm") }?.div(Ep.MM)
                val dir = if (f.optSN("twistDirection")?.lowercase() == "left") TwistDirection.LEFT else TwistDirection.RIGHT
                val sob = f.optDN("sightOverBoreMm")?.div(Ep.MM)
                val barrel = f.optDN("barrelLengthMm")?.div(Ep.MM)
                if (i < 0) {
                    val r = Rifle(
                        id = freshId("empirical:firearm:$fid"), name = name,
                        twistIn = twist?.let { Ep.round3(it) } ?: 8.0, twistDirection = dir,
                        sightHeightIn = sob?.let { Ep.round3(it) } ?: 1.5,
                        barrelLengthIn = barrel?.let { Ep.round3(it) },
                        elevationCorrection = f.optDN("elevationTrackingFactor") ?: 1.0,
                        externalRef = ref,
                        cartridge = cid?.let { cartridgeName(it) } ?: "",
                        externalIds = cid?.let { mapOf(Ep.KEY_CARTRIDGE to it) } ?: emptyMap(),
                    )
                    rifles += r; added++; riflesAdded++
                    if (twist == null) msgs += "Firearm '$name': Empirical has no twist rate; set to 1:8\" — check it."
                    if (sob == null) msgs += "Firearm '$name': Empirical has no sight height; set to 1.5\" — check it."
                    map[fid] = r.id
                } else {
                    val ex = rifles[i]
                    val up = rifleUp(ex, f)
                    if (up != ex) { rifles[i] = up; updated++; riflesUpdated++ }
                    map[fid] = ex.id
                }
            }
            if (choice == null) for ((i, r) in rifles.withIndex()) {
                val ref = r.externalRef ?: continue
                if (!ref.startsWith(PREFIX) || ref == UNASSIGNED_REF || ref.removePrefix(PREFIX) in map) continue
                if (!r.name.endsWith(REMOVED)) {
                    rifles[i] = r.copy(name = mark(r.name)); updated++; riflesUpdated++
                    msgs += "Firearm '${r.name}' is no longer in Empirical; kept here and marked \"removed in Empirical\"."
                }
            }
            return map
        }

        fun unassignedRifle(): String {
            choice?.defaultRifleId?.takeIf { id -> rifles.any { it.id == id } }?.let { return it }
            rifles.firstOrNull { it.externalRef == UNASSIGNED_REF }?.let { return it.id }
            val r = Rifle(id = freshId(UNASSIGNED_REF), name = UNASSIGNED_NAME, externalRef = UNASSIGNED_REF,
                notes = "Ammo from Empirical Precision that is not tied to one firearm there. Move it to the right firearm.")
            rifles += r; added++; riflesAdded++
            return r.id
        }

        fun chronoMv(loadId: String): Pair<Double, String>? {
            val candidates = sessionsByLoad[loadId].orEmpty()
                .mapNotNull { s -> s.optSN("chronoSessionId")?.let { chronoStrings[it] } }
                .sortedByDescending { it.optDouble("importedAt", 0.0) }
            for (c in candidates) {
                val id = c.getString("id")
                val v = shotsByString[id].orEmpty().filter { !it.optBoolean("velocitySetAside", false) }
                    .mapNotNull { s -> s.opt("velocity").let { if (it is Number) it.toDouble() else null } }
                val label = c.optSN("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" } ?: "(unnamed)"
                if (v.isNotEmpty()) return v.average() to "$label (${v.size} shots)"
            }
            return null
        }

        fun velocityOf(l: JSONObject): Pair<Double, String>? {
            chronoMv(l.getString("id"))?.let { return round(it.first, 1) to MV_CHRONO + it.second }
            l.optDN("velocityMps")?.takeIf { it > 0 }?.let { return round(it * Ep.FPS_PER_MPS, 1) to MV_PREDICTED }
            return null
        }

        fun recipeOf(l: JSONObject): Recipe? {
            val r = Recipe(
                cartridge = l.id("cartridgeId")?.let { cartridgeName(it) } ?: "",
                bullet = l.id("bulletId")?.let { bulletInfo(it) }?.let { epBulletName(it) } ?: "",
                powder = l.id("powderId")?.let { powderOrPrimerName("powders", it) } ?: "",
                chargeGr = l.optDN("chargeWeightGrams")?.takeIf { it > 0 }?.let { round(it / Ep.GRAMS_PER_GRAIN, 3) },
                primer = l.id("primerId")?.let { powderOrPrimerName("primers", it) } ?: "",
                brass = l.id("brassId")?.let { brassName(it) } ?: "",
                firings = l.optDN("firings")?.toInt(),
                coalIn = l.optDN("coalMm")?.takeIf { it > 0 }?.let { round(it / Ep.MM, 4) },
                cbtoIn = l.optDN("cbtoMm")?.takeIf { it > 0 }?.let { round(it / Ep.MM, 4) },
                cbtoComparator = l.id("cbtoComp") ?: "",
                shoulderBumpIn = l.optDN("shoulderMm")?.let { round(it / Ep.MM, 4) },
                shoulderComparator = l.id("shoulderComp") ?: "",
            )
            return if (r.isEmpty) null else r
        }

        fun epIdsOf(l: JSONObject): Map<String, String> = listOfNotNull(
            l.id("cartridgeId")?.let { Ep.KEY_CARTRIDGE to it }, l.id("bulletId")?.let { Ep.KEY_BULLET to it },
            l.id("powderId")?.let { Ep.KEY_POWDER to it }, l.id("primerId")?.let { Ep.KEY_PRIMER to it },
            l.id("brassId")?.let { Ep.KEY_BRASS to it },
        ).toMap()

        @Suppress("UNUSED_PARAMETER")
        fun loadTitle(l: JSONObject, bulletName: String?): String =
            l.optSN("name")?.trim()?.takeIf { it.isNotEmpty() } ?: generatedName(recipeOf(l))

        fun bulletById(id: String): Bullet? = bullets.firstOrNull { it.id == id } ?: library.firstOrNull { it.id == id } ?: resolveBullet(id)

        fun recipeSuccessors(): Map<String, String> {
            val liveIds = epLoads.mapTo(HashSet()) { it.getString("id") }
            val orphans = loads.filter { d ->
                val r = d.externalRef ?: return@filter false
                r.startsWith(PREFIX) && r != UNASSIGNED_REF && r.removePrefix(PREFIX) !in liveIds
            }
            if (orphans.isEmpty()) return emptyMap()
            val pairs = mutableListOf<Pair<String, String>>()
            for (l in epLoads) {
                val lid = l.getString("id")
                if (loads.any { it.externalRef == PREFIX + lid }) continue
                val epBullet = l.id("bulletId") ?: continue
                val label = l.optSN("name")?.trim()?.takeIf { it.isNotEmpty() }
                val updatedAt = l.optDN("updatedAt")?.toLong()
                val cands = orphans.filter { d ->
                    val sameBullet = d.externalIds[Ep.KEY_BULLET]?.let { it == epBullet } ?: (peekBullet(epBullet) == d.bulletId)
                    if (!sameBullet) return@filter false
                    val t = bk.tombstone(d.externalRef!!.removePrefix(PREFIX))
                    val sameSave = t != null && t.table in setOf(null, "loads") && t.deletedAt != null && updatedAt != null &&
                        t.deletedAt - updatedAt in 0..RECIPE_EDIT_WINDOW_MS
                    sameSave || (label != null && unmark(d.name) == label)
                }
                cands.singleOrNull()?.let { pairs += lid to it.id }
            }
            return pairs.groupBy { it.second }.values.filter { it.size == 1 }.associate { it[0] }
        }

        fun syncLoads(firearmRifle: Map<String, String>) {
            val unassignedId = rifles.firstOrNull { it.externalRef == UNASSIGNED_REF }?.id
            val live = HashSet<String>()
            var unassignedCount = 0
            val successors = recipeSuccessors()
            for (l in epLoads) {
                val lid = l.getString("id")
                val ref = PREFIX + lid
                live += lid
                if (choice != null && lid !in choice.loadIds) continue
                val target = choice?.targetRifleId?.takeIf { id -> rifles.any { it.id == id } }
                var i = loads.indexOfFirst { it.externalRef == ref && (target == null || it.rifleId == target) }
                var relinked = false
                if (i < 0 && target == null) successors[lid]?.let { prevId ->
                    i = loads.indexOfFirst { it.id == prevId }
                    loads[i] = loads[i].copy(externalRef = ref)
                    relinked = true
                    msgs += "Ammo '${unmark(loads[i].name)}': its recipe changed in Empirical (new id), so it was re-linked and keeps its zero and other settings here."
                }
                val epBullet = l.id("bulletId")
                val label = l.optSN("name")?.trim()?.takeIf { it.isNotEmpty() }
                val ex = loads.getOrNull(i)
                val picked = epBullet?.let { choice?.bullets?.get(it) }
                val keep = ex != null && picked == null && epBullet != null && ex.externalIds[Ep.KEY_BULLET] == epBullet &&
                    ex.bulletId != standInId(epBullet) && bulletById(ex.bulletId) != null
                val bulletId = if (keep) ex!!.bulletId else epBullet?.let { resolveEpBullet(it) }
                if (bulletId == null && ex == null) {
                    skipped++
                    msgs += "Empirical ammo ${label?.let { "'$it'" } ?: "(unnamed)"} skipped: its bullet could not be worked out from the backup."
                    continue
                }
                val name = loadTitle(l, bulletId?.let { bulletById(it) }?.name)
                val mv = velocityOf(l)
                val notes = l.optSN("notes")
                val recipe = recipeOf(l)
                val epIds = epIdsOf(l)

                fun derivedRifle(): String {
                    target?.let { return it }
                    val (pick, guessed) = firearmOf(l)
                    if (guessed) choice?.ambiguousRifleId?.takeIf { id -> rifles.any { it.id == id } }?.let { return it }
                    val rid = pick?.let { firearmRifle[it] }
                    if (rid != null) {
                        if (guessed) msgs += "Ammo '$name' fits several Empirical firearms; put on '${rifles.first { it.id == rid }.name}'."
                        return rid
                    }
                    if (choice?.defaultRifleId == null) unassignedCount++
                    return unassignedRifle()
                }

                if (ex == null) {
                    val ld = Load(
                        id = freshId("empirical:load:$lid"), rifleId = derivedRifle(), name = name,
                        bulletId = bulletId!!, muzzleVelocityFps = mv?.first ?: 0.0, notes = notes ?: "", externalRef = ref,
                        externalIds = epIds, recipe = recipe,
                        zeroConfirmed = false,
                        muzzleVelocitySource = mv?.second ?: "",
                    )
                    loads += ld; added++; loadsAdded++
                    when {
                        mv == null -> msgs += "Ammo '$name': Empirical has no muzzle velocity for it; enter one before using it."
                        mv.second.startsWith(MV_CHRONO) -> msgs += "Ammo '$name': muzzle velocity ${mv.first} fps from chronograph string ${mv.second.removePrefix(MV_CHRONO)}."
                        else -> msgs += "Ammo '$name': muzzle velocity ${mv.first} fps is Empirical's prediction, not a measurement; confirm it."
                    }
                } else {
                    val keepRifle = rifles.any { it.id == ex.rifleId } && ex.rifleId != unassignedId
                    val up = loadUp(ex, l, name, bulletId, mv, if (keepRifle) ex.rifleId else derivedRifle())
                    if (up != ex || relinked) { loads[i] = up; updated++; loadsUpdated++ }
                    if (target == null) for (j in loads.indices) {
                        val o = loads[j]
                        if (j == i || o.externalRef != ref) continue
                        val ownBullet = o.bulletId.takeIf { epBullet != null && o.externalIds[Ep.KEY_BULLET] == epBullet && bulletById(it) != null }
                        val up2 = loadUp(o, l, name, ownBullet ?: bulletId, mv, o.rifleId)
                        if (up2 != o) { loads[j] = up2; updated++; loadsUpdated++ }
                    }
                }
            }
            if (unassignedCount > 0) msgs += "$unassignedCount ammo profile(s) could not be tied to one firearm and are under '$UNASSIGNED_NAME'."
            if (choice == null) for ((i, ld) in loads.withIndex()) {
                val ref = ld.externalRef ?: continue
                if (!ref.startsWith(PREFIX) || ref.removePrefix(PREFIX) in live) continue
                if (!ld.name.endsWith(REMOVED)) {
                    loads[i] = ld.copy(name = mark(ld.name)); updated++; loadsUpdated++
                    msgs += "Ammo '${ld.name}' is no longer in Empirical; kept here and marked \"removed in Empirical\"."
                }
            }
        }

        val bulletCache = HashMap<String, String?>()

        fun standInId(epId: String) = stableId("empirical:bullet:$epId")

        fun resolveEpBullet(epId: String): String? = bulletCache.getOrPut(epId) { doResolve(epId) }

        fun peekBullet(epId: String): String? {
            bulletCache[epId]?.let { return it }
            val info = bulletInfo(epId) ?: return null
            return known(epId, info) ?: standInId(epId).takeIf { sid -> bullets.any { it.id == sid } }
        }

        fun known(epId: String, info: BulletInfo): String? {
            loads.firstOrNull { it.externalIds[Ep.KEY_BULLET] == epId && it.bulletId != standInId(epId) && bulletById(it.bulletId) != null }?.let { return it.bulletId }
            matchLibrary(info)?.let { return it.id }
            matchOwn(info)?.let { return it.id }
            (bullets.filter { it.id != standInId(epId) } + library).firstOrNull { it.externalIds[Ep.KEY] == epId }?.let { return it.id }
            info.derivedFrom?.let { bulletInfo(it) }?.let { matchLibrary(it) }?.let { return it.id }
            info.derivedFrom?.let { orig -> library.firstOrNull { it.externalIds[Ep.KEY] == orig } }?.let { return it.id }
            return null
        }

        fun doResolve(epId: String): String? {
            val picked = choice?.bullets?.get(epId)
            if (picked != null && picked != CREATE && bulletById(picked) != null) return picked
            val info = bulletInfo(epId) ?: run {
                msgs += "A bullet that some Empirical ammo uses is not in the backup file."
                return null
            }
            if (picked != CREATE) known(epId, info)?.let { return it }
            val sid = standInId(epId)
            if (bullets.any { it.id == sid }) return sid
            val dia = info.diaIn
            val wt = info.weightGr
            if (dia == null || wt == null) {
                msgs += "Empirical bullet '${epBulletName(info)}' has no diameter or weight in the backup, so it cannot be used."
                return null
            }
            val model = when {
                info.curve.isNotEmpty() && info.shape in setOf("measured", "edited") -> DragModel.CUSTOM
                info.g7 != null -> DragModel.G7
                info.g1 != null -> DragModel.G1
                else -> DragModel.G7
            }
            val rows = when {
                model == DragModel.G1 -> listOf(BcPoint(Ep.round5(info.g1!!), 0.0))
                info.g7 != null -> listOf(BcPoint(Ep.round5(info.g7), 0.0))
                else -> emptyList()
            }
            val wTxt = if (wt == Math.rint(wt)) wt.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, wt)
            val b = Bullet(
                id = sid.also { usedIds += it }, maker = info.maker,
                name = if (Regex("\\bgr\\b", RegexOption.IGNORE_CASE).containsMatchIn(info.name)) info.name else "$wTxt gr ${info.name}",
                diameterIn = Ep.round5(dia), weightGr = Ep.round3(wt),
                lengthIn = info.lengthIn?.let { Ep.round3(it) }, noseLengthIn = info.ogiveIn?.let { Ep.round3(it) },
                dragModel = model, bcPoints = rows, dragCurve = info.curve,
                caliber = info.caliber, category = EP_BULLET_CATEGORY, source = "user", externalIds = mapOf(Ep.KEY to epId),
                notes = if (rows.isEmpty() && info.curve.isEmpty())
                    "Placeholder for Empirical Precision's ${epBulletName(info)}: the backup file has no BC for it and no bullet here matched. Pick a library bullet for the ammo that uses it, or enter its BC."
                else "From Empirical Precision (${epBulletName(info)}): its single BC, because no bullet here matched. Swap it for a library bullet when you can.",
            )
            bullets += b; added++
            if (rows.isEmpty() && info.curve.isEmpty()) placeholdersMade++ else bulletsMade++
            msgs += if (rows.isEmpty() && info.curve.isEmpty())
                "${epBulletName(info)}: no bullet here matches it and the backup file has no BC for it, so a placeholder bullet '${b.name}' was created with no BC. Open the ammo that uses it and pick a library bullet, or enter the BC."
            else "Bullet '${b.name}' created from Empirical's single BC (no match among your bullets or the bullet library). Swap it when you can."
            return b.id
        }

        fun bulletInfo(epId: String): BulletInfo? = infoCache.getOrPut(epId) {
            val rec = bk.byId("bullets", epId) ?: master?.bullets?.get(epId)
            if (rec != null) infoFrom(epId, rec) else if (epId.startsWith("BUL_")) placeholder(epId) else null
        }

        fun infoFrom(epId: String, r: JSONObject): BulletInfo {
            val ph = r.optJSONObject("physis")
            val dia = r.optSN("diameterId")?.let { diameter(it) }
            val wt = ph?.optDN("weightGrams")?.div(Ep.GRAMS_PER_GRAIN)
            val bal = r.optJSONObject("ballistics")
            val drag = r.optJSONObject("drag")
            val g7 = bal?.optDN("g7BC")?.takeIf { it > 0 } ?: drag?.optDN("g7BC")?.takeIf { it > 0 }
            val g1 = bal?.optDN("g1BC")?.takeIf { it > 0 } ?: drag?.optDN("g1BC")?.takeIf { it > 0 }
            var curve = emptyList<CdPoint>()
            val f7 = drag?.optJSONArray("f7")
            if (drag != null && f7 != null && g7 != null && dia != null && wt != null) {
                val m = drag.optJSONArray("mach")
                val grid = if (m != null && m.length() == f7.length()) DoubleArray(m.length()) { m.getDouble(it) }
                    else master?.grid?.takeIf { it.size == f7.length() } ?: Ep.MACH_GRID.takeIf { it.size == f7.length() }
                if (grid != null) curve = Ep.fromEpDrag(grid, DoubleArray(f7.length()) { f7.getDouble(it) }, drag.optDN("g7BC") ?: g7, Ep.sd(wt, dia.first))
            }
            return BulletInfo(
                epId = epId, maker = makerName(r.optSN("manufacturerId")),
                name = (r.optSN("name") ?: epId).removeSuffix(" (CUST)").trim(),
                diaIn = dia?.first, caliber = dia?.second ?: "", weightGr = wt,
                lengthIn = ph?.optDN("overallLengthMm")?.div(Ep.MM), ogiveIn = ph?.optDN("ogiveLengthMm")?.div(Ep.MM),
                g1 = g1, g7 = g7, curve = curve, shape = drag?.optSN("shape"),
                derivedFrom = r.optSN("derivedFrom"), placeholder = false,
            )
        }

        fun placeholder(epId: String): BulletInfo {
            val p = epId.removePrefix("BUL_").split('_')
            val ci = p.indexOfFirst { it.isNotEmpty() && it.all(Char::isDigit) }
            val makerTokens = if (ci > 0) p.subList(0, ci) else p.take(1)
            val manKey = "MAN_" + makerTokens.joinToString("_") + "_"
            val maker = Ep.MANUFACTURERS.entries.firstOrNull { it.key.startsWith(manKey) }?.value
                ?: makerTokens.joinToString(" ") { t -> t.lowercase().replaceFirstChar { it.uppercase() } }
            val cal = if (ci >= 0) p[ci] else null
            val dia = cal?.let { c -> if (c.length == 3) ("0.$c").toDouble() else null }
            val wt = if (ci >= 0 && ci + 1 < p.size) p[ci + 1].toDoubleOrNull() else null
            val nameTokens = if (ci >= 0 && ci + 2 < p.size - 1) p.subList(ci + 2, p.size - 1) else emptyList()
            return BulletInfo(
                epId = epId, maker = maker, name = nameTokens.joinToString(" ").ifEmpty { "EP bullet $epId" },
                diaIn = dia, caliber = cal?.let { ".$it" } ?: "", weightGr = wt, lengthIn = null, ogiveIn = null,
                g1 = null, g7 = null, curve = emptyList(), shape = null, derivedFrom = null, placeholder = true,
            )
        }

        private fun sameSize(b: Bullet, dia: Double, wt: Double) = abs(b.weightGr - wt) <= 0.5 && abs(b.diameterIn - dia) <= 0.0015

        private fun makerOk(b: Bullet, info: BulletInfo): Boolean {
            val mk = Ep.normMaker(info.maker); val bm = Ep.normMaker(b.maker)
            return mk.isNotEmpty() && (bm == mk || (minOf(bm.length, mk.length) >= 4 && (bm.startsWith(mk) || mk.startsWith(bm))))
        }

        private data class Scored(val b: Bullet, val score: Double, val jac: Double)

        private fun score(pool: List<Bullet>, info: BulletInfo): List<Scored> {
            val makerWords = tokens(info.maker)
            val ep = tokens(info.name) - makerWords
            return pool.map { b ->
                val lt = tokens(b.name) - makerWords
                val inter = ep.intersect(lt).size.toDouble()
                Scored(b, if (ep.isEmpty()) 0.0 else inter / ep.size, if ((ep + lt).isEmpty()) 0.0 else inter / (ep + lt).size)
            }.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.jac })
        }

        private fun confident(pool: List<Bullet>, info: BulletInfo): Bullet? {
            val dia = info.diaIn ?: return null
            val wt = info.weightGr ?: return null
            val cands = pool.filter { makerOk(it, info) && sameSize(it, dia, wt) }
            if (cands.isEmpty()) return null
            if ((tokens(info.name) - tokens(info.maker)).isEmpty()) return cands.singleOrNull()
            val scored = score(cands, info)
            val best = scored[0]
            val second = scored.getOrNull(1)
            val strictlyBest = second == null || best.score > second.score || (best.score == second.score && best.jac > second.jac)
            return if (best.score >= 0.5 && strictlyBest) best.b else null
        }

        fun matchLibrary(info: BulletInfo): Bullet? = confident(library.filter { it.collection.isEmpty() }, info)

        fun matchOwn(info: BulletInfo): Bullet? = confident(bullets.filter { it.category != EP_BULLET_CATEGORY && it.id != standInId(info.epId) }, info)

        fun candidates(info: BulletInfo): List<Bullet> {
            val dia = info.diaIn ?: return emptyList()
            val wt = info.weightGr ?: return emptyList()
            val pool = (library.filter { it.collection.isEmpty() } + bullets.filter { it.category != EP_BULLET_CATEGORY } + library.filter { it.collection.isNotEmpty() })
                .filter { sameSize(it, dia, wt) }.distinctBy { it.id }
            val order = pool.withIndex().associate { it.value.id to it.index }
            return score(pool, info).sortedWith(
                compareByDescending<Scored> { makerOk(it.b, info) }.thenByDescending { it.score }.thenByDescending { it.jac }.thenBy { order[it.b.id] }
            ).map { it.b }
        }

        fun bulletMatch(epId: String, ex: Load?): BulletMatch? {
            val info = bulletInfo(epId) ?: return null
            val kept = ex?.takeIf { it.externalIds[Ep.KEY_BULLET] == epId && it.bulletId != standInId(epId) }?.let { bulletById(it.bulletId) }
            val matched = kept ?: known(epId, info)?.let { bulletById(it) }
            val cands = candidateCache.getOrPut(epId) { candidates(info) }
            return BulletMatch(
                epBulletId = epId, epName = epBulletName(info), matched = matched,
                candidates = (listOfNotNull(matched) + cands).distinctBy { it.id },
                epBc = info.g7?.let { "G7 " + BulletCsvFormat.num(Ep.round3(it)) } ?: info.g1?.let { "G1 " + BulletCsvFormat.num(Ep.round3(it)) } ?: "",
                canCreate = info.diaIn != null && info.weightGr != null,
            )
        }

        fun preview(): Preview {
            val successors = recipeSuccessors()
            val fa = epFirearms.map { f ->
                val fid = f.getString("id")
                val ex = rifles.firstOrNull { it.externalRef == PREFIX + fid }
                val cart = f.id("cartridgeId")?.let { cartridgeName(it) } ?: ""
                val twist = f.optDN("twistRateMm")?.let { "1:" + BulletCsvFormat.num(round(it / Ep.MM, 2)) }
                val barrel = f.optDN("barrelLengthMm")?.let { BulletCsvFormat.num(round(it / Ep.MM, 1)) + " in" }
                val parts = listOfNotNull(cart.ifEmpty { null }, twist?.let { "$it twist" }, barrel?.let { "$it barrel" })
                FirearmItem(
                    fid, firearmTitle(f), parts.joinToString(" · "),
                    status = when { ex == null -> Status.NEW; rifleUp(ex, f) == ex -> Status.SAME; else -> Status.UPDATE },
                    cartridge = cart, localRifleId = ex?.id,
                    details = listOfNotNull(
                        cart.ifEmpty { null }?.let { "Cartridge" to it },
                        twist?.let { "Barrel twist" to it + " in, " + (if (f.optSN("twistDirection")?.lowercase() == "left") "left" else "right") + " hand" },
                        barrel?.let { "Barrel length" to it },
                        f.optDN("sightOverBoreMm")?.let { "Sight height" to BulletCsvFormat.num(round(it / Ep.MM, 3)) + " in" },
                        f.optDN("elevationTrackingFactor")?.let { "Elevation correction" to BulletCsvFormat.num(it) },
                    ),
                )
            }
            val rifleOfFirearm = rifles.filter { it.externalRef?.startsWith(PREFIX) == true }.associate { it.externalRef!!.removePrefix(PREFIX) to it.id }
            val unassignedId = rifles.firstOrNull { it.externalRef == UNASSIGNED_REF }?.id
            val titles = epFirearms.associate { it.getString("id") to firearmTitle(it) }
            val ld = epLoads.map { l ->
                val lid = l.getString("id")
                val target = choice?.targetRifleId?.let { id -> rifles.firstOrNull { it.id == id } }
                val ex = if (target != null) loads.firstOrNull { it.externalRef == PREFIX + lid && it.rifleId == target.id }
                    else loads.firstOrNull { it.externalRef == PREFIX + lid } ?: successors[lid]?.let { prev -> loads.firstOrNull { it.id == prev } }
                val bm = l.id("bulletId")?.let { bulletMatch(it, ex) }
                val r = recipeOf(l)
                val mv = velocityOf(l)
                val (fid0, guessed) = firearmOf(l)
                val fid = if (target != null) null else fid0
                val exRifle = target ?: ex?.let { e -> rifles.firstOrNull { it.id == e.rifleId } }?.takeIf { it.externalRef != UNASSIGNED_REF }
                val detail = listOfNotNull(
                    r?.cartridge?.takeIf { it.isNotEmpty() },
                    r?.powder?.takeIf { it.isNotEmpty() }?.let { p -> r.chargeGr?.let { "${BulletCsvFormat.num(round(it, 1))} gr $p" } ?: p },
                    r?.coalIn?.let { "COAL " + BulletCsvFormat.num(round(it, 3)) + " in" },
                ).joinToString(" · ")
                val linked = ex != null && ex.externalRef == PREFIX + lid
                val same = linked && run {
                    val e = ex!!
                    val epBullet = l.id("bulletId")
                    val keep = epBullet != null && e.externalIds[Ep.KEY_BULLET] == epBullet && e.bulletId != standInId(epBullet) && bulletById(e.bulletId) != null
                    val bulletId = if (keep) e.bulletId else epBullet?.let { peekBullet(it) }
                    val keepRifle = rifles.any { it.id == e.rifleId } && e.rifleId != unassignedId
                    val rifleId = if (keepRifle) e.rifleId else fid?.let { rifleOfFirearm[it] } ?: unassignedId.takeIf { fid == null }
                    (bulletId != null || epBullet == null) && rifleId != null &&
                        loadUp(e, l, loadTitle(l, bulletId?.let { bulletById(it) }?.name), bulletId, mv, rifleId) == e
                }
                LoadItem(
                    epId = lid, title = loadTitle(l, bm?.matched?.name ?: bm?.epName), detail = detail,
                    status = when { ex == null -> Status.NEW; same -> Status.SAME; else -> Status.UPDATE },
                    firearmEpId = if (exRifle != null) null else fid,
                    firearmName = exRifle?.name ?: fid?.let { titles[it] } ?: "",
                    bullet = bm, mvFps = mv?.first, mvSource = mv?.second ?: "",
                    cartridge = r?.cartridge ?: "", recipe = r, notes = l.optSN("notes") ?: "",
                    localRifleId = if (target != null) ex?.rifleId else exRifle?.id ?: fid?.let { rifleOfFirearm[it] },
                    firearmGuessed = target == null && ex == null && guessed,
                    epFirearmId = fid0.takeIf { !guessed },
                )
            }
            return Preview(fa, ld, bk.exportedAt)
        }

        fun catalog(): EpCatalog {
            fun ids(key: String) = epLoads.mapNotNull { it.id(key) }.distinct()
            fun own(table: String) = bk.live(table).map { it.getString("id") }
            fun entries(idList: List<String>, name: (String) -> String?) =
                idList.distinct().mapNotNull { id -> name(id)?.takeIf { it.isNotBlank() }?.let { EpCatalog.Entry(it, id) } }.sortedBy { it.name.lowercase() }
            return EpCatalog(
                cartridges = entries(ids("cartridgeId") + epFirearms.mapNotNull { it.id("cartridgeId") } + own("cartridges")) { cartridgeName(it) },
                bullets = entries(ids("bulletId") + own("bullets")) { id -> bulletInfo(id)?.let { epBulletName(it) } },
                powders = entries(ids("powderId") + own("powders")) { powderOrPrimerName("powders", it) },
                primers = entries(ids("primerId") + own("primers")) { powderOrPrimerName("primers", it) },
                brass = entries(ids("brassId") + own("brass")) { brassName(it) },
            )
        }
    }

    fun generatedName(r: Recipe?): String {
        val charge = r?.chargeGr?.let { "%.1f gr".format(java.util.Locale.ROOT, it) }
        val bullet = r?.bullet?.takeIf { it.isNotBlank() }
        return bullet?.let { if (charge != null) "$it · $charge" else it } ?: "Empirical ammo"
    }

    private val RECIPE_KEYS = setOf(Ep.KEY_CARTRIDGE, Ep.KEY_BULLET, Ep.KEY_POWDER, Ep.KEY_PRIMER, Ep.KEY_BRASS)

    data class BulletInfo(
        val epId: String, val maker: String, val name: String,
        val diaIn: Double?, val caliber: String, val weightGr: Double?,
        val lengthIn: Double?, val ogiveIn: Double?,
        val g1: Double?, val g7: Double?, val curve: List<CdPoint>, val shape: String?,
        val derivedFrom: String?, val placeholder: Boolean,
    )

    private val STOP = setOf("gr", "grain", "grains", "cal", "caliber", "mm", "bullet", "bullets", "cust", "the", "and")
    private val SYNONYMS = mapOf(
        "eldm" to listOf("eld", "match"), "eldx" to listOf("eld", "x"), "eldvt" to listOf("eld", "vt"),
        "m" to listOf("match"), "matchking" to listOf("smk"), "tippedmatchking" to listOf("tmk"),
        "hpbt" to listOf("bthp"), "amax" to listOf("a", "max"), "vmax" to listOf("v", "max"),
        "atip" to listOf("a", "tip"),
    )

    internal fun tokens(s: String): Set<String> {
        val raw = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < raw.size) {
            val t = raw[i]
            val joined = if (i + 1 < raw.size) t + raw[i + 1] else null
            when {
                joined != null && joined in SYNONYMS && t !in SYNONYMS -> { out += SYNONYMS.getValue(joined); i += 2; continue }
                t in SYNONYMS -> out += SYNONYMS.getValue(t)
                t.all(Char::isDigit) || t in STOP -> {}
                else -> out += t
            }
            i++
        }
        return out
    }
}
