package com.longrange.dragcurve.engine

import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.Atmosphere
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import com.longrange.dragcurve.model.SpinDriftModel
import com.longrange.dragcurve.model.TwistDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val FT = 0.3048
const val YD = 0.9144
const val INCH = 0.0254
const val GRAIN = 6.479891e-5
const val G0 = 9.80665

fun localGravity(latDeg: Double, altitudeFt: Double): Double {
    val s2 = Math.sin(Math.toRadians(latDeg)).let { it * it }
    val g0 = 9.7803253359 * (1 + 0.00193185265241 * s2) / Math.sqrt(1 - 0.00669437999013 * s2)
    return g0 - 3.086e-6 * altitudeFt * FT
}
const val MPH = 0.44704
private const val LB_IN2_TO_KG_M2 = 703.06958
private const val OMEGA_EARTH = 7.2921159e-5
const val MOA_PER_MIL = 3.43774677

object DragTables {
    val G7 = doubleArrayOf(
        0.00, 0.1198, 0.05, 0.1197, 0.10, 0.1196, 0.15, 0.1194, 0.20, 0.1193,
        0.25, 0.1194, 0.30, 0.1194, 0.35, 0.1194, 0.40, 0.1193, 0.45, 0.1193,
        0.50, 0.1194, 0.55, 0.1193, 0.60, 0.1194, 0.65, 0.1197, 0.70, 0.1202,
        0.725, 0.1207, 0.75, 0.1215, 0.775, 0.1226, 0.80, 0.1242, 0.825, 0.1266,
        0.85, 0.1306, 0.875, 0.1368, 0.90, 0.1464, 0.925, 0.1660, 0.95, 0.2054,
        0.975, 0.2993, 1.00, 0.3803, 1.025, 0.4015, 1.05, 0.4043, 1.075, 0.4034,
        1.10, 0.4014, 1.125, 0.3987, 1.15, 0.3955, 1.20, 0.3884, 1.25, 0.3810,
        1.30, 0.3732, 1.35, 0.3657, 1.40, 0.3580, 1.50, 0.3440, 1.55, 0.3376,
        1.60, 0.3315, 1.65, 0.3260, 1.70, 0.3209, 1.75, 0.3160, 1.80, 0.3117,
        1.85, 0.3078, 1.90, 0.3042, 1.95, 0.3010, 2.00, 0.2980, 2.05, 0.2951,
        2.10, 0.2922, 2.15, 0.2892, 2.20, 0.2864, 2.25, 0.2835, 2.30, 0.2807,
        2.35, 0.2779, 2.40, 0.2752, 2.45, 0.2725, 2.50, 0.2697, 2.55, 0.2670,
        2.60, 0.2643, 2.65, 0.2615, 2.70, 0.2588, 2.75, 0.2561, 2.80, 0.2533,
        2.85, 0.2506, 2.90, 0.2479, 2.95, 0.2451, 3.00, 0.2424, 3.10, 0.2368,
        3.20, 0.2313, 3.30, 0.2258, 3.40, 0.2205, 3.50, 0.2154, 3.60, 0.2106,
        3.70, 0.2060, 3.80, 0.2017, 3.90, 0.1975, 4.00, 0.1935, 4.20, 0.1861,
        4.40, 0.1793, 4.60, 0.1730, 4.80, 0.1672, 5.00, 0.1618,
    )
    val G1 = doubleArrayOf(
        0.000, 0.2629, 0.050, 0.2558, 0.100, 0.2487, 0.150, 0.2413, 0.200, 0.2344,
        0.250, 0.2278, 0.300, 0.2214, 0.350, 0.2155, 0.400, 0.2104, 0.450, 0.2061,
        0.500, 0.2032, 0.550, 0.2020, 0.600, 0.2034, 0.700, 0.2165, 0.725, 0.2230,
        0.750, 0.2313, 0.775, 0.2417, 0.800, 0.2546, 0.825, 0.2706, 0.850, 0.2901,
        0.875, 0.3136, 0.900, 0.3415, 0.925, 0.3734, 0.950, 0.4084, 0.975, 0.4448,
        1.000, 0.4805, 1.025, 0.5136, 1.050, 0.5427, 1.075, 0.5677, 1.100, 0.5883,
        1.125, 0.6053, 1.150, 0.6191, 1.200, 0.6393, 1.250, 0.6518, 1.300, 0.6589,
        1.350, 0.6621, 1.400, 0.6625, 1.450, 0.6607, 1.500, 0.6573, 1.550, 0.6528,
        1.600, 0.6474, 1.650, 0.6413, 1.700, 0.6347, 1.750, 0.6280, 1.800, 0.6210,
        1.850, 0.6141, 1.900, 0.6072, 1.950, 0.6003, 2.000, 0.5934, 2.050, 0.5867,
        2.100, 0.5804, 2.150, 0.5743, 2.200, 0.5685, 2.250, 0.5630, 2.300, 0.5577,
        2.350, 0.5527, 2.400, 0.5481, 2.450, 0.5438, 2.500, 0.5397, 2.600, 0.5325,
        2.700, 0.5264, 2.800, 0.5211, 2.900, 0.5168, 3.000, 0.5133, 3.100, 0.5105,
        3.200, 0.5084, 3.300, 0.5067, 3.400, 0.5054, 3.500, 0.5040, 3.600, 0.5030,
        3.700, 0.5022, 3.800, 0.5016, 3.900, 0.5010, 4.000, 0.5006, 4.200, 0.4998,
        4.400, 0.4995, 4.600, 0.4992, 4.800, 0.4990, 5.000, 0.4988,
    )

