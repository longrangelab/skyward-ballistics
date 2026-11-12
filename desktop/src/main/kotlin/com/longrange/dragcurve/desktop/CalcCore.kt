package com.longrange.dragcurve.desktop

import com.longrange.dragcurve.engine.Engine
import com.longrange.dragcurve.engine.Row
import com.longrange.dragcurve.engine.Solution
import com.longrange.dragcurve.engine.clicks
import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import com.longrange.dragcurve.ui.calc.Adjust
import com.longrange.dragcurve.ui.calc.CalcGuard
import com.longrange.dragcurve.ui.calc.Num
import com.longrange.dragcurve.ui.calc.Quantity
import com.longrange.dragcurve.ui.calc.Units
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class Fmt(val s: AppSettings) {
    fun disp(q: Quantity, v: Double) = Units.toDisplay(q, v, s)
    fun model(q: Quantity, v: Double) = Units.fromDisplay(q, v, s)
    fun label(q: Quantity) = Units.label(q, s)
    fun num(q: Quantity, v: Double, decimals: Int = Units.decimals(q, s)) = Num.trim(disp(q, v), decimals)
    fun withUnit(q: Quantity, v: Double, decimals: Int = Units.decimals(q, s)): String =
        num(q, v, decimals) + (label(q).let { if (it.isEmpty()) "" else if (it == "%" || it == "°") it else " $it" })
}

data class Dial(val value: String, val unit: String, val clicks: String)

data class CalcResult(
    val solution: Solution,
    val row: Row,
    val elevation: Dial,
    val windage: Dial,
)

object CalcCore {
    fun clockToDeg(hour: Double): Double = ((hour % 12.0) * 30.0).let { if (it < 0) it + 360 else it }

    fun degToClock(deg: Double): Int {
        val h = Math.rint((((deg % 360) + 360) % 360) / 30.0).toInt() % 12
        return if (h == 0) 12 else h
    }

    fun unitName(u: AngleUnit): String = when (u) {
        AngleUnit.MIL -> "MIL"
        AngleUnit.MOA -> "MOA"
        AngleUnit.IPHY -> "Inches per 100 yd"
    }

    fun elevation(row: Row, r: Rifle, s: AppSettings): Dial = Dial(
        Adjust.text(Adjust.dial(row.comeUpMil, r.elevationUnit, r.elevationCorrection), Adjust.Axis.ELEVATION, s.outputPrecision, r.elevationClick, s.usePlusMinus),
        Adjust.unitLabel(r.elevationUnit),
        Adjust.clicksText(clicks(row.comeUpMil, r.elevationUnit, r.elevationClick, r.elevationCorrection), Adjust.Axis.ELEVATION, s.usePlusMinus),
    )

    fun windage(row: Row, r: Rifle, s: AppSettings): Dial = Dial(
        Adjust.text(Adjust.dial(row.windageMil, r.windageUnit, r.windageCorrection), Adjust.Axis.WINDAGE, s.outputPrecision, r.windageClick, s.usePlusMinus),
        Adjust.unitLabel(r.windageUnit),
        Adjust.clicksText(clicks(row.windageMil, r.windageUnit, r.windageClick, r.windageCorrection), Adjust.Axis.WINDAGE, s.usePlusMinus),
    )

    fun tableRange(s: AppSettings): Pair<Double, Double> {
        val max = s.tableMaxYd.coerceIn(50.0, 5000.0)
        var step = s.tableStepYd.coerceIn(1.0, 1000.0).coerceAtLeast(max / 400.0)
        if (s.distanceUnit == "m") step = Math.rint(step * Units.M_PER_YD).coerceAtLeast(1.0) / Units.M_PER_YD
        return step to max
    }

    fun solve(rifle: Rifle, load: Load, bullet: Bullet, shot: ShotInputs, s: AppSettings): CalcResult {
        CalcGuard.problem(rifle, load, bullet)?.let { throw IllegalArgumentException(it) }
        require(shot.distanceYd > 0) { "Enter a distance." }
        val (step, max) = tableRange(s)
        val sol = Engine.solve(rifle, load, bullet, shot, maxRangeYd = maxOf(max, shot.distanceYd), stepYd = step,
            extraRangesYd = listOf(shot.distanceYd, load.zeroRangeYd))
        val row = sol.rows.minBy { abs(it.rangeYd - shot.distanceYd) }
        return CalcResult(sol, row, elevation(row, rifle, s), windage(row, rifle, s))
    }

    fun tableRows(sol: Solution, s: AppSettings): List<Row> {
        val (step, max) = tableRange(s)
        return sol.rows.filter { r -> r.rangeYd > 0 && r.rangeYd <= max + 1e-6 && abs(r.rangeYd / step - Math.rint(r.rangeYd / step)) < 1e-6 }
    }

    fun withDistanceUnit(s: AppSettings, unit: String): AppSettings {
        if (s.distanceUnit == unit) return s
        val n = s.copy(distanceUnit = unit)
        fun keep(yd: Double): Double = Units.fromDisplay(Quantity.DISTANCE, Math.rint(Units.toDisplay(Quantity.DISTANCE, yd, s)), n)
        return n.copy(solutionStepYd = keep(s.solutionStepYd), tableStepYd = keep(s.tableStepYd), tableMaxYd = keep(s.tableMaxYd))
    }

    fun time(at: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(at))

    fun count(n: Int): String = String.format(Locale.US, "%,d", n)
    fun signed(n: Int): String = (if (n >= 0) "+" else "−") + count(abs(n))
}
