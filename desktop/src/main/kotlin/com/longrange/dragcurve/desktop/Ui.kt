package com.longrange.dragcurve.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.longrange.dragcurve.ui.calc.Num
import com.longrange.dragcurve.ui.calc.Quantity
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.abs

@Composable
fun NumberField(
    label: String,
    value: Double?,
    onValue: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    fmt: Fmt? = null,
    quantity: Quantity? = null,
    unit: String? = null,
    decimals: Int? = null,
    error: String? = null,
    help: String? = null,
) {
    val shownUnit = unit ?: if (fmt != null && quantity != null) fmt.label(quantity) else ""
    val dec = decimals ?: if (fmt != null && quantity != null) com.longrange.dragcurve.ui.calc.Units.decimals(quantity, fmt.s).coerceAtLeast(1) + 1 else 4
    fun toShown(v: Double) = if (fmt != null && quantity != null) fmt.disp(quantity, v) else v
    fun toModel(v: Double) = if (fmt != null && quantity != null) fmt.model(quantity, v) else v
    fun text(v: Double?) = v?.let { Num.trim(toShown(it), dec) } ?: ""
    var t by remember(shownUnit) { mutableStateOf(text(value)) }
    LaunchedEffect(value, shownUnit) {
        val cur = Num.parse(t)?.let { toModel(it) }
        if (value == null) return@LaunchedEffect
        if (cur == null || abs(cur - value) > 1e-9 * maxOf(1.0, abs(value))) t = text(value)
    }
    OutlinedTextField(
        value = t,
        onValueChange = { nt -> t = nt; onValue(Num.parse(nt)?.let { toModel(it) }) },
        label = { Text(label) },
        suffix = if (shownUnit.isNotEmpty()) ({ Text(shownUnit) }) else null,
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: help)?.let { msg -> { Text(msg) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun TextBox(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, singleLine = singleLine,
        isError = error != null, supportingText = error?.let { { Text(it) } }, modifier = modifier,
    )
}

@Composable
fun <T> Dropdown(
    label: String,
    selected: T?,
    options: List<T>,
    text: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected?.let(text) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().padding(top = 8.dp).clickable(enabled = enabled && options.isNotEmpty()) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun <T> Choice(options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, vertical: Boolean = false) {
    val item: @Composable (T) -> Unit = { o ->
        Row(Modifier.clickable { onSelect(o) }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = o == selected, onClick = { onSelect(o) })
            Text(text(o))
        }
    }
    if (vertical) Column { options.forEach { item(it) } }
    else Row(verticalAlignment = Alignment.CenterVertically) { options.forEach { item(it) } }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun FieldRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top, content = content)
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Note(it) }
    }
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

data class Message(val title: String, val text: String)

@Composable
fun MessageDialog(m: Message?, onClose: () -> Unit) {
    if (m == null) return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.title) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { Text(m.text) } },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        modifier = Modifier.width(560.dp),
    )
}

data class Question(val title: String, val text: String, val yes: String, val onYes: () -> Unit)

@Composable
fun QuestionDialog(q: Question?, onClose: () -> Unit) {
    if (q == null) return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(q.title) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { Text(q.text) } },
        confirmButton = { TextButton(onClick = { onClose(); q.onYes() }) { Text(q.yes) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        modifier = Modifier.width(560.dp),
    )
}

object FilePicker {
    var parent: Frame? = null
    private var lastDir: String? = null

    fun open(title: String, multiple: Boolean = false): List<File> {
        val d = FileDialog(parent, title, FileDialog.LOAD)
        d.isMultipleMode = multiple
        lastDir?.let { d.directory = it }
        d.isVisible = true
        val picked = d.files?.toList().orEmpty()
        if (picked.isNotEmpty()) lastDir = picked.first().absoluteFile.parent
        return picked
    }

    fun save(title: String, suggestedName: String): File? {
        val d = FileDialog(parent, title, FileDialog.SAVE)
        lastDir?.let { d.directory = it } ?: run { d.directory = System.getProperty("user.home") + File.separator + "Documents" }
        d.file = suggestedName
        d.isVisible = true
        val name = d.file ?: return null
        val dir = d.directory ?: return null
        lastDir = dir
        val f = File(dir, name)
        return if (f.name.contains('.')) f else File(dir, "$name.json")
    }
}
