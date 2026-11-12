package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.Provenance
import com.longrange.dragcurve.store.stableId
import java.math.BigDecimal

object BulletCsvFormat : LibraryFormat, DetailedImport {
    override val id = "bullets-csv"
    override val displayName = "Bullets as a table (.csv)"
    override val fileExtension = "csv"
    override val mimeType = "text/csv"
    override val exportKinds = setOf(LibraryKind.BULLETS)
    override val importKinds = setOf(LibraryKind.BULLETS)

    val COLUMNS = listOf(
        "id", "maker", "name", "caliber", "category", "diameterIn", "weightGr", "lengthIn", "noseLengthIn",
        "dragModel", "bcRows", "dragCurve", "source", "collection",
        "provenanceSource", "provenanceMethod", "provenanceDate", "provenanceLicense", "provenanceUrl", "notes",
    )

    override fun export(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet?): String {
        if (kind != LibraryKind.BULLETS) throw IllegalArgumentException("The CSV table holds bullets only. Use the Skyward Ballistics library format for firearms and ammo.")
        val sb = StringBuilder()
        sb.append(COLUMNS.joinToString(",")).append("\r\n")
        for (b in libs.bullets) {
            val p = b.provenance
            val cells = listOf(
                b.id, b.maker, b.name, b.caliber, b.category, num(b.diameterIn), num(b.weightGr),
                b.lengthIn?.let { num(it) } ?: "", b.noseLengthIn?.let { num(it) } ?: "", b.dragModel.name,
                b.bcPoints.joinToString("; ") { num(it.bc) + "@" + num(it.minVelFps) },
                b.dragCurve.joinToString("; ") { num(it.mach) + ":" + num(it.cd) },
                b.source, b.collection, p?.source ?: "", p?.method ?: "", p?.date ?: "", p?.license ?: "", p?.url ?: "", b.notes,
            )
            sb.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    internal fun num(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
    }

    private fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s != s.trim()) "\"" + s.replace("\"", "\"\"") + "\"" else s

    override fun import(text: String): Libraries = importDetailed(text).libs

    override fun importDetailed(text: String): ImportResult {
        val body = text.removePrefix("﻿")
        val first = body.lineSequence().firstOrNull { it.isNotBlank() } ?: throw IllegalArgumentException("The file is empty.")
        if (first.trimStart().startsWith("{") || first.trimStart().startsWith("[")) throw IllegalArgumentException("This is a JSON file, not a CSV table.")
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> first.count { it == d } }!!
        if (first.none { it == delim }) throw IllegalArgumentException("This is not a CSV table (its first line has no columns).")
        val rows = parse(body, delim).filter { r -> r.any { it.isNotBlank() } }
        val header = rows.first().map { norm(it) }
        fun col(vararg names: String): Int = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1
        val cName = col("name", "bullet", "bulletname", "description", "model", "product")
        val cDiaIn = col("diameterin", "diameter", "diameterinches", "diameterinch", "dia", "diain", "bulletdiameter", "bulletdiameterin")
        val cDiaMm = col("diametermm", "diamm", "bulletdiametermm")
        val cWtGr = col("weightgr", "weight", "weightgrains", "weightgrain", "grains", "bulletweight", "bulletweightgr", "wt", "wtgr")
        val cWtG = col("weightgrams", "weightg", "massg", "massgrams")
        if (cName < 0 || (cDiaIn < 0 && cDiaMm < 0) || (cWtGr < 0 && cWtG < 0)) {
            throw IllegalArgumentException("This CSV is not a bullet table: it needs columns for the bullet's name, diameter and weight (for example name, diameterIn, weightGr).")
        }
        val cId = col("id")
        val cMaker = col("maker", "manufacturer", "brand", "make", "mfr", "company")
        val cCal = col("caliber", "calibre", "cal")
        val cCat = col("category", "type", "use")
        val cLenIn = col("lengthin", "length", "lengthinches", "oal", "oalin", "bulletlength", "bulletlengthin")
        val cLenMm = col("lengthmm", "oalmm", "bulletlengthmm")
        val cNose = col("noselengthin", "noselength", "nose")
        val cModel = col("dragmodel", "dragfunction", "bctype", "bcmodel")
        val cRows = col("bcrows", "bcpoints")
        val cBc = col("bc")
        val cG7 = col("bcg7", "g7bc", "g7", "g7bcs")
        val cG1 = col("bcg1", "g1bc", "g1", "g1bcs")
        val cCurve = col("dragcurve", "curve", "cdcurve")
        val cSource = col("source")
        val cColl = col("collection", "library")
        val cPs = col("provenancesource"); val cPm = col("provenancemethod"); val cPd = col("provenancedate")
        val cPl = col("provenancelicense", "license", "licence"); val cPu = col("provenanceurl", "url")
        val cNotes = col("notes", "note", "comments", "comment")

        val msgs = mutableListOf<String>()
        val out = ArrayList<Bullet>()
        val seen = HashSet<String>()
        var bad = 0
        rows.drop(1).forEachIndexed { i, r ->
            fun s(c: Int) = if (c in r.indices) r[c].trim() else ""
            fun d(c: Int) = s(c).replace(',', '.').toDoubleOrNull()
            val name = s(cName)
            val dia = d(cDiaIn)?.let { if (it > 2.0) it / Ep.MM else it } ?: d(cDiaMm)?.div(Ep.MM)
            val wt = d(cWtGr) ?: d(cWtG)?.div(Ep.GRAMS_PER_GRAIN)
            if (name.isEmpty() || dia == null || wt == null || dia <= 0 || wt <= 0) {
                bad++
                if (bad <= 5) msgs += "Row ${i + 2} skipped: it needs a name, a diameter and a weight."
                return@forEachIndexed
            }
            val maker = s(cMaker)
            val curve = points(s(cCurve), ':').map { CdPoint(it.first, it.second) }.sortedBy { it.mach }
            val g7 = d(cG7); val g1 = d(cG1)
            var bcRows = points(s(cRows), '@').map { BcPoint(it.first, it.second) }
            val named = DragModel.values().firstOrNull { it.name.equals(s(cModel), ignoreCase = true) }
            var model = named ?: when { g7 != null -> DragModel.G7; g1 != null -> DragModel.G1; curve.size >= 2 && bcRows.isEmpty() && d(cBc) == null -> DragModel.CUSTOM; else -> DragModel.G7 }
            if (bcRows.isEmpty()) {
                val one = when (model) { DragModel.G1 -> g1 ?: d(cBc); DragModel.G7 -> g7 ?: d(cBc); DragModel.CUSTOM -> g7 ?: d(cBc) }
                if (one != null && one > 0) bcRows = listOf(BcPoint(one, 0.0))
            }
            if (model == DragModel.CUSTOM && curve.size < 2) model = DragModel.G7
            var notes = s(cNotes)
            if (model == DragModel.G7 && g7 != null && g1 != null && named == null && !notes.contains("G1 BC")) {
                notes = (notes + " G1 BC ${num(g1)}.").trim()
            }
            val prov = Provenance(s(cPs), s(cPm), s(cPd), s(cPl), s(cPu)).takeIf { !it.isEmpty }
            val library = s(cSource).equals("library", ignoreCase = true)
            var id = s(cId).ifEmpty { stableId("csv:${maker.lowercase()}|${name.lowercase()}|${num(Ep.round5(dia))}|${num(Ep.round3(wt))}") }
            if (!seen.add(id)) { id = stableId("$id#${i + 2}"); seen += id; msgs += "Row ${i + 2} ('$name') repeats an earlier id; it was given its own." }
            out += Bullet(
                id = id, maker = maker, name = name,
                diameterIn = if (d(cDiaIn)?.let { it <= 2.0 } == true) dia else Ep.round5(dia),
                weightGr = if (d(cWtGr) != null) wt else Ep.round3(wt),
                lengthIn = d(cLenIn)?.takeIf { it > 0 } ?: d(cLenMm)?.takeIf { it > 0 }?.let { Ep.round3(it / Ep.MM) },
                noseLengthIn = d(cNose)?.takeIf { it > 0 },
                dragModel = model, bcPoints = bcRows, dragCurve = curve,
                caliber = s(cCal), category = s(cCat),
                source = if (library) "library" else "user",
                collection = if (library) s(cColl) else "",
                provenance = prov, notes = notes,
            )
        }
        if (bad > 5) msgs += "${bad - 5} more row(s) skipped for the same reason."
        if (out.isEmpty()) throw IllegalArgumentException("The CSV table has no usable bullet rows (each needs a name, a diameter and a weight).")
        return ImportResult(Libraries(bullets = out), messages = msgs, kind = LibraryKind.BULLETS)
    }

    private fun norm(h: String) = h.removePrefix("﻿").lowercase().filter { it.isLetterOrDigit() }

    private fun points(s: String, sep: Char): List<Pair<Double, Double>> =
        s.split(';', '|').mapNotNull { part ->
            val t = part.trim().split(sep)
            if (t.size != 2) null else {
                val a = t[0].trim().toDoubleOrNull(); val b = t[1].trim().toDoubleOrNull()
                if (a == null || b == null) null else a to b
            }
        }

    internal fun parse(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted -> if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else quoted = false } else cur.append(c)
                c == '"' && cur.isBlank() -> { cur.setLength(0); quoted = true }
                c == delim -> { row.add(cur.toString()); cur.setLength(0) }
                c == '\r' -> {}
                c == '\n' -> { row.add(cur.toString()); cur.setLength(0); rows.add(row); row = ArrayList() }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); rows.add(row) }
        return rows
    }
}
