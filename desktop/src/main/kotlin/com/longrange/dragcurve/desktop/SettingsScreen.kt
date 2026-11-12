package com.longrange.dragcurve.desktop

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(st: AppState) {
    val s = st.settings
    ScrollColumn(Modifier.fillMaxHeight().widthIn(max = 760.dp)) {
        ScreenTitle("Settings", "Saved on this computer. They are not part of backup files, so the phone keeps its own.")
        Section("Units") {
            Note("Distances")
            Choice(listOf("yd", "m"), s.distanceUnit, { if (it == "yd") "Yards" else "Metres" }, { st.saveSettings(CalcCore.withDistanceUnit(s, it)) })
            Note("Weather (temperature, pressure, altitude)")
            Choice(listOf("imperial", "metric"), s.atmosphereUnits, { if (it == "imperial") "°F, inHg, feet" else "°C, hPa, metres" }, { st.saveSettings(s.copy(atmosphereUnits = it)) })
            Note("Everything else (velocity, energy, wind, sizes)")
            Choice(listOf("imperial", "metric"), s.otherUnits, { if (it == "imperial") "fps, ft-lb, mph, inches" else "m/s, joules, cm" }, { st.saveSettings(s.copy(otherUnits = it)) })
        }
        Section("How the answer is shown") {
            Choice(listOf("tenth", "hundredth", "click"), s.outputPrecision,
                { when (it) { "tenth" -> "0.1"; "hundredth" -> "0.01"; else -> "Whole clicks" } }, { st.saveSettings(s.copy(outputPrecision = it)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(s.usePlusMinus, { st.saveSettings(s.copy(usePlusMinus = it)) })
                Text("Show + and − instead of U/D (up/down) and L/R (left/right)")
            }
        }
        Section("Colours") {
            Choice(listOf("system", "light", "dark"), s.theme, { when (it) { "system" -> "Like Windows"; "light" -> "Light"; else -> "Dark" } },
                { st.saveSettings(s.copy(theme = it)) })
        }
        Section("About") {
            Note("$APP_NAME $APP_VERSION for Windows. Same calculations and files as the Android app.")
            Note("Data folder: ${st.data.baseDir.absolutePath}")
        }
    }
}
