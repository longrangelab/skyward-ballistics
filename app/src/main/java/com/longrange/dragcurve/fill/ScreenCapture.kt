package com.longrange.dragcurve.fill

import com.longrange.dragcurve.match.UiNode
import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.TwistDirection
import kotlin.math.abs

enum class CaptureSource(
    val pkg: String,
    val refPrefix: String,
    val howTo: String,
) {
    RIFLE_EDITOR(
        "com.geoballistics.ballisticsarc", "geob:",
        "Open the Armory, tap ••• next to a rifle and choose Edit. Open the Optic section and scroll to the bottom, then press Back " +
            "without saving. Do the same for each rifle you want.",
    ),
    ;

    fun read(roots: List<UiNode>): List<CapturedProfile> = when (this) {
        RIFLE_EDITOR -> RifleEditorReader.read(roots)
    }

    companion object {
        fun forPackage(pkg: String?): CaptureSource? = entries.firstOrNull { it.pkg == pkg }
    }
}

data class CapturedProfile(
    val key: String,
    val name: String,
    val bulletMaker: String = "",
    val bulletName: String = "",
    val diameterIn: Double? = null,
    val weightGr: Double? = null,
    val lengthIn: Double? = null,
    val dragModelText: String? = null,
    val bc: Double? = null,
    val mvFps: Double? = null,
    val zeroYd: Double? = null,
    val zeroHeightIn: Double? = null,
    val zeroOffsetIn: Double? = null,
    val sightHeightIn: Double? = null,
    val twistIn: Double? = null,
    val twistRight: Boolean? = null,
    val angleUnit: AngleUnit? = null,
    val elevationCorrection: Double? = null,
    val windageCorrection: Double? = null,
    val atmosphere: String? = null,
) {
    fun mergedWith(newer: CapturedProfile) = CapturedProfile(
        key = key, name = newer.name.ifBlank { name },
        bulletMaker = newer.bulletMaker.ifBlank { bulletMaker }, bulletName = newer.bulletName.ifBlank { bulletName },
        diameterIn = newer.diameterIn ?: diameterIn, weightGr = newer.weightGr ?: weightGr, lengthIn = newer.lengthIn ?: lengthIn,
        dragModelText = newer.dragModelText ?: dragModelText, bc = newer.bc ?: bc, mvFps = newer.mvFps ?: mvFps,
        zeroYd = newer.zeroYd ?: zeroYd, zeroHeightIn = newer.zeroHeightIn ?: zeroHeightIn, zeroOffsetIn = newer.zeroOffsetIn ?: zeroOffsetIn,
        sightHeightIn = newer.sightHeightIn ?: sightHeightIn, twistIn = newer.twistIn ?: twistIn, twistRight = newer.twistRight ?: twistRight,
        angleUnit = newer.angleUnit ?: angleUnit, elevationCorrection = newer.elevationCorrection ?: elevationCorrection,
        windageCorrection = newer.windageCorrection ?: windageCorrection, atmosphere = newer.atmosphere ?: atmosphere,
    )
}

class CaptureStore {
    private val map = LinkedHashMap<String, CapturedProfile>()
    private var last: String? = null
    val profiles: List<CapturedProfile> get() = map.values.toList()

    fun add(read: List<CapturedProfile>): Boolean {
        var changed = false
        for (p0 in read) {
            val key = if (p0.key == OPEN_EDITOR) last ?: continue else p0.key
            val p = p0.copy(key = key, name = p0.name.ifBlank { map[key]?.name.orEmpty() })
            val old = map[key]
            val m = old?.mergedWith(p) ?: p
            if (m != old) { map[key] = m; changed = true }
            last = key
        }
        return changed
    }

    fun clear() { map.clear(); last = null }

    companion object { const val OPEN_EDITOR = "open-editor" }
}

internal object CaptureUnits {
    private val NUM = Regex("""-?\d+(?:[.,]\d+)?""")
    fun num(s: String?): Double? = s?.let { NUM.find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }

