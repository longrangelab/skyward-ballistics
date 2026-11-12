package com.longrange.dragcurve.engine

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import kotlin.math.abs
import kotlin.math.sqrt

object Truing {
    data class Impact(val rangeYd: Double, val actualComeUpMil: Double)

    data class Result(
        val value: Double,
        val predictedBefore: List<Double>,
        val predictedAfter: List<Double>,
        val rmsBeforeMil: Double,
        val rmsAfterMil: Double,
    )

    fun muzzleVelocity(rifle: Rifle, load: Load, bullet: Bullet, shot: ShotInputs, impacts: List<Impact>): Result {
        val t = shot.atmosphere.temperatureF
        val mv0 = Engine.muzzleVelocity(load, t)
        fun loadFor(v: Double) = load.copy(muzzleVelocityFps = v - load.velocityPerDegFps * (t - load.powderTempF))
        return fit(mv0, mv0 * 0.80, mv0 * 1.20, impacts) { v -> comeUps(rifle, loadFor(v), bullet, shot, impacts) }
    }

    fun dragScale(rifle: Rifle, load: Load, bullet: Bullet, shot: ShotInputs, impacts: List<Impact>): Result {
        val s0 = load.dragScaleFactor
        return fit(s0, s0 * 0.70, s0 * 1.30, impacts) { s -> comeUps(rifle, load.copy(dragScaleFactor = s), bullet, shot, impacts) }
    }

    private fun comeUps(rifle: Rifle, load: Load, bullet: Bullet, shot: ShotInputs, impacts: List<Impact>): List<Double> {
        val ranges = impacts.map { it.rangeYd }
        val sol = Engine.solve(rifle, load, bullet, shot.copy(distanceYd = ranges.max()),
            maxRangeYd = ranges.max(), stepYd = ranges.max(), extraRangesYd = ranges)
        return ranges.map { r -> sol.rows.first { abs(it.rangeYd - r) < 1e-6 }.comeUpMil }
    }

    private fun rms(pred: List<Double>, impacts: List<Impact>) =
        sqrt(pred.zip(impacts).sumOf { (p, i) -> (p - i.actualComeUpMil).let { it * it } } / impacts.size)

    private fun fit(start: Double, lo0: Double, hi0: Double, impacts: List<Impact>, model: (Double) -> List<Double>): Result {
        require(impacts.isNotEmpty()) { "enter at least one impact" }
        require(impacts.all { it.rangeYd > 0 }) { "impact distances must be above 0" }
        val f = { x: Double -> rms(model(x), impacts) }
        val g = (sqrt(5.0) - 1) / 2
        var lo = lo0; var hi = hi0
        var c = hi - g * (hi - lo); var d = lo + g * (hi - lo)
        var fc = f(c); var fd = f(d)
        repeat(40) {
            if (fc < fd) { hi = d; d = c; fd = fc; c = hi - g * (hi - lo); fc = f(c) }
            else { lo = c; c = d; fc = fd; d = lo + g * (hi - lo); fd = f(d) }
        }
        val best = (lo + hi) / 2
        val before = model(start); val after = model(best)
        return Result(best, before, after, rms(before, impacts), rms(after, impacts))
    }
}
