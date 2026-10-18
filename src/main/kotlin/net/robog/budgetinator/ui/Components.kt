package net.robog.budgetinator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.robog.budgetinator.validation.Issue
import net.robog.budgetinator.validation.Severity

val Warn = Color(0xFFB26A00)

@Composable
fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** Text field that reports the first matching issue for [field] inline. */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    support: String? = null,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    prefix: String? = null,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            onChange(if (numeric) v.filter { it.isDigit() || it == '.' || it == ',' || it == '$' } else v)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: support)?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        prefix = prefix?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun <T> Dropdown(
    label: String,
    value: T?,
    options: List<T>,
    text: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value?.let(text).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().padding(bottom = if (error != null) 20.dp else 0.dp).clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun YesNo(label: String, value: Boolean?, onChange: (Boolean) -> Unit, error: String? = null, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(value == true, { onChange(true) }, { Text("Yes") })
            FilterChip(value == false, { onChange(false) }, { Text("No") })
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun IssueLine(i: Issue, modifier: Modifier = Modifier) {
    val isErr = i.severity == Severity.ERROR
    val color = if (isErr) MaterialTheme.colorScheme.error else Warn
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (isErr) Icons.Default.ErrorOutline else Icons.Default.WarningAmber, null, tint = color, modifier = Modifier.size(18.dp))
        Text(i.message, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Callout(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) { Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
}

fun money(v: java.math.BigDecimal): String = "$" + String.format("%,.2f", v)