    fun lookup(t: DoubleArray, x: Double): Double {
        val n = t.size / 2
        if (x <= t[0]) return t[1]
        if (x >= t[2 * (n - 1)]) return t[2 * n - 1]
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (t[2 * mid] <= x) lo = mid else hi = mid }
        val x0 = t[2 * lo]; val x1 = t[2 * hi]
        return t[2 * lo + 1] + (x - x0) / (x1 - x0) * (t[2 * hi + 1] - t[2 * lo + 1])
    }
}

class Air(a: Atmosphere) {
    val tempK = (a.temperatureF - 32) * 5 / 9 + 273.15
    val stationPa: Double = (a.pressureInHg * 3386.389).let { p ->
        if (a.pressureIsAbsolute) p else p * (1 - 2.25577e-5 * a.altitudeFt * FT).pow(5.25588)
    }
    val density: Double = run {
        val tc = tempK - 273.15
        val es = 611.21 * exp((18.678 - tc / 234.5) * (tc / (257.14 + tc)))
        val pv = a.humidityPct / 100.0 * es
        (stationPa - pv) / (287.058 * tempK) + pv / (461.495 * tempK)
    }
    val speedOfSound = sqrt(1.4 * 287.058 * tempK)
}

class Drag(
    val model: DragModel,
    bcPoints: List<BcPoint>,
    curve: List<CdPoint>,
    val weightGr: Double,
    val diameterIn: Double,
    val scale: Double = 1.0,
) {
    private val rows = bcPoints.sortedByDescending { it.minVelFps }
    private val curveT = DoubleArray(curve.size * 2).also { t ->
        curve.sortedBy { it.mach }.forEachIndexed { i, p -> t[2 * i] = p.mach; t[2 * i + 1] = p.cd }
    }
    private val aOverM = PI / 4 * (diameterIn * INCH).pow(2) / (weightGr * GRAIN)

    init {
        require(model == DragModel.CUSTOM || rows.isNotEmpty()) { "no BC rows" }
        require(model != DragModel.CUSTOM || curve.size >= 2) { "drag curve needs at least 2 points" }
    }

    fun bcAt(vFps: Double): Double {
        if (rows.size == 1) return rows[0].bc
        for (r in rows) if (vFps >= r.minVelFps) return r.bc
        return rows.last().bc
    }

    fun k(rho: Double, airSpeed: Double, groundSpeed: Double, sos: Double): Double {
        val mach = airSpeed / sos
        return scale * when (model) {
            DragModel.CUSTOM -> 0.5 * rho * DragTables.lookup(curveT, mach) * aOverM * airSpeed
            DragModel.G1, DragModel.G7 -> {
                val bc = bcAt(groundSpeed / FT) * LB_IN2_TO_KG_M2
                val cd = DragTables.lookup(if (model == DragModel.G1) DragTables.G1 else DragTables.G7, mach)
                rho * cd * PI / 8.0 / bc * airSpeed
            }
        }
    }

    companion object {
        fun seasonFor(tempF: Double) = when { tempF < 30 -> "cold"; tempF > 90 -> "hot"; else -> "mild" }

        fun of(bullet: Bullet, load: Load? = null, tempF: Double = 59.0): Drag {
            val model = load?.dragModelOverride ?: bullet.dragModel
            val season = when (val s = load?.bcSeason) { null -> null; "auto" -> seasonFor(tempF); else -> s }
            val rows = season?.let { bullet.seasonalBcPoints[it] } ?: load?.bcPointsOverride ?: bullet.bcPoints
            return Drag(model, rows, bullet.dragCurve, bullet.weightGr, bullet.diameterIn, load?.dragScaleFactor ?: 1.0)
        }
    }
}

