package com.longrange.dragcurve.ui.calc

import com.longrange.dragcurve.engine.Air
import com.longrange.dragcurve.engine.milTo
import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.Atmosphere
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

object Adjust {
    enum class Axis { ELEVATION, WINDAGE }

    fun unitLabel(u: AngleUnit): String = when (u) {
        AngleUnit.MIL -> "MIL"
        AngleUnit.MOA -> "MOA"
        AngleUnit.IPHY -> "IPHY"
    }

    fun dial(mil: Double, unit: AngleUnit, correction: Double): Double =
        milTo(unit, mil) * (if (correction > 0) correction else 1.0)

    private fun prefix(v: Double, axis: Axis, plusMinus: Boolean): String = when {
        v == 0.0 -> ""
        plusMinus -> if (v > 0) "+" else "-"
        axis == Axis.ELEVATION -> if (v > 0) "U" else "D"
        else -> if (v > 0) "R" else "L"
    }

    fun text(value: Double, axis: Axis, precision: String, click: Double, plusMinus: Boolean): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val (rounded, decimals) = when (precision) {
            "click" -> if (click > 0) {
                Math.rint(value / click) * click to Num.decimalsOf(click)
            } else value to 2
            "hundredth" -> roundTo(value, 2) to 2
            else -> roundTo(value, 1) to 1
        }
        if (abs(rounded) < 1e-9) return "0"
        return prefix(rounded, axis, plusMinus) + Num.fixed(abs(rounded), decimals)
    }

    fun clicksText(clicks: Double, axis: Axis, plusMinus: Boolean): String {
        if (clicks.isNaN() || clicks.isInfinite()) return "—"
        val n = Math.rint(clicks).roundToLong()
        if (n == 0L) return "0"
        return prefix(n.toDouble(), axis, plusMinus) + abs(n)
    }

    private fun roundTo(v: Double, d: Int): Double { val f = 10.0.pow(d); return Math.rint(v * f) / f }
}

object DensityAltitude {
    private const val RHO0 = 1.225
    private const val K = 2.25577e-5
    private const val EXP = 4.2559
    private const val PA_PER_INHG = 3386.389

    fun of(a: Atmosphere): Double {
        val rho = Air(a).density
        val hM = (1 - (rho / RHO0).pow(1 / EXP)) / K
        return hM / 0.3048
    }

    fun atmosphere(daFt: Double, tempF: Double): Atmosphere {
        val hM = daFt * 0.3048
        val rho = RHO0 * (1 - K * hM).coerceAtLeast(0.05).pow(EXP)
        val tK = (tempF - 32) * 5 / 9 + 273.15
        val pPa = rho * 287.058 * tK
        return Atmosphere(altitudeFt = daFt, pressureInHg = pPa / PA_PER_INHG, pressureIsAbsolute = true,
            temperatureF = tempF, humidityPct = 0.0)
    }
}

enum class DragSource(val label: String) {
    CURVE("Measured drag curve"),
    BULLET("Bullet's BC rows"),
    COLD("Cold-weather BC rows (< 30°F)"),
    MILD("Mild-weather BC rows"),
    HOT("Hot-weather BC rows (> 90°F)"),
    AUTO("Seasonal rows by temperature (auto)"),
    CUSTOM("My own BC rows");

    val season: String? get() = when (this) { COLD -> "cold"; MILD -> "mild"; HOT -> "hot"; else -> null }
}

object BcSelection {
    const val COLD_BELOW_F = 30.0
    const val HOT_ABOVE_F = 90.0

    fun hasCurve(b: Bullet) = b.dragCurve.size >= 2

    fun choices(b: Bullet): List<DragSource> = buildList {
        if (hasCurve(b)) add(DragSource.CURVE)
        if (b.bcPoints.isNotEmpty()) add(DragSource.BULLET)
        val seasons = b.seasonalBcPoints.filterValues { it.isNotEmpty() }.keys
        if ("cold" in seasons) add(DragSource.COLD)
        if ("mild" in seasons) add(DragSource.MILD)
        if ("hot" in seasons) add(DragSource.HOT)
        if (seasons.isNotEmpty()) add(DragSource.AUTO)
        add(DragSource.CUSTOM)
    }

    fun default(b: Bullet): DragSource = if (hasCurve(b)) DragSource.CURVE
        else if (b.bcPoints.isNotEmpty()) DragSource.BULLET else DragSource.CUSTOM

