package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.engine.DragTables
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.TwistDirection
import com.longrange.dragcurve.store.JsonCodec
import com.longrange.dragcurve.store.objects
import com.longrange.dragcurve.store.optDN
import com.longrange.dragcurve.store.optSN
import com.longrange.dragcurve.store.putOpt2
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object Ep {
    const val BACKUP_FORMAT = "epv5-user-data"
    const val KEY = "empirical"
    const val KEY_CARTRIDGE = "empirical.cartridge"
    const val KEY_BULLET = "empirical.bullet"
    const val KEY_POWDER = "empirical.powder"
    const val KEY_PRIMER = "empirical.primer"
    const val KEY_BRASS = "empirical.brass"
    const val BACKUP_VERSION = 5
    const val MM = 25.4
    const val GRAMS_PER_GRAIN = 0.06479891
    const val FPS_PER_MPS = 3.280839895
    const val G1_TO_G7 = 0.506015

    val MACH_GRID = doubleArrayOf(
        0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0, 1.05, 1.1, 1.2, 1.3,
        1.35, 1.4, 1.5, 1.65, 1.8, 2.0, 2.2, 2.4, 2.55, 2.7, 2.85, 3.0,
    )

    val LIBRARY_PREFIXES = listOf("MAN_", "DIA_", "CTG_", "BUL_", "PWD_", "PRI_", "PKT_", "BRS_")
    fun isLibraryId(id: String) = LIBRARY_PREFIXES.any { id.startsWith(it) }

    private val TABLE_PREFIX = mapOf(
        "manufacturers" to "MAN_", "diameters" to "DIA_", "bullets" to "BUL_", "powders" to "PWD_",
        "primers" to "PRI_", "primerPockets" to "PKT_", "brass" to "BRS_", "cartridges" to "CTG_",
    )
    fun isLibraryRow(table: String, id: String) =
        table == "grainTypes" || TABLE_PREFIX[table]?.let { id.startsWith(it) } == true

    data class Dia(val id: String, val imperialName: String, val metricName: String, val bulletDiameterMm: Double)

    val DIAMETERS = listOf(
        Dia("DIA_224_L5X8", ".224", "5.56", 5.6896),
        Dia("DIA_243_B7V4", ".243", "6mm", 6.1722),
        Dia("DIA_257_N2M1", ".257", "6.35", 6.5278),
        Dia("DIA_264_H8J4", ".264", "6.5mm", 6.7056),
        Dia("DIA_277_K9L0", ".277", "6.8mm", 7.0358),
        Dia("DIA_284_S3F6", ".284", "7mm", 7.2136),
        Dia("DIA_308_XBM7", ".308", "7.62", 7.8232),
        Dia("DIA_323_M2P1", ".323", "8.2mm", 8.2042),
        Dia("DIA_338_R7E3", ".338", "8.6mm", 8.5852),
        Dia("DIA_355_A4D1", ".355", "9mm", 9.017),
        Dia("DIA_358_P8K5", ".358", "9.1mm", 9.0932),
        Dia("DIA_375_W4T2", ".375", "9.5mm", 9.525),
        Dia("DIA_458_83DA", ".458", "11.63", 11.6332),
    )

    val MANUFACTURERS = linkedMapOf(
        "MAN_ACCURATE_A2C1" to "Accurate", "MAN_ADG_A4D7" to "ADG", "MAN_ALEXANDER_ARMS_A1A2" to "Alexander Arms",
        "MAN_ALLIANT_A5R2" to "Alliant", "MAN_ALPHA_A1L2" to "Alpha Munitions", "MAN_BARNES_B2N5" to "Barnes",
        "MAN_BERGER_B9K2" to "Berger", "MAN_BERTRAM_8F3B" to "Bertram", "MAN_BROWNING_5E25" to "Browning",
        "MAN_CCI_C2W7" to "CCI", "MAN_CEB_C3B4" to "Cutting Edge Bullets", "MAN_FEDERAL_F4G7" to "Federal",
        "MAN_FIOCCHI_F2C3" to "Fiocchi", "MAN_GA_G1A2" to "GA Munitions", "MAN_HODGDON_H7X2" to "Hodgdon",
        "MAN_IMI_I1M2" to "IMI", "MAN_IMR_I2M3" to "IMR", "MAN_JAG_J1A2" to "JAG",
        "MAN_LAKE_CITY_L1C2" to "Lake City", "MAN_LAPUA_L5X1" to "Lapua", "MAN_NORMA_N3R4" to "Norma",
        "MAN_NOSLER_N9K4" to "Nosler", "MAN_PETERSON_P3T2" to "Peterson", "MAN_PPU_9D64" to "PPU",
        "MAN_RAMSHOT_R2S3" to "Ramshot", "MAN_RELOADSWISS_0F09" to "Reload Swiss", "MAN_REMINGTON_R1M2" to "Remington",
        "MAN_RUAG_R8A9" to "RUAG", "MAN_RWS_173C" to "RWS", "MAN_SAKO_S2A3" to "Sako", "MAN_SBR_01C2" to "SBR",
        "MAN_SELLIER_BELLOT_S3B4" to "Sellier & Bellot", "MAN_SHOOTERS_WORLD_S5W6" to "Shooters World",
        "MAN_SIERRA_S6Y3" to "Sierra", "MAN_SPEER_S2P1" to "Speer", "MAN_STARLINE_S8L9" to "Starline",
        "MAN_SWIFT_SW5T" to "Swift Bullet Company", "MAN_VIHTAVUORI_V3H8" to "Vihtavuori",
        "MAN_WARNERTOOL_73AD" to "Warner Tool", "MAN_WEATHERBY_W2B3" to "Weatherby",
        "MAN_WHITERIVER_W8R2" to "White River Energetics", "MAN_WINCHESTER_W1C6" to "Winchester",
        "MAN_WOODLEIGH_2906" to "Woodleigh",
    )

    fun normMaker(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun diameterFor(diaIn: Double): Dia? =
        DIAMETERS.minByOrNull { abs(it.bulletDiameterMm / MM - diaIn) }?.takeIf { abs(it.bulletDiameterMm / MM - diaIn) <= 0.0015 }

    fun manufacturerFor(maker: String): String? {
        val n = normMaker(maker)
        if (n.isEmpty()) return null
        return MANUFACTURERS.entries.firstOrNull { normMaker(it.value) == n }?.key
            ?: MANUFACTURERS.entries.firstOrNull { val m = normMaker(it.value); m.length >= 4 && (n.startsWith(m) || m.startsWith(n)) }?.key
    }

    fun sd(weightGr: Double, diaIn: Double) = weightGr / 7000.0 / (diaIn * diaIn)

    fun g7Ref(mach: Double) = DragTables.lookup(DragTables.G7, mach)

    fun interp(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        val r = xs.size - 1
        if (x <= xs[0]) return ys[0]
        if (x >= xs[r]) return ys[r]
        var i = 1
        while (xs[i] < x) i++
        val a = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        return ys[i - 1] + (ys[i] - ys[i - 1]) * a
    }

    data class EpDrag(val f7: DoubleArray, val g7BC: Double, val g1BC: Double)

    fun toEpDrag(curve: List<CdPoint>, sdLbIn2: Double): EpDrag {
        val pts = curve.sortedBy { it.mach }
        val xs = pts.map { it.mach }.toDoubleArray()
        val ys = pts.map { it.cd }.toDoubleArray()
        val cd = MACH_GRID.map { interp(xs, ys, it) }
        val i = DoubleArray(MACH_GRID.size) { cd[it] / g7Ref(MACH_GRID[it]) }
        var a = 0.0; var o = 0.0
        val steps = (1.35 / 0.05).roundToInt() + 1
        for (t in 0 until steps) {
            val m = 1.35 + t * 0.05
            val w = g7Ref(m) * m
            a += interp(MACH_GRID, i, m) * w
            o += w
        }
        val c = a / o
        val g7 = sdLbIn2 / c
        return EpDrag(DoubleArray(i.size) { i[it] / c }, g7, g7 / G1_TO_G7)
    }

    fun fromEpDrag(mach: DoubleArray, f7: DoubleArray, g7BC: Double, sdLbIn2: Double): List<CdPoint> =
        mach.indices.map { CdPoint(mach[it], round5(f7[it] * g7Ref(mach[it]) * sdLbIn2 / g7BC)) }

    fun representativeBc(rows: List<BcPoint>): Double? {
        if (rows.isEmpty()) return null
        val sorted = rows.sortedByDescending { it.minVelFps }
        var upper = Double.POSITIVE_INFINITY
        var sum = 0.0; var w = 0.0
        for (r in sorted) {
            val lo = maxOf(r.minVelFps, 1400.0); val hi = minOf(upper, 3000.0)
            if (hi > lo) { sum += r.bc * (hi - lo); w += hi - lo }
            upper = r.minVelFps
        }
        return if (w > 0) sum / w else sorted.first().bc
    }

    fun round5(v: Double) = Math.round(v * 100000.0) / 100000.0
    fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
}

class EpBackup private constructor(
    private val tables: Map<String, List<JSONObject>>,
    private val tombstones: Map<String, Tombstone>,
    val exportedAt: String,
) {
    data class Tombstone(val id: String, val table: String?, val deletedAt: Long?)

    val tombstoned: Set<String> get() = tombstones.keys

    fun tombstone(id: String): Tombstone? = tombstones[id]

    private fun isDead(table: String, id: String, r: JSONObject): Boolean {
        val t = tombstones[id] ?: return false
        if (t.table != null && t.table != table) return false
        val updated = r.optDN("updatedAt") ?: return true
        val deleted = t.deletedAt ?: return true
        return deleted > updated
    }

    fun live(table: String): List<JSONObject> = tables[table].orEmpty().filter { r ->
        val id = r.opt("id") as? String
        id != null && !Ep.isLibraryRow(table, id) && !isDead(table, id, r)
    }

    fun liveIds(table: String): Set<String> = live(table).mapTo(HashSet()) { it.getString("id") }

    fun byId(table: String, id: String): JSONObject? = tables[table].orEmpty().firstOrNull { it.optSN("id") == id }

    companion object {
        fun parse(text: String): EpBackup {
            val o = JsonCodec.parseObject(text, "Empirical Precision backup")
            val format = o.optSN("format")
            if (format != Ep.BACKUP_FORMAT) {
                throw IllegalArgumentException(
                    when {
                        o.optJSONArray("sessions") != null || o.optJSONArray("firearms") != null ->
                            "This is an Empirical Precision session file (EPSF), not a backup. Export a backup in EP (Custom Data → Back Up and Move → Export Backup)."
                        o.optJSONObject("bulletDragGrid") != null -> "This is Empirical Precision's master component database, not a backup of your data."
                        format == SkywardFormat.FORMAT || format in SkywardFormat.LEGACY_FORMATS -> "This is a Skyward Ballistics library file. Import it with the Skyward Ballistics format."
                        format == null -> "This is not an Empirical Precision backup: it carries no format tag."
                        else -> "This is not an Empirical Precision backup (format \"$format\")."
                    }
                )
            }
            val version = o.opt("version")
            if (version !is Number || version.toInt() != Ep.BACKUP_VERSION || version.toDouble() != Ep.BACKUP_VERSION.toDouble()) {
                throw IllegalArgumentException("Empirical Precision backup version $version is not one Skyward Ballistics reads (expected ${Ep.BACKUP_VERSION}). Export the backup again from the current EP.")
            }
            val t = o.optJSONObject("tables") ?: throw IllegalArgumentException("The Empirical Precision backup has no tables.")
            val tomb = o.optJSONArray("tombstones") ?: throw IllegalArgumentException("The Empirical Precision backup has no tombstone list.")
            val tables = LinkedHashMap<String, List<JSONObject>>()
            val keys = t.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = t.opt(k)
                if (a !is JSONArray) throw IllegalArgumentException("Empirical Precision backup: table '$k' is not an array of records.")
                for (i in 0 until a.length()) {
                    val row = a.opt(i) as? JSONObject ?: throw IllegalArgumentException("Empirical Precision backup: table '$k' row $i is not an object.")
                    if (row.has("id") && row.opt("id") !is String) throw IllegalArgumentException("Empirical Precision backup: table '$k' row $i has a non-string id.")
                }
                tables[k] = a.objects()
            }
            val dead = LinkedHashMap<String, Tombstone>()
            for (i in 0 until tomb.length()) {
                val r = tomb.optJSONObject(i) ?: throw IllegalArgumentException("The Empirical Precision backup has a malformed tombstone.")
                val id = r.opt("id") as? String ?: throw IllegalArgumentException("The Empirical Precision backup has a malformed tombstone.")
                val ts = Tombstone(id, r.opt("table") as? String, (r.opt("deletedAt") as? Number)?.toLong())
                val prev = dead[id]
                if (prev == null || (ts.deletedAt ?: Long.MAX_VALUE) > (prev.deletedAt ?: Long.MAX_VALUE)) dead[id] = ts
            }
            return EpBackup(tables, dead, o.optString("exportedAt", ""))
        }
    }
}