data class Row(
    val rangeYd: Double,
    val pathIn: Double,
    val pathMil: Double,
    val comeUpMil: Double,
    val windageIn: Double,
    val windageMil: Double,
    val windage2Mil: Double?,
    val windDriftIn: Double,
    val spinDriftIn: Double,
    val aeroJumpMil: Double,
    val coriolisVertIn: Double,
    val coriolisHorizIn: Double,
    val leadMil: Double,
    val velocityFps: Double,
    val mach: Double,
    val energyFtLb: Double,
    val tofS: Double,
    val stability: Double,
)

data class Solution(
    val rows: List<Row>,
    val muzzleVelocityFps: Double,
    val zeroAngleMil: Double,
    val stabilityMuzzle: Double,
    val transonicYd: Double?,
    val subsonicYd: Double?,
) {
    fun at(rangeYd: Double): Row = rows.minBy { abs(it.rangeYd - rangeYd) }
}

fun milTo(unit: AngleUnit, mil: Double, rangeYd: Double = 100.0): Double = when (unit) {
    AngleUnit.MIL -> mil
    AngleUnit.MOA -> mil * MOA_PER_MIL
    AngleUnit.IPHY -> mil * 3.6
}

fun clicks(mil: Double, unit: AngleUnit, click: Double, correction: Double): Double =
    milTo(unit, mil) * correction / click

object Engine {
    var dt = 0.0005

    fun muzzleVelocity(load: Load, tempF: Double): Double =
        load.muzzleVelocityFps + load.velocityPerDegFps * (tempF - load.powderTempF)

    fun stability(bullet: Bullet, rifle: Rifle, mvFps: Double, atmo: Atmosphere): Double {
        val d = bullet.diameterIn
        val len = (bullet.lengthIn ?: estimateLength(bullet)) / d
        val t = rifle.twistIn / d
        val sg = 30.0 * bullet.weightGr / (t * t * d.pow(3) * len * (1 + len * len))
        val air = Air(atmo)
        val tempR = air.tempK * 9 / 5
        val pInHg = air.stationPa / 3386.389
        return sg * (mvFps / 2800.0).pow(1.0 / 3.0) * (tempR / 518.67) * (29.92 / pInHg)
    }

    fun estimateLength(b: Bullet): Double {
        val r = b.weightGr / b.diameterIn.pow(3) / 1000.0
        return b.diameterIn * 1.22 * r.pow(0.73)
    }