    private fun unitOf(s: String) = s.replace(NUM, "").trim().lowercase()

    fun length(v: Double, unit: String): Double? = when (unitOf(unit).substringBefore('-').trim()) {
        "in", "inch", "inches", "\"", "" -> v
        "cm" -> v / 2.54
        "mm" -> v / 25.4
        else -> null
    }
    fun distance(v: Double, unit: String): Double? = when (unitOf(unit)) {
        "y", "yd", "yds", "yard", "yards", "" -> v
        "m", "meter", "meters", "metre", "metres" -> v / 0.9144
        else -> null
    }
    fun velocity(v: Double, unit: String): Double? = when (unitOf(unit).replace(" ", "")) {
        "fps", "ft/s", "" -> v
        "mps", "m/s" -> v / 0.3048
        else -> null
    }
    fun weight(v: Double, unit: String): Double? = when (unitOf(unit)) {
        "gr", "grain", "grains", "" -> v
        "g", "gram", "grams" -> v * 15.4323584
        else -> null
    }
    fun bracket(label: String): String = Regex("""\(([^)]*)\)""").find(label)?.groupValues?.get(1)?.trim().orEmpty()
}

object RifleEditorReader {
    const val TITLE = "Edit Rifle"

    fun read(roots: List<UiNode>): List<CapturedProfile> {
        val title = roots.asSequence().flatMap { it.walk() }.firstOrNull { it.text == TITLE } ?: return emptyList()
        val screen = screenOf(title)
        val nodes = screen.walk().filter { it !== title && (it.text.isNotEmpty() || it.isEditText) && it.bounds.bottom - it.bounds.top >= 8 }.toList()
        val labels = nodes.filter { !it.isEditText }

        fun label(prefix: String): UiNode? = labels.firstOrNull { it.text == prefix || it.text.startsWith("$prefix (") }
        fun sameLine(a: UiNode, b: UiNode) =
            a.bounds.centerY in b.bounds.top..b.bounds.bottom || b.bounds.centerY in a.bounds.top..a.bounds.bottom
        fun row(l: UiNode): List<UiNode> =
            nodes.filter { it !== l && it.bounds.left >= l.bounds.left + 1 && sameLine(it, l) }.sortedBy { it.bounds.left }
        fun value(prefix: String): String? = label(prefix)?.let { l -> row(l).firstOrNull { CaptureUnits.num(it.text) != null || it.text.isNotBlank() }?.text }
        fun number(prefix: String): Double? = label(prefix)?.let { l -> row(l).firstNotNullOfOrNull { CaptureUnits.num(it.text) } }
        fun unit(prefix: String) = label(prefix)?.let { CaptureUnits.bracket(it.text) }.orEmpty()
        fun side(prefix: String): String? = label(prefix)?.let { l -> row(l).firstOrNull { it.text in SIDES }?.text }

        val name = label("Name")?.let { l -> row(l).firstOrNull { it.isEditText }?.text }?.trim().orEmpty()
        val zeroUnit = unit("Zero Range")
        val metric = zeroUnit.equals("m", true)
        fun len(p: String) = number(p)?.let { CaptureUnits.length(it, unit(p).removePrefix("1/")) }
        val elev = len("Elevation Offset")?.let { if (side("Elevation Offset") == "Down") -it else it }
        val wind = len("Windage Offset")?.let { if (side("Windage Offset") == "Left") -it else it }
        val units = value("Solution Units")?.uppercase()
        val bulletTitle = labels.firstOrNull { it.text == "Bullet" }?.let { b ->
            labels.filter { it.bounds.top > b.bounds.top && it.bounds.left <= b.bounds.left + 5 }.minByOrNull { it.bounds.top }
                ?.takeIf { !isField(it) && !it.text.contains("(") && it.bounds.top - b.bounds.bottom < 3 * maxOf(20, abs(b.bounds.bottom - b.bounds.top)) }?.text
        }.orEmpty()
        return listOf(CapturedProfile(
            key = if (name.isEmpty()) CaptureStore.OPEN_EDITOR else "rifle:$name",
            name = name,
            bulletName = bulletTitle,
            diameterIn = len("Cal"),
            weightGr = number("Weight")?.let { CaptureUnits.weight(it, unit("Weight")) },
            lengthIn = len("Length"),
            dragModelText = value("Drag function")?.uppercase(),
            bc = number("Ballistic Coefficient"),
            mvFps = number("MV")?.let { CaptureUnits.velocity(it, unit("MV").ifEmpty { if (metric) "m/s" else "fps" }) },
            zeroYd = number("Zero Range")?.let { CaptureUnits.distance(it, zeroUnit) },
            zeroHeightIn = elev,
            zeroOffsetIn = wind,
            sightHeightIn = len("Sight Height"),
            twistIn = len("Barrel Twist"),
            twistRight = side("Barrel Twist")?.let { it == "Right" },
            angleUnit = when (units) { "MRAD", "MIL", "MILS" -> AngleUnit.MIL; "MOA" -> AngleUnit.MOA; else -> null },
            elevationCorrection = number("Elevation SSF"),
            windageCorrection = number("Windage SSF"),
            atmosphere = value("Metro")?.uppercase(),
        ))
    }