class EpMaster private constructor(
    val bullets: Map<String, JSONObject>,
    val cartridges: Map<String, JSONObject>,
    val diameters: Map<String, JSONObject>,
    val manufacturers: Map<String, JSONObject>,
    val grid: DoubleArray,
    val powders: Map<String, JSONObject> = emptyMap(),
    val primers: Map<String, JSONObject> = emptyMap(),
    val brass: Map<String, JSONObject> = emptyMap(),
) {
    companion object {
        fun parse(text: String): EpMaster {
            val o = JsonCodec.parseObject(text, "master-db.json")
            fun table(k: String) = o.optJSONArray(k).objects().filter { it.optSN("id") != null }.associateBy { it.getString("id") }
            val g = o.optJSONObject("bulletDragGrid")?.optJSONArray("mach")
            val grid = if (g != null && g.length() > 1) DoubleArray(g.length()) { g.getDouble(it) } else Ep.MACH_GRID
            if (!o.has("bullets")) throw IllegalArgumentException("master-db.json has no bullets table.")
            return EpMaster(table("bullets"), table("cartridges"), table("diameters"), table("manufacturers"), grid,
                table("powders"), table("primers"), table("brass"))
        }
    }
}

class EmpiricalSessionFormat : LibraryFormat {
    override val id = "empirical-epsf"
    override val displayName = "Empirical Precision session file (EPSF)"
    override val fileExtension = "json"
    override val exportKinds = LibraryKind.values().toSet()
    override val importKinds = emptySet<LibraryKind>()