    fun solve(
        rifle: Rifle,
        load: Load,
        bullet: Bullet,
        shot: ShotInputs,
        maxRangeYd: Double = shot.distanceYd,
        stepYd: Double = 50.0,
        extraRangesYd: List<Double> = listOf(shot.distanceYd),
        mvShiftFps: Double = 0.0,
        bcFactor: Double = 1.0,
    ): Solution {
        val zeroDrag = Drag.of(bullet, load, shot.atmosphere.temperatureF)
        val drag = if (bcFactor == 1.0) zeroDrag
            else Drag.of(bullet, load.copy(dragScaleFactor = load.dragScaleFactor / bcFactor), shot.atmosphere.temperatureF)
        val mv = muzzleVelocity(load, shot.atmosphere.temperatureF) + mvShiftFps
        val zeroAtmo = load.zeroAtmosphere ?: shot.atmosphere
        val mvZero = muzzleVelocity(load, zeroAtmo.temperatureF)
        val zero = zeroAngles(rifle, load, zeroDrag, mvZero, zeroAtmo, gravityFor(shot))

        val ranges = buildList {
            var r = 0.0
            while (r <= maxRangeYd + 1e-9) { add(r); r += stepYd }
            addAll(extraRangesYd.filter { it >= 0.0 })
        }.distinct().sorted()

        val air = Air(shot.atmosphere)
        val look = Math.toRadians(shot.lookAngleDeg)
        val wa = Math.toRadians(shot.windAngleDeg)
        fun windVec(mph: Double) = doubleArrayOf(-mph * MPH * cos(wa), 0.0, -mph * MPH * sin(wa))
        val main = fly(drag, mv, rifle, air, zero.elevRad + look, zero.windRad, windVec(shot.windSpeed1Mph), shot, ranges, look)
        val second = if (shot.windSpeed2Mph != 0.0)
            fly(drag, mv, rifle, air, zero.elevRad + look, zero.windRad, windVec(shot.windSpeed2Mph), shot, ranges, look)
        else null
        val calm = if (shot.windSpeed1Mph != 0.0)
            fly(drag, mv, rifle, air, zero.elevRad + look, zero.windRad, doubleArrayOf(0.0, 0.0, 0.0), shot, ranges, look)
        else main

        val sg0 = stability(bullet, rifle, mv, shot.atmosphere)
        val twistSign = if (rifle.twistDirection == TwistDirection.RIGHT) 1.0 else -1.0
        val crossMph = shot.windSpeed1Mph * sin(wa)
        val lCal = (bullet.lengthIn ?: estimateLength(bullet)) / bullet.diameterIn
        val ajMoaPerMph = 0.01 * sg0 - 0.0024 * lCal + 0.032
        val ajMil = if (shot.aeroJump) crossMph * twistSign * ajMoaPerMph / MOA_PER_MIL else 0.0
        val litz = { t: Double -> 1.25 * (sg0 + 1.2) * t.pow(1.83) }
        val zeroSpinIn = if (shot.spinDrift && shot.spinDriftModel == SpinDriftModel.LITZ && load.zeroRangeYd > 0)
            litz(fly(drag, mv, rifle, air, zero.elevRad + look, zero.windRad, doubleArrayOf(0.0, 0.0, 0.0), shot,
                listOf(load.zeroRangeYd), look)[0].t) else 0.0
        val cant = Math.toRadians(shot.cantDeg)

        val rows = ranges.mapIndexed { i, r ->
            val p = main[i]; val c = calm[i]
            val rangeM = r * YD
            val v = p.speed
            val sgHere = sg0 * (v / (mv * FT)).pow(-1.198)
            val sd = if (!shot.spinDrift || r <= 0) 0.0 else twistSign * when (shot.spinDriftModel) {
                SpinDriftModel.MEASURED -> 2.184 * sg0.pow(0.537) * p.t.pow(1.920)
                SpinDriftModel.LITZ -> litz(p.t) - zeroSpinIn * r / load.zeroRangeYd
            }
            val ajIn = if (r > 0) ajMil / 1000.0 * rangeM / INCH else 0.0
            var pathIn = p.perp / INCH + ajIn
            var windIn = p.z / INCH + sd
            if (cant != 0.0 && r > 0) {
                val rise = -pathIn
                windIn += rise * sin(cant)
                pathIn -= rise * (1 - cos(cant))
            }
            val toMil = { inches: Double -> if (r > 0) atan2(inches * INCH, rangeM) * 1000.0 else 0.0 }
            val leadIn = shot.targetSpeedMph * MPH * p.t / INCH
            Row(
                rangeYd = r,
                pathIn = pathIn,
                pathMil = toMil(pathIn),
                comeUpMil = -toMil(pathIn),
                windageIn = windIn,
                windageMil = -toMil(windIn),
                windage2Mil = second?.let { -toMil(it[i].z / INCH + sd) },
                windDriftIn = (p.z - c.z) / INCH,
                spinDriftIn = sd,
                aeroJumpMil = if (r > 0) ajMil else 0.0,
                coriolisVertIn = if (shot.coriolis) (p.perp - p.perpNoCoriolis) / INCH else 0.0,
                coriolisHorizIn = if (shot.coriolis) (p.z - p.zNoCoriolis) / INCH else 0.0,
                leadMil = toMil(leadIn),
                velocityFps = v / FT,
                mach = v / air.speedOfSound,
                energyFtLb = 0.5 * bullet.weightGr * GRAIN * v * v / 1.3558179483,
                tofS = p.t,
                stability = sgHere,
            )
        }
        return Solution(
            rows = rows,
            muzzleVelocityFps = mv,
            zeroAngleMil = zero.elevRad * 1000,
            stabilityMuzzle = sg0,
            transonicYd = crossing(rows, 1.2),
            subsonicYd = crossing(rows, 1.0),
        )
    }

