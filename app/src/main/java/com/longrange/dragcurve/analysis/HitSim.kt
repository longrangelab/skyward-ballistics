package com.longrange.dragcurve.analysis

import com.longrange.dragcurve.engine.Engine
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object HitSim {
    const val ES5_PER_SIGMA = 3.077
    const val MOA_PER_MIL = 3.43774677
    const val IN_PER_MIL_PER_YD = 0.036

    const val WIND_SPEED = "Wind speed"
    const val WIND_DIRECTION = "Wind direction"

    private const val MV_STEP = 10.0
    private const val BC_STEP = 0.02
    private const val RANGE_STEP = 5.0
    private const val CROSS_STEP = 10.0
    private const val HEAD_STEP = 10.0

    data class Inputs(
        val rangeYd: Double = 600.0,
        val targetWidthIn: Double = 12.0,
        val targetHeightIn: Double = 12.0,
        val round: Boolean = false,
        val windMph: Double = 8.0,
        val windFromDeg: Double = 90.0,
        val mvSdFps: Double = 10.0,
        val bcSdPct: Double = 1.5,
        val rangeSdYd: Double = 3.0,
        val windSpeedSdMph: Double = 1.5,
        val windDirSdDeg: Double = 10.0,
        val groupMoa: Double = 0.75,
        val aimSdMoa: Double = 0.2,
        val shots: Int = 5000,
    )

    data class Sensitivity(
        val rangeYd: Double,
        val vPerFps: Double, val hPerFps: Double,
        val vPerBcPct: Double, val hPerBcPct: Double,
        val vPerYd: Double, val hPerYd: Double,
        val vPerCrossMph: Double, val hPerCrossMph: Double,
        val vPerHeadMph: Double = 0.0, val hPerHeadMph: Double = 0.0,
    )

    data class Cause(val name: String, val vIn: Double, val hIn: Double, val note: String = "") {
        val totalIn: Double get() = sqrt(vIn * vIn + hIn * hIn)
    }

    data class Result(
        val rangeYd: Double,
        val probability: Double,
        val hits: Int,
        val shots: Int,
        val impacts: List<Pair<Double, Double>>,
        val hitFlags: List<Boolean>,
        val sdVIn: Double,
        val sdHIn: Double,
        val causes: List<Cause>,
    )

    fun sensitivities(rifle: Rifle, load: Load, bullet: Bullet, shot: ShotInputs, windMph: Double, windFromDeg: Double,
                      rangesYd: List<Double>): List<Sensitivity> {
        val ranges = rangesYd.filter { it > RANGE_STEP }.distinct().sorted()
        if (ranges.isEmpty()) return emptyList()
        val all = ranges.flatMap { listOf(it - RANGE_STEP, it, it + RANGE_STEP) }.distinct().sorted()
        val max = all.last()
        val base = shot.copy(distanceYd = max, windSpeed1Mph = windMph, windAngleDeg = windFromDeg, windSpeed2Mph = 0.0,
            targetSpeedMph = 0.0)
        fun run(s: ShotInputs, dv: Double = 0.0, bc: Double = 1.0) =
            Engine.solve(rifle, load, bullet, s, maxRangeYd = max, stepYd = max, extraRangesYd = all, mvShiftFps = dv, bcFactor = bc)
        val s0 = run(base)
        val sMv = run(base, dv = MV_STEP)
        val sBc = run(base, bc = 1 + BC_STEP)
        val wa = Math.toRadians(windFromDeg)
        val head = windMph * cos(wa); val cross = windMph * sin(wa) + CROSS_STEP
        val sX = run(base.copy(windSpeed1Mph = sqrt(head * head + cross * cross), windAngleDeg = Math.toDegrees(kotlin.math.atan2(cross, head))))
        val head2 = windMph * cos(wa) + HEAD_STEP; val cross2 = windMph * sin(wa)
        val sH = run(base.copy(windSpeed1Mph = sqrt(head2 * head2 + cross2 * cross2), windAngleDeg = Math.toDegrees(kotlin.math.atan2(cross2, head2))))
        return ranges.map { r ->
            val o = s0.at(r)
            fun dv(p: com.longrange.dragcurve.engine.Solution) = o.comeUpMil - p.at(r).comeUpMil
            fun dh(p: com.longrange.dragcurve.engine.Solution) = o.windageMil - p.at(r).windageMil
            val lo = s0.at(r - RANGE_STEP); val hi = s0.at(r + RANGE_STEP)
            Sensitivity(
                rangeYd = r,
                vPerFps = dv(sMv) / MV_STEP, hPerFps = dh(sMv) / MV_STEP,
                vPerBcPct = dv(sBc) / (BC_STEP * 100), hPerBcPct = dh(sBc) / (BC_STEP * 100),
                vPerYd = (hi.comeUpMil - lo.comeUpMil) / (2 * RANGE_STEP),
                hPerYd = (hi.windageMil - lo.windageMil) / (2 * RANGE_STEP),
                vPerCrossMph = dv(sX) / CROSS_STEP, hPerCrossMph = dh(sX) / CROSS_STEP,
                vPerHeadMph = dv(sH) / HEAD_STEP, hPerHeadMph = dh(sH) / HEAD_STEP,
            )
        }
    }

    private fun moaToMil(moa: Double) = moa / MOA_PER_MIL

    private fun rmsOverAngle(sdRad: Double, f: (Double) -> Double): Double {
        if (sdRad <= 0.0) return kotlin.math.abs(f(0.0))
        val n = 240
        var sw = 0.0; var s = 0.0
        for (j in 0..n) {
            val z = -6.0 + 12.0 * j / n
            val w = kotlin.math.exp(-z * z / 2) * (if (j == 0 || j == n) 0.5 else 1.0)
            val v = f(z * sdRad)
            sw += w; s += w * v * v
        }
        return sqrt(s / sw)
    }

    fun causes(s: Sensitivity, i: Inputs): List<Cause> {
        val k = s.rangeYd * IN_PER_MIL_PER_YD
        val wa = Math.toRadians(i.windFromDeg)
        fun windV(dCross: Double, dHead: Double) = s.vPerCrossMph * dCross + s.vPerHeadMph * dHead
        fun windH(dCross: Double, dHead: Double) = s.hPerCrossMph * dCross + s.hPerHeadMph * dHead
        val sw = i.windSpeedSdMph
        val speed = Cause(WIND_SPEED, kotlin.math.abs(windV(sw * sin(wa), sw * cos(wa))) * k,
            kotlin.math.abs(windH(sw * sin(wa), sw * cos(wa))) * k)
        fun dCross(d: Double) = i.windMph * (sin(wa + d) - sin(wa))
        fun dHead(d: Double) = i.windMph * (cos(wa + d) - cos(wa))
        val sd = Math.toRadians(i.windDirSdDeg)
        val dir = Cause(WIND_DIRECTION, rmsOverAngle(sd) { windV(dCross(it), dHead(it)) } * k,
            rmsOverAngle(sd) { windH(dCross(it), dHead(it)) } * k)
        val group = moaToMil(i.groupMoa / ES5_PER_SIGMA) * k
        val aim = moaToMil(i.aimSdMoa) * k
        val all = listOf(
            speed to (i.windSpeedSdMph > 0),
            dir to (i.windDirSdDeg > 0),
            Cause("Range", kotlin.math.abs(s.vPerYd) * i.rangeSdYd * k, kotlin.math.abs(s.hPerYd) * i.rangeSdYd * k) to (i.rangeSdYd > 0),
            Cause("Muzzle velocity", kotlin.math.abs(s.vPerFps) * i.mvSdFps * k, kotlin.math.abs(s.hPerFps) * i.mvSdFps * k) to (i.mvSdFps > 0),
            Cause("BC", kotlin.math.abs(s.vPerBcPct) * i.bcSdPct * k, kotlin.math.abs(s.hPerBcPct) * i.bcSdPct * k) to (i.bcSdPct > 0),
            Cause("Rifle & ammo group", group, group) to (i.groupMoa > 0),
            Cause("Aim", aim, aim) to (i.aimSdMoa > 0),
        ).filter { it.second }.map { it.first }
        val top = all.maxOfOrNull { it.totalIn } ?: 0.0
        val across = kotlin.math.abs(sin(wa))
        return all.map { c ->
            val small = c.totalIn < 0.5 * top
            when {
                c.name == WIND_DIRECTION && i.windMph < 0.5 ->
                    c.copy(note = "There is hardly any wind to call, so its direction can't be off by much. Gusts from any side belong in Wind speed ±.")
                c.name == WIND_DIRECTION && small && across > 0.94 ->
                    c.copy(note = "Small in a full-value wind (3 or 9 o'clock): turned a little, it still blows almost fully across, so the drift hardly changes. It matters more in a quartering wind.")
                c.name == WIND_SPEED && small && across < 0.34 ->
                    c.copy(note = "Small in a head or tail wind: a wrong speed hardly moves the bullet sideways. Wind direction matters more here.")
                else -> c
            }
        }.sortedByDescending { it.totalIn }
    }

    fun simulate(s: Sensitivity, i: Inputs, bulletDiaIn: Double = 0.0, seed: Long = 1L, keep: Int = 600): Result {
        val rnd = Random(seed)
        val k = s.rangeYd * IN_PER_MIL_PER_YD
        val wa = Math.toRadians(i.windFromDeg)
        val calledCross = i.windMph * sin(wa)
        val calledHead = i.windMph * cos(wa)
        val groupMil = moaToMil(i.groupMoa / ES5_PER_SIGMA)
        val aimMil = moaToMil(i.aimSdMoa)
        val hw = i.targetWidthIn / 2 + bulletDiaIn / 2
        val hh = i.targetHeightIn / 2 + bulletDiaIn / 2
        val n = i.shots.coerceIn(100, 200_000)
        var hits = 0
        val pts = ArrayList<Pair<Double, Double>>(minOf(keep, n))
        val flags = ArrayList<Boolean>(minOf(keep, n))
        var sx = 0.0; var sxx = 0.0; var sy = 0.0; var syy = 0.0
        repeat(n) { idx ->
            val dv = rnd.nextGaussian() * i.mvSdFps
            val dbc = rnd.nextGaussian() * i.bcSdPct
            val dr = rnd.nextGaussian() * i.rangeSdYd
            val w = i.windMph + rnd.nextGaussian() * i.windSpeedSdMph
            val a = wa + Math.toRadians(rnd.nextGaussian() * i.windDirSdDeg)
            val dCross = w * sin(a) - calledCross
            val dHead = w * cos(a) - calledHead
            val yMil = s.vPerFps * dv + s.vPerBcPct * dbc + s.vPerYd * dr + s.vPerCrossMph * dCross + s.vPerHeadMph * dHead +
                rnd.nextGaussian() * groupMil + rnd.nextGaussian() * aimMil
            val xMil = s.hPerFps * dv + s.hPerBcPct * dbc + s.hPerYd * dr + s.hPerCrossMph * dCross + s.hPerHeadMph * dHead +
                rnd.nextGaussian() * groupMil + rnd.nextGaussian() * aimMil
            val x = xMil * k; val y = yMil * k
            val hit = if (i.round) (x / hw) * (x / hw) + (y / hh) * (y / hh) <= 1.0 else kotlin.math.abs(x) <= hw && kotlin.math.abs(y) <= hh
            if (hit) hits++
            if (idx < keep) { pts += x to y; flags += hit }
            sx += x; sxx += x * x; sy += y; syy += y * y
        }
        fun sd(sum: Double, sq: Double) = sqrt(maxOf(0.0, sq / n - (sum / n) * (sum / n)))
        return Result(s.rangeYd, hits.toDouble() / n, hits, n, pts, flags, sd(sy, syy), sd(sx, sxx), causes(s, i))
    }

    fun chartRanges(rangeYd: Double): List<Double> {
        val top = (maxOf(rangeYd * 1.5, rangeYd + 300.0) / 100.0).let { kotlin.math.ceil(it) * 100.0 }.coerceAtMost(3000.0)
        val step = when { top <= 1000 -> 50.0; top <= 2000 -> 100.0; else -> 150.0 }
        val out = mutableListOf<Double>()
        var r = 100.0
        while (r <= top + 1e-9) { out += r; r += step }
        if (out.none { kotlin.math.abs(it - rangeYd) < 1e-6 } && rangeYd > RANGE_STEP) out += rangeYd
        return out.sorted()
    }

    data class Report(val main: Result, val chart: List<Pair<Double, Double>>)

    fun run(rifle: Rifle, load: Load, bullet: Bullet, shot: ShotInputs, i: Inputs): Report {
        val ranges = chartRanges(i.rangeYd)
        val sens = sensitivities(rifle, load, bullet, shot, i.windMph, i.windFromDeg, ranges)
        val main = simulate(sens.first { kotlin.math.abs(it.rangeYd - i.rangeYd) < 1e-6 }, i, bullet.diameterIn, keep = 600)
        val chart = sens.map { s -> s.rangeYd to simulate(s, i.copy(shots = 4000), bullet.diameterIn, keep = 0).probability }
        return Report(main, chart)
    }
}