    override fun import(text: String): Libraries =
        throw IllegalArgumentException("Empirical Precision session files are export-only here. To bring EP data in, use an EP backup (PDBF).")

    override fun export(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet?): String {
        val sel = LibraryFiles.select(kind, libs, resolveBullet)
        val notes = mutableListOf<String>()
        val manufacturers = LinkedHashMap<String, JSONObject>()
        val diameters = LinkedHashMap<String, JSONObject>()
        val cartridges = LinkedHashMap<String, JSONObject>()
        var anyDrag = false

        fun diameterId(diaIn: Double): String {
            Ep.diameterFor(diaIn)?.let { return it.id }
            val thou = (diaIn * 1000).roundToInt()
            val id = "dc-dia-$thou"
            diameters.getOrPut(id) {
                JSONObject().put("id", id)
                    .put("metricName", "%.2fmm".format(java.util.Locale.ROOT, diaIn * Ep.MM))
                    .put("imperialName", ".$thou")
                    .put("bulletDiameterMm", Ep.round5(diaIn * Ep.MM))
            }
            return id
        }

        fun manufacturerId(maker: String): String {
            Ep.manufacturerFor(maker)?.let { return it }
            val name = maker.ifBlank { "Unknown" }
            val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "unknown" }
            val id = "dc-man-$slug"
            manufacturers.getOrPut(id) { JSONObject().put("id", id).put("name", name).put("type", JSONArray().put("bullet")) }
            return id
        }

