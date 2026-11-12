package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.Provenance
import kotlin.math.roundToInt

object DragFileFormat : LibraryFormat, DetailedImport {
    const val COLLECTION = "Imported drag files"

    override val id = "drag-file"
    override val displayName = "Drag curve file (.drg, or a Mach / Cd table)"
    override val fileExtension = "drg"
    override val mimeType = "text/plain"
    override val exportKinds = emptySet<LibraryKind>()
    override val importKinds = setOf(LibraryKind.BULLETS)

    override fun export(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet?): String =
        throw IllegalArgumentException("Drag curve files are import-only. Export bullets as a Skyward Ballistics library or a CSV table.")

    override fun import(text: String): Libraries = importDetailed(text).libs

    override fun importDetailed(text: String): ImportResult {
        val lines = text.removePrefix("﻿").split('\n').map { it.trim('\r', ' ', '\t') }
        val first = lines.firstOrNull { it.isNotEmpty() } ?: throw IllegalArgumentException("The file is empty.")
        if (first.startsWith("{\"") || first == "{" || first.startsWith("[")) throw IllegalArgumentException("This is a JSON file, not a drag curve file.")
        val head = first.split(',').map { it.trim() }
        return if (head[0].equals("CFM", true) || head[0].equals("BRL", true)) drg(head, lines) else table(lines)
    }

    private fun drg(head: List<String>, lines: List<String>): ImportResult {
        if (head.size < 4) throw IllegalArgumentException("This drag file's first line is incomplete (expected: CFM, name, mass in kg, diameter in m).")
        val name = head[1].replace(Regex("\\s+"), " ").trim()
        val kg = head[2].toDoubleOrNull()
        val m = head[3].toDoubleOrNull()
        if (name.isEmpty() || kg == null || m == null || kg <= 0 || m <= 0) {
            throw IllegalArgumentException("This drag file's first line has no usable name, mass (kg) or diameter (m).")
        }
        val lengthM = head.getOrNull(4)?.toDoubleOrNull()?.takeIf { it > 0 }
        val remark = head.drop(if (head.getOrNull(4)?.toDoubleOrNull() != null) 5 else 4).joinToString(", ").trim()
        val pts = ArrayList<CdPoint>()
        var started = false
        for (ln in lines) {
            if (!started) { started = ln.isNotEmpty(); continue }
            val t = ln.split(Regex("[\\s;]+")).filter { it.isNotEmpty() }
            if (t.size != 2) continue
            val cd = t[0].toDoubleOrNull(); val mach = t[1].toDoubleOrNull()
            if (cd != null && mach != null) pts += CdPoint(mach, cd)
        }
        val radar = remark.contains("radar", true) || remark.contains("doppler", true)
        return one(
            name = name, maker = makerIn(name), diaIn = round(m / 0.0254, 4), weightGr = round(kg * 1000.0 / Ep.GRAMS_PER_GRAIN, 1),
            lengthIn = lengthM?.let { round(it / 0.0254, 3) }, pts = pts,
            prov = Provenance(source = listOf(makerIn(name), remark).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { "Drag file" },
                method = if (radar) "radar" else "published drag curve"),
        )
    }

    private val META = Regex("^(?:#|//|;)\\s*([A-Za-z_ ]+?)\\s*[:=]\\s*(.+)$")
    private val DICT_MACH = Regex("mach['\"]?\\s*[:=]\\s*([-+0-9.eE]+)", RegexOption.IGNORE_CASE)
    private val DICT_CD = Regex("cd['\"]?\\s*[:=]\\s*([-+0-9.eE]+)", RegexOption.IGNORE_CASE)