    fun seasonFor(tempF: Double): String = when {
        tempF < COLD_BELOW_F -> "cold"
        tempF > HOT_ABOVE_F -> "hot"
        else -> "mild"
    }

    private fun sameRows(a: List<BcPoint>?, b: List<BcPoint>?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        val sa = a.sortedByDescending { it.minVelFps }; val sb = b.sortedByDescending { it.minVelFps }
        return sa.indices.all { abs(sa[it].bc - sb[it].bc) < 1e-9 && abs(sa[it].minVelFps - sb[it].minVelFps) < 1e-6 }
    }

    fun detect(load: Load, b: Bullet): DragSource {
        if (load.dragModelOverride == DragModel.CUSTOM) return DragSource.CURVE
        when (load.bcSeason) {
            "auto" -> return DragSource.AUTO
            "cold" -> return DragSource.COLD
            "mild" -> return DragSource.MILD
            "hot" -> return DragSource.HOT
        }
        val rows = load.bcPointsOverride
        if (rows == null) {
            if (load.dragModelOverride == null || load.dragModelOverride == b.dragModel)
                return if (b.dragModel == DragModel.CUSTOM) DragSource.CURVE else DragSource.BULLET
            return DragSource.CUSTOM
        }
        if (load.dragModelOverride == null || load.dragModelOverride == DragModel.G7) {
            for ((season, src) in listOf("cold" to DragSource.COLD, "mild" to DragSource.MILD, "hot" to DragSource.HOT)) {
                if (sameRows(rows, b.seasonalBcPoints[season])) return src
            }
            if (b.dragModel == DragModel.G7 && sameRows(rows, b.bcPoints)) return DragSource.BULLET
        }
        return DragSource.CUSTOM
    }

    fun apply(load: Load, b: Bullet, src: DragSource, customModel: DragModel, customRows: List<BcPoint>): Load = when (src) {
        DragSource.CURVE -> load.copy(dragModelOverride = if (b.dragModel == DragModel.CUSTOM) null else DragModel.CUSTOM,
            bcPointsOverride = null, bcSeason = null)
        DragSource.BULLET -> load.copy(dragModelOverride = null, bcPointsOverride = null, bcSeason = null)
        DragSource.COLD, DragSource.MILD, DragSource.HOT, DragSource.AUTO ->
            load.copy(dragModelOverride = if (b.dragModel == DragModel.G7) null else DragModel.G7, bcPointsOverride = null,
                bcSeason = src.season ?: "auto")
        DragSource.CUSTOM -> load.copy(dragModelOverride = customModel, bcPointsOverride = customRows, bcSeason = null)
    }

    fun resolvedSeason(load: Load, b: Bullet, tempF: Double): String? {
        if (load.dragModelOverride == DragModel.CUSTOM) return null
        val s = when (val v = load.bcSeason) { null -> return null; "auto" -> seasonFor(tempF); else -> v }
        return s.takeIf { b.seasonalBcPoints[it]?.isNotEmpty() == true }
    }

    fun summary(load: Load, b: Bullet?): String {
        if (b == null) return "bullet missing"
        val w = Num.trim(b.weightGr, 1) + "gr"
        val model = load.dragModelOverride ?: b.dragModel
        if (model == DragModel.CUSTOM) return "$w drag curve"
        when (load.bcSeason) {
            "auto" -> return "$w G7 seasonal (by temperature)"
            "cold", "mild", "hot" -> return "$w G7 ${load.bcSeason}"
        }
        val rows = load.bcPointsOverride ?: b.bcPoints
        return when (rows.size) {
            0 -> "$w $model (no BC)"
            1 -> "$w $model ${Num.trim(rows[0].bc, 3)}"
            else -> "$w $model (${rows.size} BCs)"
        }
    }

    fun validate(rows: List<BcPoint>): String? {
        if (rows.isEmpty()) return "Enter at least one ballistic coefficient."
        if (rows.any { it.bc <= 0 || it.bc > 2.0 }) return "Each BC must be between 0 and 2."
        if (rows.any { it.minVelFps < 0 }) return "Min velocities can't be negative."
        if (rows.none { it.minVelFps == 0.0 }) return "One BC must have a min velocity of 0."
        if (rows.map { it.minVelFps }.toSet().size != rows.size) return "Two BC rows have the same min velocity."
        return null
    }
}