    private fun crossing(rows: List<Row>, mach: Double): Double? {
        for (i in 0 until rows.size - 1) {
            val a = rows[i]; val b = rows[i + 1]
            if (a.mach >= mach && b.mach < mach) return a.rangeYd + (a.mach - mach) / (a.mach - b.mach) * (b.rangeYd - a.rangeYd)
        }
        return null
    }

    private class Point(val t: Double, val perp: Double, val z: Double, val speed: Double,
                        val perpNoCoriolis: Double, val zNoCoriolis: Double)

    private data class Zero(val elevRad: Double, val windRad: Double)

    fun gravityFor(shot: ShotInputs): Double =
        if (shot.localGravity) localGravity(shot.latitudeDeg, shot.atmosphere.altitudeFt) else G0

    private fun zeroAngles(rifle: Rifle, load: Load, drag: Drag, mv: Double, atmo: Atmosphere, g: Double): Zero {
        val air = Air(atmo)
        val zx = load.zeroRangeYd * YD
        val noWind = doubleArrayOf(0.0, 0.0, 0.0)
        fun yAt(theta: Double): Double {
            val s = integrate(drag, mv, rifle.sightHeightIn * INCH, 0.0, air, theta, 0.0, noWind, null, doubleArrayOf(zx), 0.0, g)
            return s[0][1] - load.zeroHeightIn * INCH
        }
        var t1 = 0.0; var t2 = 0.004
        var y1 = yAt(t1); var y2 = yAt(t2)
        var elev = Double.NaN
        for (iter in 0 until 30) {
            if (abs(y2 - y1) < 1e-14) break
            val t3 = t2 - y2 * (t2 - t1) / (y2 - y1)
            t1 = t2; y1 = y2; t2 = t3; y2 = yAt(t2)
            if (abs(y2) < 1e-7) { elev = t2; break }
        }
        if (elev.isNaN()) {
            var lo = -0.01; var hi = 0.05
            repeat(60) { val mid = 0.5 * (lo + hi); if (yAt(mid) > 0) hi = mid else lo = mid }
            elev = 0.5 * (lo + hi)
        }
        val windRad = (rifle.sightOffsetIn * INCH + load.zeroOffsetIn * INCH) / zx
        return Zero(elev, windRad)
    }

    private fun fly(drag: Drag, mv: Double, rifle: Rifle, air: Air, elevRad: Double, windRad: Double,
                    wind: DoubleArray, shot: ShotInputs, rangesYd: List<Double>, look: Double): List<Point> {
        val targets = rangesYd.map { it * YD }.toDoubleArray()
        val cor = if (shot.coriolis) earth(shot) else null
        val a = integrate(drag, mv, rifle.sightHeightIn * INCH, rifle.sightOffsetIn * INCH, air, elevRad, windRad, wind, cor, targets, look, gravityFor(shot))
        val b = if (cor != null) integrate(drag, mv, rifle.sightHeightIn * INCH, rifle.sightOffsetIn * INCH, air, elevRad, windRad, wind, null, targets, look, gravityFor(shot)) else a
        return targets.indices.map { i ->
            val s = a[i]; val n = b[i]
            Point(s[6], perp(s, look), s[2], speed(s), perp(n, look), n[2])
        }
    }