    private val SIDES = setOf("Up", "Down", "Right", "Left")

    private val FIELDS = listOf("Name", "Zero Range", "Barrel Twist", "Cal (", "Ballistic Coefficient", "Drag function", "Sight Height", "Solution Units", "MV")

    private fun isField(n: UiNode) = !n.isEditText && FIELDS.any { f -> n.text == f || n.text.startsWith(if (f.endsWith("(")) f else "$f (") }

    private fun screenOf(title: UiNode): UiNode {
        var n = title
        while (true) {
            if (n.walk().any { it !== title && isField(it) }) return n
            n = n.parent ?: return n
        }
    }
}

object CaptureConvert {
    fun toImport(source: CaptureSource, profiles: List<CapturedProfile>, bullets: List<Bullet>): AppImport {
        val firearms = ArrayList<AppFirearm>()
        val ammo = ArrayList<AppAmmo>()
        val taken = HashSet<String>()
        for (p in profiles) {
            var fname = p.name.trim()
            var k = 2
            while (!taken.add(fname)) fname = "${p.name.trim()} ($k)".also { k++ }
            firearms += AppFirearm(
                name = fname,
                twistIn = p.twistIn,
                twistDirection = if (p.twistRight == false) TwistDirection.LEFT else TwistDirection.RIGHT,
                sightHeightIn = p.sightHeightIn,
                sightOffsetIn = 0.0,
                elevationUnit = p.angleUnit ?: AngleUnit.MIL,
                elevationClick = if (p.angleUnit == AngleUnit.MOA) 0.25 else 0.1,
                windageUnit = p.angleUnit ?: AngleUnit.MIL,
                windageClick = if (p.angleUnit == AngleUnit.MOA) 0.25 else 0.1,
                elevationCorrection = p.elevationCorrection ?: 1.0,
                windageCorrection = p.windageCorrection ?: 1.0,
                scopeRead = p.angleUnit != null,
                clicksRead = false,
            )
            ammo += ammoOf(source, p, fname, bullets)
        }
        return AppImport(firearms, ammo, refPrefix = source.refPrefix)
    }