object BulletSearch {
    fun haystack(b: Bullet): String = listOf(
        b.maker, b.name, b.caliber, b.category, b.collection,
        Num.trim(b.weightGr, 1) + "gr", Num.trim(b.weightGr, 1) + " gr",
        Num.trim(b.diameterIn, 3), b.dragModel.name,
    ).joinToString(" ").lowercase()

    fun matches(b: Bullet, query: String): Boolean {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val h = haystack(b)
        return tokens.all { h.contains(it) }
    }

    fun sourceLabel(b: Bullet): String = when {
        b.source != "library" -> "My bullet"
        b.replacedBy.isNotEmpty() -> "Older library data"
        b.collection.isBlank() -> "Bullet library"
        b.collection.length <= 30 -> b.collection
        else -> b.collection.take(29) + "…"
    }

    fun title(b: Bullet): String =
        if (b.maker.isNotBlank() && !b.name.startsWith(b.maker, ignoreCase = true)) "${b.maker} ${b.name}" else b.name

    fun subtitle(b: Bullet): String {
        val parts = mutableListOf(Num.trim(b.diameterIn, 3) + " in", Num.trim(b.weightGr, 1) + " gr")
        b.lengthIn?.let { parts += "L " + Num.trim(it, 3) }
        if (b.dragCurve.size >= 2) parts += "drag curve"
        if (b.bcPoints.isNotEmpty()) {
            val m = if (b.dragModel == DragModel.CUSTOM) "G7" else b.dragModel.name
            parts += if (b.bcPoints.size == 1) "$m ${Num.trim(b.bcPoints[0].bc, 3)}" else "$m (${b.bcPoints.size} BCs)"
        }
        if (b.seasonalBcPoints.isNotEmpty()) parts += "seasonal"
        if (b.collection.isNotBlank()) parts += b.collection
        return parts.joinToString(" · ")
    }
}

object LoadFlags {
    const val ZERO = "Zero not confirmed"
    const val MV_NEEDED = "Muzzle velocity needed"
    const val MV_PREDICTED = "Muzzle velocity not measured"
    const val NEWER_BULLET_DATA = "Newer bullet data available"

    fun mvNeeded(l: Load) = l.muzzleVelocityFps < 300

    fun list(l: Load, bullet: Bullet? = null): List<String> = buildList {
        if (mvNeeded(l)) add(MV_NEEDED)
        else if (l.muzzleVelocitySource == com.longrange.dragcurve.exchange.EmpiricalLink.MV_PREDICTED) add(MV_PREDICTED)
        if (!l.zeroConfirmed) add(ZERO)
        if (bullet != null && bullet.replacedBy.isNotEmpty()) add(NEWER_BULLET_DATA)
    }

    fun text(l: Load, bullet: Bullet? = null): String? = list(l, bullet).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "⚠ ")
}

object RecipeText {
    fun summary(r: com.longrange.dragcurve.model.Recipe?): String {
        if (r == null || r.isEmpty) return "None (factory ammo)"
        val parts = listOfNotNull(
            r.powder.takeIf { it.isNotBlank() }?.let { p -> r.chargeGr?.let { Num.trim(it, 2) + " gr " + p } ?: p } ?: r.chargeGr?.let { Num.trim(it, 2) + " gr" },
            r.primer.takeIf { it.isNotBlank() },
            r.brass.takeIf { it.isNotBlank() }?.let { "$it case" },
            r.coalIn?.let { "COAL " + Num.trim(it, 4) },
            r.cbtoIn?.let { "CBTO " + Num.trim(it, 4) },
        )
        return if (parts.isEmpty()) listOf(r.bullet, r.cartridge).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Entered" } else parts.joinToString(" · ")
    }
}

object BulletBook {
    fun ordered(all: List<Bullet>): List<Bullet> =
        all.filter { it.source != "library" }.sortedBy { BulletSearch.title(it).lowercase() } +
            all.filter { it.source == "library" }
                .sortedWith(compareBy({ it.collection.isNotEmpty() }, { it.collection }, { it.libraryOrder ?: Int.MAX_VALUE }, { it.name }))

