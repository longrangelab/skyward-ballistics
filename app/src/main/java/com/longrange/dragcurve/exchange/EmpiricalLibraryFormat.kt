package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.Provenance
import com.longrange.dragcurve.store.optDN
import com.longrange.dragcurve.store.optSN

object EmpiricalLibraryFormat : LibraryFormat, DetailedImport {
    const val COLLECTION = "Empirical Precision library"
    const val URL = "https://empiricalprecision.com/master-db.json"
    const val LICENSE = "Apache-2.0 (Empirical Precision repository licence)"

    override val id = "empirical-master"
    override val displayName = "Empirical Precision component library (master-db.json)"
    override val fileExtension = "json"
    override val exportKinds = emptySet<LibraryKind>()
    override val importKinds = setOf(LibraryKind.BULLETS)

    override fun export(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet?): String =
        throw IllegalArgumentException("Empirical Precision's component library is import-only (it is EP's own data).")

    override fun import(text: String): Libraries = importDetailed(text).libs

    override fun importDetailed(text: String): ImportResult {
        if (!text.contains("\"bulletDragGrid\"") && !text.contains("\"diameterId\"")) {
            throw IllegalArgumentException("This is not Empirical Precision's component library (master-db.json).")
        }
        val master = EpMaster.parse(text)
        val out = ArrayList<Bullet>()
        var noBc = 0; var noSize = 0; var curves = 0
        var order = 0
        for ((epId, r) in master.bullets) {
            val ph = r.optJSONObject("physis")
            val diaRec = r.optSN("diameterId")?.let { id ->
                master.diameters[id]?.let { d -> d.optDN("bulletDiameterMm")?.let { it / Ep.MM to (d.optSN("imperialName") ?: "") } }
                    ?: Ep.DIAMETERS.firstOrNull { it.id == id }?.let { it.bulletDiameterMm / Ep.MM to it.imperialName }
            }
            val wt = ph?.optDN("weightGrams")?.div(Ep.GRAMS_PER_GRAIN)
            if (diaRec == null || wt == null || wt <= 0) { noSize++; continue }
            val bal = r.optJSONObject("ballistics")
            val drag = r.optJSONObject("drag")
            val g7 = bal?.optDN("g7BC")?.takeIf { it > 0 } ?: drag?.optDN("g7BC")?.takeIf { it > 0 }
            val g1 = bal?.optDN("g1BC")?.takeIf { it > 0 } ?: drag?.optDN("g1BC")?.takeIf { it > 0 }
            if (g7 == null && g1 == null) { noBc++; continue }
            val shape = drag?.optSN("shape")
            var curve = emptyList<CdPoint>()
            val f7 = drag?.optJSONArray("f7")
            if (shape == "measured" && f7 != null && g7 != null && f7.length() == master.grid.size) {
                curve = Ep.fromEpDrag(master.grid, DoubleArray(f7.length()) { f7.getDouble(it) }, drag.optDN("g7BC") ?: g7, Ep.sd(wt, diaRec.first))
                    .filter { it.cd > 0 }
                if (curve.size < 2) curve = emptyList() else curves++
            }
            val maker = r.optSN("manufacturerId")?.let { master.manufacturers[it]?.optSN("name") ?: Ep.MANUFACTURERS[it] } ?: ""
            val name = r.optSN("name") ?: epId
            val w = Ep.round3(wt)
            val wTxt = BulletCsvFormat.num(Math.round(w * 10.0) / 10.0)
            val basis = bal?.optSN("basis")
            out += Bullet(
                id = "ep-$epId", maker = maker,
                name = if (Regex("\\bgr\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) name else "$wTxt gr $name",
                diameterIn = Ep.round5(diaRec.first), weightGr = w,
                lengthIn = ph?.optDN("overallLengthMm")?.takeIf { it > 0 }?.let { Ep.round3(it / Ep.MM) },
                noseLengthIn = ph?.optDN("ogiveLengthMm")?.takeIf { it > 0 }?.let { Ep.round3(it / Ep.MM) },
                dragModel = if (g7 != null) DragModel.G7 else DragModel.G1,
                bcPoints = listOf(BcPoint(Ep.round5(g7 ?: g1!!), 0.0)),
                dragCurve = curve,
                caliber = diaRec.second, source = "library", libraryOrder = order++,
                collection = COLLECTION,
                provenance = Provenance(
                    source = "Empirical Precision component library",
                    method = listOfNotNull(basis?.let { "BC: $it" }, if (curve.isNotEmpty()) "drag curve: measured" else null).joinToString("; "),
                    license = LICENSE, url = URL,
                ),
                externalIds = mapOf(Ep.KEY to epId),
                notes = if (g7 != null && g1 != null) "G1 BC ${BulletCsvFormat.num(Ep.round3(g1))}." else "",
            )
        }
        if (out.isEmpty()) throw IllegalArgumentException("Empirical Precision's component library has no bullets this app can use.")
        val msgs = mutableListOf("${out.size} bullets from Empirical Precision's library, $curves with a measured drag curve.")
        if (noBc > 0) msgs += "$noBc bullet(s) left out: no BC."
        if (noSize > 0) msgs += "$noSize bullet(s) left out: no diameter or weight."
        return ImportResult(Libraries(bullets = out), messages = msgs, kind = LibraryKind.BULLETS)
    }
}