        val bulletById = HashMap<String, Bullet>()
        sel.bullets.forEach { bulletById[it.id] = it }
        fun findBullet(id: String) = bulletById[id] ?: libs.bullets.firstOrNull { it.id == id } ?: resolveBullet(id)

        fun rifleCartridge(r: Rifle): String {
            val epId = r.externalIds[Ep.KEY_CARTRIDGE]?.takeIf { it.isNotBlank() } ?: EpNames.cartridgeIdFor(r.cartridge)
            if (epId != null && Ep.isLibraryRow("cartridges", epId)) return epId
            val cid = epId ?: "dc-ct-${r.id}"
            if (cartridges.containsKey(cid)) return cid
            val dias = libs.loads.filter { it.rifleId == r.id }.mapNotNull { findBullet(it.bulletId)?.diameterIn }
            if (dias.isEmpty() && epId != null) return epId
            if (dias.isEmpty()) throw IllegalArgumentException(
                "Empirical Precision needs a cartridge (and so a calibre) for firearm '${r.name}', and Skyward Ballistics only knows a firearm's calibre from its ammo. Add ammo to '${r.name}' or leave it out of the export."
            )
            val dia = dias.groupingBy { (it * 1000).roundToInt() }.eachCount().maxByOrNull { it.value }!!.key / 1000.0
            cartridges[cid] = JSONObject().put("id", cid)
                .put("name", r.cartridge.trim().ifEmpty { "${r.name} chamber (Skyward Ballistics)" })
                .put("diameterId", diameterId(dias.first { (it * 1000).roundToInt() == (dia * 1000).roundToInt() }))
                .put("twistRateMm", Ep.round3(r.twistIn * Ep.MM))
            return cid
        }