    private fun speed(s: DoubleArray) = sqrt(s[3] * s[3] + s[4] * s[4] + s[5] * s[5])

    private fun perp(s: DoubleArray, look: Double) = -s[0] * sin(look) + s[1] * cos(look)

    private fun earth(shot: ShotInputs): DoubleArray {
        val lat = Math.toRadians(shot.latitudeDeg); val az = Math.toRadians(shot.azimuthDeg)
        return doubleArrayOf(
            OMEGA_EARTH * cos(lat) * cos(az),
            OMEGA_EARTH * sin(lat),
            -OMEGA_EARTH * cos(lat) * sin(az),
        )
    }

    private fun integrate(drag: Drag, mv: Double, sightH: Double, sightOff: Double, air: Air,
                          elevRad: Double, windRad: Double, wind: DoubleArray, omega: DoubleArray?,
                          targets: DoubleArray, look: Double, g: Double = G0): List<DoubleArray> {
        val rho = air.density; val sos = air.speedOfSound
        val v0 = mv * FT
        val cl = cos(look); val sl = sin(look)
        var s = doubleArrayOf(sightH * sl, -sightH * cl, -sightOff,
            v0 * cos(elevRad) * cos(windRad), v0 * sin(elevRad), v0 * cos(elevRad) * sin(windRad), 0.0)
        val out = ArrayList<DoubleArray>(targets.size)
        var idx = 0
        val along = { st: DoubleArray -> st[0] * cl + st[1] * sl }
        while (idx < targets.size && targets[idx] <= 0.0) { out.add(s.copyOf()); idx++ }
        val k1 = DoubleArray(6); val k2 = DoubleArray(6); val k3 = DoubleArray(6); val k4 = DoubleArray(6)
        val tmp = DoubleArray(7)
        val h = dt
        while (idx < targets.size && s[6] < 30.0) {
            deriv(s, drag, rho, sos, wind, omega, k1, g)
            for (j in 0..5) tmp[j] = s[j] + 0.5 * h * k1[j]
            deriv(tmp, drag, rho, sos, wind, omega, k2, g)
            for (j in 0..5) tmp[j] = s[j] + 0.5 * h * k2[j]
            deriv(tmp, drag, rho, sos, wind, omega, k3, g)
            for (j in 0..5) tmp[j] = s[j] + h * k3[j]
            deriv(tmp, drag, rho, sos, wind, omega, k4, g)
            val ns = DoubleArray(7)
            for (j in 0..5) ns[j] = s[j] + h / 6 * (k1[j] + 2 * k2[j] + 2 * k3[j] + k4[j])
            ns[6] = s[6] + h
            val a0 = along(s); val a1 = along(ns)
            while (idx < targets.size && a1 >= targets[idx]) {
                val fr = (targets[idx] - a0) / (a1 - a0)
                out.add(DoubleArray(7) { j -> s[j] + fr * (ns[j] - s[j]) })
                idx++
            }
            s = ns
            if (s[3] <= 0) break
        }
        while (out.size < targets.size) out.add(s.copyOf())
        return out
    }

    private fun deriv(s: DoubleArray, drag: Drag, rho: Double, sos: Double, wind: DoubleArray,
                      omega: DoubleArray?, d: DoubleArray, g: Double) {
        val rvx = s[3] - wind[0]; val rvy = s[4] - wind[1]; val rvz = s[5] - wind[2]
        val air = sqrt(rvx * rvx + rvy * rvy + rvz * rvz)
        val ground = sqrt(s[3] * s[3] + s[4] * s[4] + s[5] * s[5])
        val k = drag.k(rho, air, ground, sos)
        var ax = -k * rvx; var ay = -k * rvy - g; var az = -k * rvz
        if (omega != null) {
            ax += -2 * (omega[1] * s[5] - omega[2] * s[4])
            ay += -2 * (omega[2] * s[3] - omega[0] * s[5])
            az += -2 * (omega[0] * s[4] - omega[1] * s[3])
        }
        d[0] = s[3]; d[1] = s[4]; d[2] = s[5]; d[3] = ax; d[4] = ay; d[5] = az
    }
}
