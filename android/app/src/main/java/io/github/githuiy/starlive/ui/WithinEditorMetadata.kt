package io.github.githuiy.starlive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.data.WithinCategory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val WithinMetadataDateFormat = DateTimeFormatter.ofPattern("uuuu.MM.dd", Locale.ROOT)

private enum class WithinMetadataDialog { DATE, CATEGORIES }

@Composable
internal fun WithinEditorMetadata(
    date: LocalDate?,
    categories: List<WithinCategory>,
    today: LocalDate,
    allowMissingDate: Boolean,
    enabled: Boolean,
    onDateChange: (LocalDate?) -> Unit,
    onCategoriesChange: (List<WithinCategory>) -> Unit,
    modifier: Modifier = Modifier,
    showRow: Boolean = true,
) {
    var dialog by rememberSaveable { mutableStateOf<WithinMetadataDialog?>(null) }
    val dateText = date?.format(WithinMetadataDateFormat) ?: "日期未记录"
    val categoryText = WithinCategory.entries.filter { it in categories }
        .joinToString(" · ") { it.label }.ifEmpty { "未分类" }
    val dateButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        WithinMetadataButton(dateText, enabled, { dialog = WithinMetadataDialog.DATE },
            buttonModifier.testTag("within_editor_open_date"))
    }
    val categoryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        WithinMetadataButton(categoryText, enabled, { dialog = WithinMetadataDialog.CATEGORIES },
            buttonModifier.testTag("within_editor_open_categories"), selected = categories.isNotEmpty())
    }

    if (showRow) BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < (224 * LocalDensity.current.fontScale).dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                dateButton(Modifier.fillMaxWidth())
                categoryButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                dateButton(Modifier.weight(1f, fill = false))
                Text("·", Modifier.clearAndSetSemantics {}, color = Muted)
                categoryButton(Modifier.weight(1f, fill = false))
            }
        }
    }

    when (dialog) {
        WithinMetadataDialog.DATE -> WithinDateChoice(date, today, allowMissingDate,
            onDismiss = { dialog = null }, onApply = { selection ->
                onDateChange(selection)
                dialog = null
            })
        WithinMetadataDialog.CATEGORIES -> WithinCategoryChoice(categories,
            onDismiss = { dialog = null }, onApply = { selection ->
                onCategoriesChange(selection)
                dialog = null
            })
        null -> Unit
    }
}

@Composable
private fun WithinMetadataButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier,
    selected: Boolean = false) {
    val color = if (enabled && selected) Gold else Muted
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Row(
            Modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f, fill = false), color = color, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾", Modifier.clearAndSetSemantics {}, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WithinDateChoice(
    date: LocalDate?, today: LocalDate, allowMissingDate: Boolean,
    onDismiss: () -> Unit, onApply: (LocalDate?) -> Unit,
) {
    var entry by rememberSaveable(date) { mutableStateOf(date?.toString().orEmpty()) }
    var invalidDate by rememberSaveable(date) { mutableStateOf(false) }
    val applySelection: () -> Unit = apply@{
        val parsed = try {
            if (allowMissingDate && entry.isBlank()) null else LocalDate.parse(entry.trim())
        } catch (_: DateTimeParseException) {
            invalidDate = true
            return@apply
        }
        onApply(parsed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("within_editor_date_dialog").semantics { testTagsAsResourceId = true },
        containerColor = Night,
        titleContentColor = Ink,
        textContentColor = Ink,
        title = { Text("记录日期") },
        text = {
            OutlinedTextField(
                value = entry,
                onValueChange = {
                    entry = it
                    invalidDate = false
                },
                label = { Text("年-月-日") },
                supportingText = {
                    if (invalidDate) {
                        Text("请填写有效日期，例如 $today。",
                            Modifier.testTag("within_editor_date_error").semantics { liveRegion = LiveRegionMode.Polite })
                    } else if (allowMissingDate) {
                        Text("可留空")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { applySelection() }),
                singleLine = true,
                isError = invalidDate,
                modifier = Modifier.fillMaxWidth().testTag("within_editor_date"),
            )
        },
        confirmButton = {
            TextButton(onClick = applySelection, modifier = Modifier.testTag("within_editor_apply_date")) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun WithinCategoryChoice(
    categories: List<WithinCategory>,
    onDismiss: () -> Unit,
    onApply: (List<WithinCategory>) -> Unit,
) {
    var selected by rememberSaveable(categories) { mutableStateOf(categories.toList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("within_editor_categories_dialog").semantics { testTagsAsResourceId = true },
        containerColor = Night,
        titleContentColor = Ink,
        textContentColor = Ink,
        title = { Text("随笔分类") },
        text = {
            FlowRow(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WithinCategory.entries.forEach { category ->
                    val checked = category in selected
                    FilterChip(
                        selected = checked,
                        onClick = { selected = if (checked) selected - category else selected + category },
                        label = { Text(category.label) },
                        leadingIcon = if (checked) {
                            { Text("✓", Modifier.clearAndSetSemantics {}, color = Gold) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(labelColor = Ink,
                            selectedContainerColor = Gold.copy(alpha = .18f), selectedLabelColor = Gold),
                        modifier = Modifier.testTag("within_editor_category_${category.name}"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(WithinCategory.entries.filter { it in selected }) },
                modifier = Modifier.testTag("within_editor_apply_categories")) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