        fun epRef(ref: String?): String? = ref?.takeIf { it.startsWith("empirical:") && it != EmpiricalLink.UNASSIGNED_REF }?.removePrefix("empirical:")
        fun firearmId(r: Rifle) = epRef(r.externalRef) ?: "dc-fa-${r.id}"
        fun bulletId(b: Bullet) = b.externalIds[Ep.KEY]?.takeIf { it.isNotBlank() } ?: "dc-bu-${b.id}"

        val firearms = JSONArray()
        if (kind == LibraryKind.RIFLES || kind == LibraryKind.MASTER) {
            for (r in sel.rifles) {
                val o = JSONObject().put("id", firearmId(r))
                    .put("nickname", r.name)
                    .put("cartridgeId", rifleCartridge(r))
                    .putOpt2("barrelLengthMm", r.barrelLengthIn?.let { Ep.round3(it * Ep.MM) })
                    .put("twistRateMm", Ep.round3(r.twistIn * Ep.MM))
                    .put("twistDirection", if (r.twistDirection == TwistDirection.LEFT) "left" else "right")
                    .put("sightOverBoreMm", Ep.round3(r.sightHeightIn * Ep.MM))
                if (r.elevationCorrection != 1.0) {
                    if (r.elevationCorrection in 0.8..1.2) o.put("elevationTrackingFactor", r.elevationCorrection)
                    else notes += "Firearm '${r.name}': its elevation correction (${r.elevationCorrection}) is outside what EP accepts (0.8-1.2) and was left out."
                }
                firearms.put(o)
            }
        }

