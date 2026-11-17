package io.github.githuiy.starlive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.githuiy.starlive.data.WithinCategory
import io.github.githuiy.starlive.data.WithinEssay
import io.github.githuiy.starlive.data.WithinEssayDraft
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun WithinEssayEditor(
    initial: WithinEssay?,
    defaultCategory: WithinCategory?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: suspend (WithinEssayDraft) -> Unit,
) {
    val original = initial?.draft
    val originalBody = original?.body.orEmpty()
    val originalCategories = original?.categories ?: setOfNotNull(defaultCategory)
    val originalDate = rememberSaveable(initial?.id) {
        if (initial == null) today.toString() else original?.date?.toString().orEmpty()
    }
    val allowMissingDate = initial != null && original?.date == null
    var body by rememberSaveable(initial?.id) { mutableStateOf(originalBody) }
    var date by rememberSaveable(initial?.id) { mutableStateOf(originalDate) }
    var categories by rememberSaveable(initial?.id, defaultCategory) { mutableStateOf(originalCategories.toList()) }
    var dateError by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var discard by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val dirty = body != originalBody || date != originalDate || categories.toSet() != originalCategories

    fun requestDismiss() {
        if (saving) return
        if (dirty) discard = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, decorFitsSystemWindows = false),
        modifier = Modifier.safeDrawingPadding().testTag("within_editor")
            .semantics { testTagsAsResourceId = true },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (initial == null) "写随笔" else "编辑随笔")
                if (saveFailed) WithinWriteError("未能保存随笔。可以重试。")
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = body,
                    onValueChange = {
                        body = it
                        saveFailed = false
                    },
                    label = { Text("正文") },
                    minLines = 3,
                    maxLines = 8,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("within_editor_body"),
                )
                Column {
                    Text("分类（可多选）", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WithinCategory.entries.forEach { category ->
                            val selected = category in categories
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    categories = if (selected) categories - category else categories + category
                                    saveFailed = false
                                },
                                label = { Text(category.label) },
                                enabled = !saving,
                                modifier = Modifier.testTag("within_editor_category_${category.name}"),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = date,
                    onValueChange = {
                        date = it
                        dateError = false
                        saveFailed = false
                    },
                    label = { Text("记录日期") },
                    supportingText = {
                        if (dateError) {
                            Text(
                                "请填写有效日期，例如 $today。",
                                modifier = Modifier.testTag("within_editor_date_error")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        } else {
                            Text("年-月-日")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    singleLine = true,
                    enabled = !saving,
                    isError = dateError,
                    modifier = Modifier.fillMaxWidth().testTag("within_editor_date"),
                )
            }
        },
        dismissButton = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            TextButton(
                onClick = {
                    focus.clearFocus()
                    keyboard?.hide()
                    requestDismiss()
                },
                enabled = !saving,
                modifier = Modifier.testTag("within_editor_cancel"),
            ) { Text("取消") }
        },
        confirmButton = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            Button(
                enabled = body.isNotBlank() && !saving,
                modifier = Modifier.testTag("within_editor_save"),
                onClick = save@{
                    val recordedOn = try {
                        if (allowMissingDate && date.isBlank()) null else LocalDate.parse(date.trim())
                    } catch (_: DateTimeParseException) {
                        dateError = true
                        scope.launch { scroll.animateScrollTo(scroll.maxValue) }
                        return@save
                    }
                    val draft = WithinEssayDraft(body = body, date = recordedOn, categories = categories.toSet())
                    focus.clearFocus()
                    keyboard?.hide()
                    saving = true
                    saveFailed = false
                    scope.launch {
                        try {
                            onSave(draft)
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) "保存中" else "保存") }
        },
    )

    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("放弃未保存的内容？") },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("within_editor_discard")) {
                    Text("放弃")
                }
            },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WithinDeleteDialog(onDismiss: () -> Unit, onDelete: suspend () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("删除这篇随笔？") },
        text = {
            if (deleteFailed) WithinWriteError("未能删除随笔。可以重试。")
            else Text("这篇随笔会从全部随笔和相关分类中移除。")
        },
        dismissButton = {
            TextButton(
                enabled = !deleting,
                onClick = onDismiss,
                modifier = Modifier.testTag("within_delete_cancel"),
            ) { Text("保留") }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                modifier = Modifier.testTag("within_delete_confirm"),
                onClick = {
                    deleting = true
                    deleteFailed = false
                    scope.launch {
                        try {
                            onDelete()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            deleteFailed = true
                        } finally {
                            deleting = false
                        }
                    }
                },
            ) { Text(if (deleting) "删除中" else "删除", color = MaterialTheme.colorScheme.error) }
        },
    )
}

@Composable
private fun WithinWriteError(message: String) {
    Text(
        message,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
