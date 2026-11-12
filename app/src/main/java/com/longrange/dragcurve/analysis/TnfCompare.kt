package com.longrange.dragcurve.analysis

import com.longrange.dragcurve.engine.Engine
import com.longrange.dragcurve.model.Atmosphere
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2

object TnfCompare {
    const val DRIFT_RANGE_YD = 1000.0
    const val WIND_MPH = 10.0
    const val MAX_ENTRIES = 10
    const val MV_LOW = 800.0
    const val MV_HIGH = 4500.0

    enum class Kind { AMMO, BULLET, CUSTOM }

    data class Entry(val kind: Kind, val refId: String = "", val label: String = "", val mvFps: Double? = null, val g7Bc: Double? = null)

    data class Flight(val title: String, val subtitle: String, val bullet: Bullet, val load: Load)

    sealed class MatchMv {
        data class At(val mvFps: Double) : MatchMv()
        object AboveMax : MatchMv()
        object NotAvailable : MatchMv()
    }

    data class Row(
        val entry: Entry,
        val title: String,
        val subtitle: String,
        val mvFps: Double?,
        val g7Bc: Double?,
        val bcIsEquivalent: Boolean,
        val tnf: Double?,
        val driftMil: Double?,
        val deltaTnf: Double?,
        val isReference: Boolean,
        val matchMv: MatchMv,
        val problem: String = "",
        val index: Int = 0,
    )

    fun byTnf(rows: List<Row>): List<Row> =
        rows.sortedWith(compareByDescending<Row> { it.tnf != null }.thenByDescending { it.tnf ?: 0.0 })

    fun customFlight(label: String, mvFps: Double, g7: Double): Flight {
        val b = Bullet(id = "tnf-custom", name = label.ifBlank { "Typed in" }, diameterIn = 0.308, weightGr = 175.0,
            dragModel = DragModel.G7, bcPoints = listOf(BcPoint(g7, 0.0)))
        return Flight(label.ifBlank { "Typed in" }, "Typed in: G7 BC ${"%.3f".format(java.util.Locale.US, g7)}", b, load(b, mvFps))
    }

    fun load(b: Bullet, mvFps: Double) = Load(id = "tnf", rifleId = "tnf", name = "", bulletId = b.id, muzzleVelocityFps = mvFps)

    private val rifle = Rifle(id = "tnf", name = "")
    private val shot = ShotInputs(distanceYd = DRIFT_RANGE_YD, windSpeed1Mph = WIND_MPH, windAngleDeg = 90.0,
        spinDrift = false, aeroJump = false, atmosphere = Atmosphere())

    class Calc {
        private val cache = HashMap<Triple<Bullet, Load, Double>, Double>()

        fun driftMil(bullet: Bullet, load: Load, mvFps: Double): Double {
            val l = load.copy(muzzleVelocityFps = mvFps, velocityPerDegFps = 0.0, zeroRangeYd = 100.0, zeroHeightIn = 0.0,
                zeroOffsetIn = 0.0, zeroAtmosphere = null, bcSeason = load.bcSeason?.let { if (it == "auto") "mild" else it })
            val key = Triple(bullet, l.copy(updatedAt = 0), mvFps)
            return cache.getOrPut(key) {
                val row = Engine.solve(rifle, l, bullet, shot, maxRangeYd = DRIFT_RANGE_YD, stepYd = DRIFT_RANGE_YD).at(DRIFT_RANGE_YD)
                atan2(kotlin.math.abs(row.windDriftIn) * 0.0254, DRIFT_RANGE_YD * 0.9144) * 1000.0
            }
        }

        fun g7(bullet: Bullet, load: Load, mvFps: Double): Pair<Double, Boolean> {
            val model = load.dragModelOverride ?: bullet.dragModel
            val rows = load.bcPointsOverride ?: bullet.bcPoints
            if (model == DragModel.G7 && rows.size == 1 && load.bcSeason == null && load.dragScaleFactor == 1.0)
                return rows[0].bc to false
            val target = driftMil(bullet, load, mvFps)
            val probe = customFlight("", mvFps, 0.3).bullet
            fun d(bc: Double) = driftMil(probe.copy(bcPoints = listOf(BcPoint(bc, 0.0))), load(probe, mvFps), mvFps)
            var lo = 0.05; var hi = 1.5
            if (d(hi) > target) return hi to true
            if (d(lo) < target) return lo to true
            while (hi - lo > 0.00005) {
                val mid = (lo + hi) / 2
                if (d(mid) > target) lo = mid else hi = mid
            }
            return (lo + hi) / 2 to true
        }