        val bulletsOut = JSONArray()
        val sentBullets = HashSet<String>()
        var libraryBulletsNamed = 0
        fun sendBullet(b: Bullet, outId: String = bulletId(b)): String {
            if (Ep.isLibraryRow("bullets", outId)) { libraryBulletsNamed++; return outId }
            if (!sentBullets.add(outId)) return outId
            val sd = Ep.sd(b.weightGr, b.diameterIn)
            val physis = JSONObject().put("weightGrams", Ep.round5(b.weightGr * Ep.GRAMS_PER_GRAIN))
                .putOpt2("overallLengthMm", b.lengthIn?.let { Ep.round3(it * Ep.MM) })
                .putOpt2("ogiveLengthMm", b.noseLengthIn?.let { Ep.round3(it * Ep.MM) })
            val o = JSONObject().put("id", outId)
                .put("manufacturerId", manufacturerId(b.maker))
                .put("diameterId", diameterId(b.diameterIn))
                .put("name", b.name)
                .put("physis", physis)
            if (b.dragCurve.size >= 2) {
                val d = Ep.toEpDrag(b.dragCurve, sd)
                val drag = JSONObject()
                    .put("g7BC", Ep.round5(d.g7BC)).put("g1BC", Ep.round5(d.g1BC))
                    .put("shape", if (b.source == "library") "measured" else "edited")
                    .put("g1ToG7", Ep.G1_TO_G7)
                    .put("f7", JSONArray().also { a -> d.f7.forEach { a.put(Ep.round5(it)) } })
                if (b.source == "library") drag.put("source", "measured")
                o.put("ballistics", JSONObject().put("g1BC", Ep.round5(d.g1BC)).put("g7BC", Ep.round5(d.g7BC)))
                o.put("drag", drag)
                anyDrag = true
            } else {
                val rep = Ep.representativeBc(b.bcPoints)
                if (rep == null || b.dragModel == DragModel.CUSTOM) {
                    notes += "Bullet '${b.name}' has no BC rows or drag curve, so EP cannot fly it until you give it a BC."
                } else {
                    val (g1, g7) = if (b.dragModel == DragModel.G1) rep to rep * Ep.G1_TO_G7 else rep / Ep.G1_TO_G7 to rep
                    o.put("ballistics", JSONObject().put("g1BC", Ep.round5(g1)).put("g7BC", Ep.round5(g7)))
                }
            }
            bulletsOut.put(o)
            return outId
        }
        when (kind) {
            LibraryKind.BULLETS -> sel.bullets.forEach { sendBullet(it) }
            LibraryKind.MASTER -> libs.bullets.forEach { sendBullet(it) }
            else -> {}
        }

        val loadsOut = JSONArray()
        val sentLoads = HashSet<String>()
        if (kind == LibraryKind.LOADS || kind == LibraryKind.MASTER) {
            for (l in sel.loads) {
                val r = libs.rifles.firstOrNull { it.id == l.rifleId }
                    ?: throw IllegalArgumentException("Ammo '${l.name}' belongs to a firearm that is not in this export, so its cartridge for Empirical Precision is unknown.")
                val b = findBullet(l.bulletId)
                    ?: throw IllegalArgumentException("Ammo '${l.name}' uses a bullet (${l.bulletId}) that is not in Skyward Ballistics.")
                if (!sentLoads.add(epRef(l.externalRef) ?: "dc-ld-${l.id}")) continue
                fun epId(key: String) = l.externalIds[key]?.takeIf { it.isNotBlank() }
                val epBullet = epId(Ep.KEY_BULLET)
                val rc = l.recipe
                val typed = if (rc == null) emptyList() else listOfNotNull(
                    rc.powder.takeIf { it.isNotBlank() && epId(Ep.KEY_POWDER) == null }?.let { "powder $it" },
                    rc.primer.takeIf { it.isNotBlank() && epId(Ep.KEY_PRIMER) == null }?.let { "primer $it" },
                    rc.brass.takeIf { it.isNotBlank() && epId(Ep.KEY_BRASS) == null }?.let { "case $it" },
                )
                val noteText = listOfNotNull(l.notes.ifBlank { null },
                    typed.takeIf { it.isNotEmpty() }?.let { "Recipe typed in Skyward Ballistics: " + it.joinToString(", ") + "." }).joinToString("\n")
                fun mm(inches: Double?) = inches?.let { Math.round(it * Ep.MM * 10000.0) / 10000.0 }
                val generated = epRef(l.externalRef) != null && l.name.removeSuffix(EmpiricalLink.REMOVED) == EmpiricalLink.generatedName(rc)
                val o = JSONObject().put("id", epRef(l.externalRef) ?: "dc-ld-${l.id}")
                    .putOpt2("name", l.name.takeIf { !generated })
                    .put("cartridgeId", epId(Ep.KEY_CARTRIDGE) ?: rifleCartridge(r))
                    .put("bulletId", if (epBullet != null) sendBullet(b, epBullet) else sendBullet(b))
                    .putOpt2("powderId", epId(Ep.KEY_POWDER))
                    .putOpt2("primerId", epId(Ep.KEY_PRIMER))
                    .putOpt2("brassId", epId(Ep.KEY_BRASS))
                if (rc != null) o
                    .putOpt2("chargeWeightGrams", rc.chargeGr?.let { Math.round(it * Ep.GRAMS_PER_GRAIN * 1e9) / 1e9 })
                    .putOpt2("coalMm", mm(rc.coalIn))
                    .putOpt2("cbtoMm", mm(rc.cbtoIn))
                    .putOpt2("cbtoComp", rc.cbtoComparator.ifBlank { null })
                    .putOpt2("shoulderMm", mm(rc.shoulderBumpIn))
                    .putOpt2("shoulderComp", rc.shoulderComparator.ifBlank { null })
                    .putOpt2("firings", rc.firings)
                if (l.muzzleVelocityFps > 0) o.put("velocityMps", Ep.round3(l.muzzleVelocityFps / Ep.FPS_PER_MPS))
                o.putOpt2("notes", noteText.ifBlank { null })
                loadsOut.put(o)
            }
        }

