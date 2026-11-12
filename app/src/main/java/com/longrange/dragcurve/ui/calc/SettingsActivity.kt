package com.longrange.dragcurve.ui.calc

import android.os.Bundle
import android.widget.ScrollView
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.AppSettings

class SettingsActivity : CalcActivity() {
    private lateinit var form: Form

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(R.string.calc_settings))
        form = contentForm()
        render()
    }

    private fun save(s: AppSettings, rerender: Boolean = false) {
        store.saveSettings(s)
        if (rerender) render()
    }

    private fun render() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val y = scroll.scrollY
        val s = settings
        form.settings = s
        form.clear()

        form.header("Display")
        form.group {
            choice("Text size", TextScale.LABELS, TextScale.index(this@SettingsActivity),
                sub = "Makes all text in the app bigger, on top of your phone's own font size") { i ->
                if (i != TextScale.index(this@SettingsActivity)) { TextScale.set(this@SettingsActivity, i); recreate() }
            }
            choice("Motion", MotionPref.LABELS, if (MotionPref.reduced(this@SettingsActivity)) 1 else 0,
                sub = "Reduced: the logo stays still and changes happen at once. The phone's own \"Remove animations\" setting does the same") { i ->
                MotionPref.set(this@SettingsActivity, i == 1)
            }
            val themes = listOf("system", "light", "dark")
            choice("Colours", listOf("Same as phone", "Light (daylight)", "Dark (low light)"), themes.indexOf(s.theme).coerceAtLeast(0)) { i ->
                save(settings.copy(theme = themes[i]))
                applyTheme(themes[i])
            }
            switch("Keep screen on", s.keepScreenOn, sub = "The screen will not dim or lock while the app is open") { c ->
                save(settings.copy(keepScreenOn = c)); applyKeepScreenOn()
            }
        }

        form.header("Units")
        form.group {
            choice("Distance", listOf("Yards", "Meters"), if (s.distanceUnit == "m") 1 else 0) { i ->
                save(SettingsLogic.withDistanceUnit(settings, if (i == 1) "m" else "yd"), rerender = true)
            }
            choice("Weather", listOf("Imperial (ft, inHg, °F)", "Metric (m, hPa, °C)"), if (s.atmosphereUnits == "metric") 1 else 0) { i ->
                save(settings.copy(atmosphereUnits = if (i == 1) "metric" else "imperial"), rerender = true)
            }
            choice("Everything else", listOf("Imperial (in, fps, ft-lb, mph)", "Metric (cm, m/s, J, m/s)"), if (s.otherUnits == "metric") 1 else 0,
                sub = "Bullet path, speed, energy and wind") { i ->
                save(settings.copy(otherUnits = if (i == 1) "metric" else "imperial"), rerender = true)
            }
        }

        form.header("Entering a shot")
        form.group {
            switch("Remember my last shot", s.saveLastInputs, sub = "Brings back the distance, wind and weather you used last time") { c ->
                save(settings.copy(saveLastInputs = c))
            }
            switch("Enter density altitude", s.useDensityAltitude,
                sub = "Type one density-altitude number and the temperature, instead of altitude, pressure and humidity") { c ->
                save(settings.copy(useDensityAltitude = c))
            }
            number("Distance step", Quantity.DISTANCE, s.solutionStepYd, { v -> if (v != null && v > 0) save(settings.copy(solutionStepYd = v)) },
                decimals = 1, sub = "How far each tap of − or + moves the distance")
        }

        form.header("Showing the answer")
        form.group {
            switch("Show + and − instead of letters", s.usePlusMinus,
                sub = "Off: U / D for up and down, R / L for right and left. On: + and −") { c -> save(settings.copy(usePlusMinus = c)) }
            val precs = listOf("tenth", "hundredth", "click")
            choice("Round the answer to", listOf("One decimal (0.1)", "Two decimals (0.01)", "The nearest click"), precs.indexOf(s.outputPrecision).coerceAtLeast(0)) { i ->
                save(settings.copy(outputPrecision = precs[i]))
            }
        }

        form.header("Trajectory table")
        form.group {
            number("Row spacing", Quantity.DISTANCE, s.tableStepYd, { v -> if (v != null && v >= 1) save(settings.copy(tableStepYd = v)) }, decimals = 1,
                sub = "Distance between rows of the table")
            number("Furthest distance", Quantity.DISTANCE, s.tableMaxYd, { v -> if (v != null && v >= 50 && v <= 5000) save(settings.copy(tableMaxYd = v)) },
                sub = "Where the table stops")
            switch("Furthest distance first", s.tableSortDescending, sub = "Lists the table from far to near") { c -> save(settings.copy(tableSortDescending = c)) }
        }
        scroll.post { scroll.scrollTo(0, y) }
    }
}

object SettingsLogic {
    fun withDistanceUnit(s: AppSettings, unit: String): AppSettings {
        if (s.distanceUnit == unit) return s
        val n = s.copy(distanceUnit = unit)
        fun keep(yd: Double): Double = Units.fromDisplay(Quantity.DISTANCE, Math.rint(Units.toDisplay(Quantity.DISTANCE, yd, s)), n)
        return n.copy(solutionStepYd = keep(s.solutionStepYd), tableStepYd = keep(s.tableStepYd), tableMaxYd = keep(s.tableMaxYd))
    }
}