    private fun ammoOf(source: CaptureSource, p: CapturedProfile, firearm: String, bullets: List<Bullet>): AppAmmo {
        val bulletLabel = listOf(p.bulletMaker, p.bulletName).filter { it.isNotBlank() }.joinToString(" ")
        val name = when {
            bulletLabel.isNotBlank() && p.weightGr != null -> "$bulletLabel ${fmt(p.weightGr)} gr"
            bulletLabel.isNotBlank() -> bulletLabel
            else -> p.name
        }
        val model = when (p.dragModelText) { "G1" -> DragModel.G1; "G7" -> DragModel.G7; else -> null }
        val library = if (model == null) match(p, bullets) else null
        val missing = buildList {
            if (p.mvFps == null) add("muzzle velocity")
            if (p.diameterIn == null || p.weightGr == null) add("bullet diameter and weight")
            if (p.zeroYd == null) add("zero range")
            if (p.sightHeightIn == null) add("sight height")
            if (p.twistIn == null) add("twist rate")
        }
        val problem = when {
            missing.isNotEmpty() -> "Not all of it was read yet (${missing.joinToString(", ")}). " + when (source) {
                CaptureSource.RIFLE_EDITOR -> "Open this rifle's editor again, open the Optic section and scroll to the bottom."
            }
            model == null && library == null ->
                "It uses the other app's own drag model (${p.dragModelText ?: "unknown"}), which isn't copied, and no matching bullet " +
                    "is in your Skyward bullet library. Import that bullet into your library, or add this ammo by hand with a G7 BC."
            model != null && (p.bc == null || p.bc <= 0) -> "No BC was read."
            else -> null
        }
        return AppAmmo(
            firearm = firearm,
            name = name,
            diameterIn = p.diameterIn ?: 0.0,
            weightGr = p.weightGr ?: 0.0,
            lengthIn = p.lengthIn,
            noseLengthIn = null,
            muzzleVelocityFps = p.mvFps ?: 0.0,
            atmosphereStandard = p.atmosphere ?: "ICAO",
            dragModel = model ?: if (library != null) DragModel.CUSTOM else null,
            dragModelText = when {
                library != null -> "drag curve of your library bullet"
                else -> p.dragModelText ?: ""
            },
            dragScaleFactor = 1.0,
            bcPoints = if (model != null && p.bc != null) listOf(BcPoint(p.bc, 0.0)) else emptyList(),
            powderTempF = null,
            velocityPerDegFps = 0.0,
            zeroRangeYd = p.zeroYd ?: 100.0,
            zeroHeightIn = p.zeroHeightIn ?: 0.0,
            zeroOffsetIn = p.zeroOffsetIn ?: 0.0,
            zeroAtmosphere = null,
            notes = "",
            problem = problem,
            libraryBulletId = library?.id,
            libraryBulletName = library?.let { listOf(it.maker, it.name).filter { s -> s.isNotBlank() }.joinToString(" ") },
        )
    }

    private fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)

    private val STOP = setOf("gr", "grain", "grains", "bullet", "bullets", "the", "and")
    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in STOP }.toSet()

    fun match(p: CapturedProfile, pool: List<Bullet>): Bullet? {
        val dia = p.diameterIn ?: return null
        val wt = p.weightGr ?: return null
        val maker = p.bulletMaker.lowercase()
        if (maker.isEmpty()) return null
        val cands = pool.filter { b ->
            abs(b.weightGr - wt) <= 0.5 && abs(b.diameterIn - dia) <= 0.0015 &&
                b.maker.lowercase().let { m -> m == maker || (minOf(m.length, maker.length) >= 4 && (m.startsWith(maker) || maker.startsWith(m))) } &&
                (b.dragCurve.size >= 2 || b.bcPoints.isNotEmpty())
        }
        if (cands.isEmpty()) return null
        val want = tokens(p.bulletName)
        if (want.isEmpty()) return cands.singleOrNull()
        val scored = cands.map { b -> b to (want.intersect(tokens(b.name)).size.toDouble() / want.size) }.sortedByDescending { it.second }
        val best = scored[0]
        val second = scored.getOrNull(1)
        if (second != null && second.second == best.second) {
            val curved = scored.filter { it.second == best.second && it.first.dragCurve.size >= 2 }
            return if (best.second >= 0.5 && curved.size == 1) curved[0].first else null
        }
        return if (best.second >= 0.5) best.first else null
    }
}