    fun copyName(name: String, existing: Collection<String>): String {
        val base = name.trim().replace(Regex("\\s*\\(copy(?: \\d+)?\\)$"), "")
        val taken = existing.map { it.trim().lowercase() }.toSet()
        var n = 1
        while (true) {
            val candidate = if (n == 1) "$base (copy)" else "$base (copy $n)"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    fun copyOf(src: Bullet, newId: String, existingNames: Collection<String>): Bullet =
        src.copy(id = newId, source = "user", libraryOrder = null, name = copyName(src.name, existingNames), updatedAt = 0)

    const val MIN_DIAMETER_IN = 0.1
    const val MAX_DIAMETER_IN = 1.0
    const val MIN_WEIGHT_GR = 10.0
    const val MAX_WEIGHT_GR = 1000.0
    const val MIN_BC = 0.05
    const val MAX_BC = 1.5

    fun diameterProblem(inches: Double?): String? =
        if (inches == null || inches < MIN_DIAMETER_IN || inches > MAX_DIAMETER_IN) "Enter a diameter from 0.1 to 1.0 in (for example 0.264)" else null

    fun weightProblem(grains: Double?): String? =
        if (grains == null || grains < MIN_WEIGHT_GR || grains > MAX_WEIGHT_GR) "Enter a weight from 10 to 1000 gr" else null

    fun bcProblem(rows: List<RowDraft>): String? =
        if (rows.any { r -> r.bc?.let { it < MIN_BC || it > MAX_BC } == true }) "A BC must be between 0.05 and 1.5" else null
}

object WindText {
    fun clock(deg: Double): String {
        val d = ((deg % 360) + 360) % 360
        val h = Math.rint(d / 30.0).toInt().let { if (it == 0 || it == 12) 12 else it }
        val side = when {
            d < 15 || d > 345 -> "head"
            d in 165.0..195.0 -> "tail"
            d < 180 -> "from right"
            else -> "from left"
        }
        return "$h o'clock · $side"
    }

    fun normalize(deg: Double): Double = ((deg % 360) + 360) % 360
}

object CalcGuard {
    fun problem(r: Rifle, l: Load, b: Bullet): String? = when {
        l.muzzleVelocityFps <= 0 -> "Ammo '${l.name}' has no muzzle velocity. Edit the ammo and enter one."
        l.zeroRangeYd <= 0 -> "Ammo '${l.name}' has no zero range. Edit the ammo and enter one."
        l.dragScaleFactor <= 0 -> "Ammo '${l.name}' has a drag scale factor of 0. Edit the ammo."
        b.diameterIn <= 0 || b.weightGr <= 0 -> "The bullet of '${l.name}' has no diameter or weight. Edit the ammo."
        runCatching { com.longrange.dragcurve.engine.Drag.of(b, l) }.isFailure ->
            "Ammo '${l.name}' has no ballistic coefficient or drag curve. Edit the ammo and enter a BC."
        r.twistIn <= 0 -> "Firearm '${r.name}' has no barrel twist. Edit the firearm."
        r.elevationClick <= 0 || r.windageClick <= 0 -> "Firearm '${r.name}' has a click value of 0. Edit the firearm."
        r.elevationCorrection <= 0 || r.windageCorrection <= 0 -> "Firearm '${r.name}' has a correction factor of 0. Edit the firearm."
        else -> null
    }
}

object RifleText {
    fun subtitle(r: Rifle): String {
        val dir = if (r.twistDirection.name == "LEFT") "L" else "R"
        return "1:${Num.trim(r.twistIn, 2)}\" $dir · sight ${Num.trim(r.sightHeightIn, 2)}\" · " +
            "${Adjust.unitLabel(r.elevationUnit)} ${Num.trim(r.elevationClick, 3)}/click"
    }
}

object Calibration {
    fun dialToMil(dial: Double, unit: AngleUnit, correction: Double): Double =
        dial / Adjust.dial(1.0, unit, correction)

    fun baseMvFor(load: Load, mvAtTemp: Double, tempF: Double): Double =
        mvAtTemp - load.velocityPerDegFps * (tempF - load.powderTempF)

    fun hint(velocityMode: Boolean, machs: List<Double>): String? {
        if (machs.isEmpty()) return null
        val minMach = machs.min()
        return when {
            velocityMode && minMach < 1.2 -> "Some impacts are near or below transonic (Mach ${Num.fixed(minMach, 2)}): drag truing suits them better."
            !velocityMode && minMach > 1.5 -> "All impacts are well supersonic: velocity truing suits them better."
            else -> null
        }
    }
}
