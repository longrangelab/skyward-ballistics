package com.longrange.dragcurve.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Icon
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.ProfileStore
import java.awt.Dimension
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date

const val APP_NAME = "Skyward Ballistics"
const val APP_VERSION = "1.4.0"

enum class Screen(val title: String, val icon: ImageVector) {
    CALC("Calculator", Icons.Filled.Home),
    GEAR("My gear", Icons.Filled.Build),
    LIBRARY("Bullet library", Icons.AutoMirrored.Filled.List),
    ANALYSIS("Analysis", Icons.Filled.Star),
    ROUNDS("Round count", Icons.Filled.DateRange),
    BACKUP("Backup & move data", Icons.Filled.Refresh),
    SETTINGS("Settings", Icons.Filled.Settings),
}

class AppState(val data: DesktopData) {
    val store: ProfileStore get() = data.store
    var rev by mutableStateOf(0)
    var settings by mutableStateOf(data.store.settings())
    var screen by mutableStateOf(System.getProperty("skyward.screen")?.let { s -> Screen.entries.firstOrNull { it.name == s } } ?: Screen.CALC)
    var message by mutableStateOf<Message?>(null)
    var question by mutableStateOf<Question?>(null)
    var newAmmoBullet by mutableStateOf<String?>(null)

    fun changed() { rev++ }
    fun saveSettings(s: AppSettings) { data.store.saveSettings(s); settings = s }
    fun tell(title: String, text: String) { message = Message(title, text) }
    fun ask(title: String, text: String, yes: String, onYes: () -> Unit) { question = Question(title, text, yes, onYes) }
}

private val Navy = Color(0xFF01274A)
private val Accent = Color(0xFF006E94)

private val LightColors = lightColorScheme(
    primary = Navy, onPrimary = Color.White, primaryContainer = Color(0xFFD3E4F5), onPrimaryContainer = Navy,
    secondary = Accent, onSecondary = Color.White, secondaryContainer = Color(0xFFCDE8F3), onSecondaryContainer = Color(0xFF00334A),
    tertiary = Color(0xFF2E7D32),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FCAFF), onPrimary = Color(0xFF003258), primaryContainer = Color(0xFF0B3A63), onPrimaryContainer = Color(0xFFD3E4F5),
    secondary = Color(0xFF7FD0F2), onSecondary = Color(0xFF00344A), secondaryContainer = Color(0xFF004D6B),
    tertiary = Color(0xFF81C784),
)

@Composable
fun SkywardTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

private fun loadIcon(): ImageBitmap? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("skyward.png")?.use { it.readBytes() } ?: return null
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

private fun installErrorLog(base: File) {
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        val sw = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
        System.err.println("Unexpected error on ${t.name}: $sw")
        runCatching {
            base.mkdirs()
            File(base, "error.log").appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())} $APP_NAME $APP_VERSION on ${t.name}\n$sw\n", Charsets.UTF_8)
        }
    }
}

fun main() {
    val base = AppPaths.baseDir()
    installErrorLog(base)
    println("$APP_NAME $APP_VERSION starting. Data folder: ${base.absolutePath}")
    val data = runCatching { DesktopData(base) }
    data.exceptionOrNull()?.let { System.err.println("Can't open the data folder: $it") }
    val icon = loadIcon()
    application {
        val windowState = rememberWindowState(size = DpSize(1300.dp, 880.dp), position = WindowPosition(Alignment.Center))
        Window(onCloseRequest = ::exitApplication, title = APP_NAME, state = windowState, icon = icon?.let { BitmapPainter(it) }) {
            FilePicker.parent = window
            window.minimumSize = Dimension(1024, 700)
            val d = data.getOrNull()
            if (d == null) {
                SkywardTheme("system") {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Skyward Ballistics can't open its data folder", style = MaterialTheme.typography.headlineSmall)
                            Text(base.absolutePath)
                            Text(data.exceptionOrNull()?.message ?: "")
                        }
                    }
                }
            } else {
                val state = remember { AppState(d) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (d.sharedSync.path != null && d.sharedSync.auto) {
                        val o = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { d.syncShared(automatic = true) }
                        state.changed()
                        if (o !is com.longrange.dragcurve.sync.SharedFileSync.Outcome.Done)
                            state.tell("Sync at start didn't finish", (d.sharedSync.lastResult ?: o.text) + "\n\nOpen Backup & move data and press Sync.")
                    }
                }
                App(state, icon)
            }
        }
    }
}

@Composable
fun App(st: AppState, icon: ImageBitmap?) {
    SkywardTheme(st.settings.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                PermanentDrawerSheet(Modifier.width(230.dp).fillMaxHeight(), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) Image(BitmapPainter(icon), null, Modifier.size(40.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Skyward", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Ballistics", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Screen.entries.forEach { sc ->
                        NavigationDrawerItem(
                            label = { Text(sc.title) },
                            icon = { Icon(sc.icon, null) },
                            selected = st.screen == sc,
                            onClick = { st.screen = sc },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Note("Version $APP_VERSION", Modifier.padding(16.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp, vertical = 16.dp)) {
                    when (st.screen) {
                        Screen.CALC -> CalculatorScreen(st)
                        Screen.GEAR -> GearScreen(st)
                        Screen.LIBRARY -> LibraryScreen(st)
                        Screen.ANALYSIS -> AnalysisScreen(st)
                        Screen.ROUNDS -> RoundsScreen(st)
                        Screen.BACKUP -> BackupScreen(st)
                        Screen.SETTINGS -> SettingsScreen(st)
                    }
                }
            }
            MessageDialog(st.message) { st.message = null }
            QuestionDialog(st.question) { st.question = null }
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))