    private fun table(lines: List<String>): ImportResult {
        val meta = HashMap<String, String>()
        val raw = ArrayList<Pair<Double, Double>>()
        var machFirst: Boolean? = null
        for (ln in lines) {
            if (ln.isEmpty()) continue
            val m = META.find(ln)
            if (m != null) { meta[m.groupValues[1].lowercase().filter { it.isLetterOrDigit() }] = m.groupValues[2].trim(); continue }
            if (ln.startsWith("#") || ln.startsWith("//")) continue
            val dm = DICT_MACH.find(ln); val dc = DICT_CD.find(ln)
            if (dm != null && dc != null) {
                val a = dm.groupValues[1].toDoubleOrNull(); val b = dc.groupValues[1].toDoubleOrNull()
                if (a != null && b != null) { raw += a to b; if (machFirst == null) machFirst = true }
                continue
            }
            val t = ln.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
            if (t.size < 2) continue
            val a = t[0].toDoubleOrNull(); val b = t[1].toDoubleOrNull()
            if (a == null || b == null) {
                val h = t.map { it.lowercase().filter { c -> c.isLetter() } }
                val im = h.indexOfFirst { it.startsWith("mach") }; val ic = h.indexOfFirst { it == "cd" || it.startsWith("drag") }
                if (im in 0..1 && ic in 0..1 && im != ic && raw.isEmpty()) machFirst = im == 0
                continue
            }
            raw += a to b
        }
        if (raw.size < 2) throw IllegalArgumentException("This is not a drag curve file: it has no rows of Mach and Cd numbers.")
        val mf = machFirst ?: (raw.maxOf { it.first } > 1.2 || raw.maxOf { it.second } <= 1.2 && raw.zipWithNext().all { it.second.first >= it.first.first })
        val pts = raw.map { if (mf) CdPoint(it.first, it.second) else CdPoint(it.second, it.first) }

        fun num(vararg keys: String) = keys.firstNotNullOfOrNull { meta[it]?.replace(',', '.')?.toDoubleOrNull() }
        val dia = num("diameterin", "diameter") ?: num("diametermm")?.div(Ep.MM)
        val wt = num("weightgr", "weight", "weightgrains") ?: num("weightgrams", "weightg")?.div(Ep.GRAMS_PER_GRAIN)
        val name = meta["name"] ?: meta["bullet"]
        if (dia == null || wt == null || name.isNullOrBlank() || dia <= 0 || wt <= 0) {
            throw IllegalArgumentException(
                "This looks like a Mach / Cd drag table, but it does not say which bullet it is for. Add these lines at the top of the file and import it again:\n" +
                    "# name: (the bullet's name)\n# diameterIn: 0.308\n# weightGr: 175"
            )
        }
        val maker = meta["maker"] ?: meta["manufacturer"] ?: makerIn(name)
        return one(
            name = name.trim(), maker = maker, diaIn = round(dia, 4), weightGr = round(wt, 1),
            lengthIn = (num("lengthin", "length") ?: num("lengthmm")?.div(Ep.MM))?.takeIf { it > 0 }?.let { round(it, 3) }, pts = pts,
            prov = Provenance(source = meta["source"] ?: "Drag table", method = meta["method"] ?: "published drag curve",
                date = meta["date"] ?: "", license = meta["license"] ?: meta["licence"] ?: "", url = meta["url"] ?: ""),
        )
    }

    private fun one(name: String, maker: String, diaIn: Double, weightGr: Double, lengthIn: Double?, pts: List<CdPoint>, prov: Provenance): ImportResult {
        val msgs = mutableListOf<String>()
        val sorted = pts.filter { it.mach >= 0 && it.cd > 0 && it.cd <= 2.0 }.sortedBy { it.mach }
        val curve = ArrayList<CdPoint>()
        for (p in sorted) if (curve.isEmpty() || p.mach > curve.last().mach) curve += p
        if (curve.size < 2) throw IllegalArgumentException("The drag file for '$name' has fewer than two usable Mach / Cd rows.")
        if (curve.size < pts.size) msgs += "'$name': ${pts.size - curve.size} row(s) left out (repeated Mach or a Cd outside 0-2)."
        val dia = Ep.diameterFor(diaIn)?.takeIf { Math.abs(it.bulletDiameterMm / Ep.MM - diaIn) <= 0.001 }
        val named = name.substringBefore(' ').takeIf { it.length in 2..8 && (it[0] == '.' || it[0].isDigit()) && !it.contains('_') }
        if (dia == null) msgs += "'$name': the file's diameter (${BulletCsvFormat.num(diaIn)} in) is not a standard bullet diameter; it is kept as given because the file's Cd values refer to it."
        val bc = if (curve.first().mach <= 1.35 && curve.last().mach >= 2.0)
            Ep.toEpDrag(curve, Ep.sd(weightGr, diaIn)).g7BC.takeIf { it.isFinite() && it > 0 } else null
        val b = Bullet(
            id = "drag-" + slug(name), maker = maker, name = name, diameterIn = diaIn, weightGr = weightGr, lengthIn = lengthIn,
            dragModel = DragModel.CUSTOM, bcPoints = bc?.let { listOf(BcPoint(Ep.round3(it), 0.0)) } ?: emptyList(), dragCurve = curve,
            caliber = named ?: dia?.imperialName ?: ".${(diaIn * 1000).roundToInt()}", source = "library",
            collection = if (maker.equals("Lapua", true)) "Lapua radar drag data" else COLLECTION,
            provenance = prov,
            notes = "Drag curve from a published file, Mach ${BulletCsvFormat.num(curve.first().mach)} to ${BulletCsvFormat.num(curve.last().mach)}." +
                if (bc != null) " The G7 BC is worked out from the curve (Mach 1.35 to 2.7)." else "",
        )
        return ImportResult(Libraries(bullets = listOf(b)), messages = msgs, kind = LibraryKind.BULLETS)
    }

    private fun makerIn(name: String): String {
        val words = name.lowercase().split(Regex("[^a-z0-9&]+")).toSet()
        return Ep.MANUFACTURERS.values.firstOrNull { it.lowercase() in words } ?: ""
    }

    internal fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "bullet" }

    private fun round(v: Double, places: Int): Double { val f = Math.pow(10.0, places.toDouble()); return Math.round(v * f) / f }
}