        val instructions = buildString {
            append("Exported from Skyward Ballistics for Empirical Precision. Import on the Sessions page.")
            if (libraryBulletsNamed > 0) append(" Bullets that are in EP's own library are referred to by their EP id and use EP's data.")
            if (cartridges.isNotEmpty()) append(" Each firearm comes with a cartridge of your own named '<firearm> chamber (Skyward Ballistics)'; set its primer pocket in Components, or edit the firearm to use EP's library cartridge for its chambering.")
            notes.forEach { append(' ').append(it) }
        }
        val out = JSONObject()
            .put("_instructions", instructions)
            .put("_dragCurve", JSONObject().put("kind", kind.name.lowercase()).put("exportedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
        if (manufacturers.isNotEmpty()) out.put("manufacturers", JSONArray(manufacturers.values))
        if (diameters.isNotEmpty()) out.put("diameters", JSONArray(diameters.values))
        if (cartridges.isNotEmpty()) out.put("cartridges", JSONArray(cartridges.values))
        if (firearms.length() > 0) out.put("firearms", firearms)
        if (bulletsOut.length() > 0) out.put("bullets", bulletsOut)
        if (loadsOut.length() > 0) out.put("loads", loadsOut)
        if (anyDrag) out.put("bulletDragGrid", JSONObject().put("mach", JSONArray().also { a -> Ep.MACH_GRID.forEach { a.put(it) } }))
        return out.toString(1)
    }
}

class EmpiricalBackupFormat(
    private val masterDbJson: () -> String? = { null },
    private val libraryBullets: () -> List<Bullet> = { emptyList() },
) : LibraryFormat, DetailedImport {
    override val id = "empirical-pdbf"
    override val displayName = "Empirical Precision backup (PDBF)"
    override val fileExtension = "json"
    override val exportKinds = emptySet<LibraryKind>()
    override val importKinds = setOf(LibraryKind.RIFLES, LibraryKind.LOADS, LibraryKind.BULLETS, LibraryKind.MASTER)

    override fun export(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet?): String =
        throw IllegalArgumentException("Skyward Ballistics does not write Empirical Precision backups (EP says not to generate them). Use the EPSF session-file export.")

    override fun import(text: String): Libraries = importDetailed(text).libs

    override fun importDetailed(text: String): ImportResult {
        val lib = libraryBullets()
        val (libs, report) = EmpiricalLink.refresh(
            text, Libraries(), resolveBullet = { id -> lib.firstOrNull { it.id == id } },
            libraryBullets = lib, masterDbJson = masterDbJson(),
        )
        return ImportResult(libs, messages = report.messages)
    }
}
