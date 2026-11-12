package com.longrange.dragcurve.ui.calc

import com.longrange.dragcurve.model.AppSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Quantity {
    DISTANCE,
    ALTITUDE,
    PRESSURE,
    TEMPERATURE,
    HUMIDITY,
    LENGTH,
    VELOCITY,
    ENERGY,
    SPEED,
    VEL_PER_DEG,
    ANGLE,
    BULLET_LENGTH,
    WEIGHT,
    PLAIN,
}

object Units {
    const val M_PER_YD = 0.9144
    const val M_PER_FT = 0.3048
    const val CM_PER_IN = 2.54
    const val HPA_PER_INHG = 33.8638866667
    const val J_PER_FTLB = 1.3558179483
    const val MS_PER_MPH = 0.44704

    fun metric(q: Quantity, s: AppSettings): Boolean = when (q) {
        Quantity.DISTANCE -> s.distanceUnit == "m"
        Quantity.ALTITUDE, Quantity.PRESSURE, Quantity.TEMPERATURE -> s.atmosphereUnits == "metric"
        Quantity.LENGTH, Quantity.VELOCITY, Quantity.ENERGY, Quantity.SPEED -> s.otherUnits == "metric"
        Quantity.VEL_PER_DEG -> s.otherUnits == "metric"
        else -> false
    }

    fun toDisplay(q: Quantity, v: Double, s: AppSettings): Double {
        if (!metric(q, s)) return v
        return when (q) {
            Quantity.DISTANCE -> v * M_PER_YD
            Quantity.ALTITUDE -> v * M_PER_FT
            Quantity.PRESSURE -> v * HPA_PER_INHG
            Quantity.TEMPERATURE -> (v - 32.0) * 5.0 / 9.0
            Quantity.LENGTH -> v * CM_PER_IN
            Quantity.VELOCITY -> v * M_PER_FT
            Quantity.ENERGY -> v * J_PER_FTLB
            Quantity.SPEED -> v * MS_PER_MPH
            Quantity.VEL_PER_DEG -> v * M_PER_FT * 1.8
            else -> v
        }
    }

    fun fromDisplay(q: Quantity, v: Double, s: AppSettings): Double {
        if (!metric(q, s)) return v
        return when (q) {
            Quantity.DISTANCE -> v / M_PER_YD
            Quantity.ALTITUDE -> v / M_PER_FT
            Quantity.PRESSURE -> v / HPA_PER_INHG
            Quantity.TEMPERATURE -> v * 9.0 / 5.0 + 32.0
            Quantity.LENGTH -> v / CM_PER_IN
            Quantity.VELOCITY -> v / M_PER_FT
            Quantity.ENERGY -> v / J_PER_FTLB
            Quantity.SPEED -> v / MS_PER_MPH
            Quantity.VEL_PER_DEG -> v / (M_PER_FT * 1.8)
            else -> v
        }
    }

    fun label(q: Quantity, s: AppSettings): String {
        val m = metric(q, s)
        return when (q) {
            Quantity.DISTANCE -> if (m) "m" else "yd"
            Quantity.ALTITUDE -> if (m) "m" else "ft"
            Quantity.PRESSURE -> if (m) "hPa" else "inHg"
            Quantity.TEMPERATURE -> if (m) "°C" else "°F"
            Quantity.HUMIDITY -> "%"
            Quantity.LENGTH -> if (m) "cm" else "in"
            Quantity.VELOCITY -> if (m) "m/s" else "fps"
            Quantity.ENERGY -> if (m) "J" else "ft-lb"
            Quantity.SPEED -> if (m) "m/s" else "mph"
            Quantity.VEL_PER_DEG -> if (m) "m/s/°C" else "fps/°F"
            Quantity.ANGLE -> "°"
            Quantity.BULLET_LENGTH -> "in"
            Quantity.WEIGHT -> "gr"
            Quantity.PLAIN -> ""
        }
    }

    fun decimals(q: Quantity, s: AppSettings): Int {
        val m = metric(q, s)
        return when (q) {
            Quantity.DISTANCE -> 0
            Quantity.ALTITUDE -> 0
            Quantity.PRESSURE -> if (m) 1 else 2
            Quantity.TEMPERATURE -> if (m) 1 else 0
            Quantity.HUMIDITY -> 0
            Quantity.LENGTH -> 2
            Quantity.VELOCITY -> if (m) 1 else 0
            Quantity.ENERGY -> 0
            Quantity.SPEED -> 1
            Quantity.VEL_PER_DEG -> 2
            Quantity.ANGLE -> 1
            Quantity.BULLET_LENGTH -> 3
            Quantity.WEIGHT -> 1
            Quantity.PLAIN -> 3
        }
    }
}

object Num {
    fun trim(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val f = 10.0.pow(decimals)
        val r = (v * f).roundToLong() / f
        val s = String.format(Locale.US, "%.${decimals.coerceAtLeast(0)}f", if (r == 0.0) 0.0 else r)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    fun fixed(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val f = 10.0.pow(decimals)
        val r = (v * f).roundToLong() / f
        return String.format(Locale.US, "%.${decimals.coerceAtLeast(0)}f", if (r == 0.0) 0.0 else r)
    }

    fun parse(text: CharSequence?): Double? {
        val t = text?.toString()?.trim()?.replace(',', '.') ?: return null
        if (t.isEmpty() || t == "-" || t == "+" || t == ".") return null
        return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    fun decimalsOf(v: Double): Int {
        for (d in 0..4) {
            val f = 10.0.pow(d)
            if (abs(v * f - Math.rint(v * f)) < 1e-6) return d
        }
        return 4
    }
}