        fun matchMv(bullet: Bullet, load: Load, refDriftMil: Double): MatchMv {
            if (driftMil(bullet, load, MV_HIGH) > refDriftMil + 1e-9) return MatchMv.AboveMax
            if (driftMil(bullet, load, MV_LOW) <= refDriftMil) return MatchMv.At(MV_LOW)
            var lo = MV_LOW; var hi = MV_HIGH
            while (hi - lo > 0.5) {
                val mid = (lo + hi) / 2
                if (driftMil(bullet, load, mid) > refDriftMil) lo = mid else hi = mid
            }
            return MatchMv.At(hi)
        }
    }

    fun compare(entries: List<Entry>, flightOf: (Entry) -> Flight?, calc: Calc, referenceIndex: Int? = null): List<Row> {
        data class Base(val e: Entry, val f: Flight?, val mv: Double?, val bc: Double?, val eq: Boolean, val tnf: Double?, val drift: Double?, val problem: String)
        val base = entries.map { e ->
            val f = flightOf(e)
            if (f == null) return@map Base(e, null, e.mvFps, null, false, null, null,
                if (e.kind == Kind.AMMO) "This ammo was deleted." else "This bullet is no longer in your library.")
            val mv = f.load.muzzleVelocityFps
            try {
                val (bc, eq) = calc.g7(f.bullet, f.load, mv)
                Base(e, f, mv, bc, eq, mv * bc, calc.driftMil(f.bullet, f.load, mv), "")
            } catch (x: Exception) {
                Base(e, f, mv, null, false, null, null, "Can't fly this bullet: ${x.message ?: "missing drag data"}.")
            }
        }
        val refIdx = referenceIndex?.takeIf { it in base.indices && base[it].tnf != null }
            ?: base.indices.filter { base[it].tnf != null }.maxByOrNull { base[it].tnf!! }
        val ref = refIdx?.let { base[it] }
        return base.mapIndexed { i, x ->
            val match = when {
                x.f == null || x.tnf == null || ref?.drift == null -> MatchMv.NotAvailable
                i == refIdx -> MatchMv.At(x.mv!!)
                else -> calc.matchMv(x.f.bullet, x.f.load, ref.drift)
            }
            Row(
                entry = x.e, title = x.f?.title ?: x.e.label.ifBlank { "Missing" }, subtitle = x.f?.subtitle ?: "",
                mvFps = x.mv, g7Bc = x.bc, bcIsEquivalent = x.eq, tnf = x.tnf, driftMil = x.drift,
                deltaTnf = if (x.tnf != null && ref?.tnf != null) x.tnf - ref.tnf else null,
                isReference = i == refIdx, matchMv = match, problem = x.problem, index = i,
            )
        }
    }

    fun toJson(entries: List<Entry>): String = JSONArray().apply {
        for (e in entries) put(JSONObject().put("kind", e.kind.name.lowercase()).put("ref", e.refId).put("label", e.label)
            .apply { e.mvFps?.let { put("mv", it) }; e.g7Bc?.let { put("g7", it) } })
    }.toString()

    fun fromJson(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val kind = Kind.entries.firstOrNull { it.name.equals(o.optString("kind"), true) } ?: return@mapNotNull null
                fun num(k: String) = if (o.has(k)) o.optDouble(k).takeIf { !it.isNaN() && it > 0 } else null
                val e = Entry(kind, o.optString("ref"), o.optString("label"), num("mv"), num("g7"))
                when (kind) {
                    Kind.AMMO -> e.takeIf { it.refId.isNotEmpty() }
                    Kind.BULLET -> e.takeIf { it.refId.isNotEmpty() && it.mvFps != null }
                    Kind.CUSTOM -> e.takeIf { it.mvFps != null && it.g7Bc != null }
                }
            }.take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }
}
