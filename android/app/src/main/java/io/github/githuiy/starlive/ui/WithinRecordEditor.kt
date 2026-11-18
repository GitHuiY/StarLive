package io.github.githuiy.starlive.ui

import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.data.WithinCategory
import io.github.githuiy.starlive.data.WithinEssay
import io.github.githuiy.starlive.data.WithinEssayDraft
import io.github.githuiy.starlive.data.WithinImageAttachment
import io.github.githuiy.starlive.data.WithinTextStyle
import io.github.githuiy.starlive.data.MAX_WITHIN_IMAGES
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun WithinEssayEditor(
    initial: WithinEssay?,
    defaultCategory: WithinCategory?,
    today: LocalDate,
    onDismiss: () -> Unit,
    loadImage: suspend (WithinImageAttachment) -> ByteArray,
    importImage: suspend (Uri, Int) -> WithinImageAttachment,
    discardImages: suspend (List<WithinImageAttachment>) -> Unit,
    onSave: suspend (WithinEssayDraft) -> Unit,
) {
    val original = initial?.draft
    val originalBody = original?.body.orEmpty()
    val originalCategories = original?.categories ?: setOfNotNull(defaultCategory)
    val originalDate = rememberSaveable(initial?.id) {
        if (initial == null) today.toString() else original?.date?.toString().orEmpty()
    }
    val allowMissingDate = initial != null && original?.date == null
    var body by rememberSaveable(initial?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(originalBody))
    }
    var spans by rememberSaveable(initial?.id, stateSaver = withinSpanSaver) {
        mutableStateOf(original?.spans.orEmpty())
    }
    var images by rememberSaveable(initial?.id, stateSaver = withinImageSaver) {
        mutableStateOf(original?.images.orEmpty())
    }
    var pendingStyle by rememberSaveable(initial?.id, stateSaver = withinStyleSaver) {
        mutableStateOf(WithinTextStyle())
    }
    var date by rememberSaveable(initial?.id) { mutableStateOf(originalDate) }
    var categories by rememberSaveable(initial?.id, defaultCategory) { mutableStateOf(originalCategories.toList()) }
    var discard by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var importingImage by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = saving || importingImage
    val dirty = body.text != originalBody || spans != original?.spans.orEmpty() ||
        images != original?.images.orEmpty() || date != originalDate || categories.toSet() != originalCategories

    fun removeDraftImages(removed: List<WithinImageAttachment>, onDone: () -> Unit = {}) {
        val newlyAdded = removed.filter { image -> original?.images.orEmpty().none { it.id == image.id } }
        if (newlyAdded.isEmpty()) {
            onDone()
        } else {
            scope.launch {
                importingImage = true
                try {
                    discardImages(newlyAdded)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Cleanup is best effort and never discards a committed attachment.
                } finally {
                    importingImage = false
                }
                onDone()
            }
        }
    }

    fun requestDismiss() {
        if (busy) return
        if (dirty) discard = true else onDismiss()
    }

    fun addImage(uri: Uri) {
        if (busy || images.size >= MAX_WITHIN_IMAGES) return
        val position = body.selection.max
        importingImage = true
        imageError = null
        scope.launch {
            try {
                images = images + importImage(uri, position)
                saveFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                imageError = failure.message ?: "无法打开这张图片。"
            } finally {
                importingImage = false
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addImage(uri)
    }

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var tools by rememberSaveable { mutableStateOf<WithinEditorTools?>(null) }

    BackHandler {
        when {
            keyboardVisible -> keyboard?.hide()
            tools != null -> tools = null
            else -> requestDismiss()
        }
    }

    WithinDocumentPage(Modifier.testTag("within_editor")) {
        WithinDocumentHeader(if (initial == null) "写随笔" else "编辑随笔", "within_editor_cancel",
            onBack = {
                focus.clearFocus()
                keyboard?.hide()
                requestDismiss()
            }, backEnabled = !busy) {
            val canSave = (body.text.isNotBlank() || images.isNotEmpty()) && !busy
            TextButton(enabled = canSave, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("within_editor_save"),
                onClick = {
                    val recordedOn = date.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                    val draft = WithinEssayDraft(body = body.text, date = recordedOn, categories = categories.toSet(),
                        spans = spans, images = images)
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
                }) {
                Text(if (saving) "保存中" else "保存", color = if (canSave) Gold else Muted, maxLines = 1)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val stackedMetadata = maxWidth - 20.dp < (224 * LocalDensity.current.fontScale).dp
            val showMetadata = !keyboardVisible || maxHeight >= if (stackedMetadata) 224.dp else 172.dp
            Column(Modifier.fillMaxSize()) {
                WithinEditorMetadata(date.takeIf(String::isNotBlank)?.let(LocalDate::parse), categories,
                    today, allowMissingDate, !busy,
                    onDateChange = { date = it?.toString().orEmpty(); saveFailed = false },
                    onCategoriesChange = { categories = it; saveFailed = false },
                    modifier = Modifier.padding(horizontal = 10.dp), showRow = showMetadata)
                if (saveFailed) WithinWriteError("未能保存随笔。可以重试。", Modifier.padding(horizontal = 22.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val panelHeight = ((maxHeight - 49.dp).coerceAtLeast(0.dp) * .5f).coerceAtMost(250.dp)
                    Column(Modifier.fillMaxSize()) {
                        BasicTextField(
                            value = body,
                            onValueChange = {
                                withinTextEdit(body, it)?.let { edit ->
                                    val style = if (body.text.isEmpty()) pendingStyle else
                                        withinStyleAt(spans, if (body.selection.collapsed) (edit.start - 1).coerceAtLeast(0) else edit.start)
                                    spans = edit.updateSpans(spans, style)
                                    images = edit.updateImages(images)
                                    if (it.text.isEmpty()) pendingStyle = style
                                }
                                body = it
                                saveFailed = false
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = WithinWritingInk, lineHeight = 28.sp),
                            cursorBrush = SolidColor(Gold),
                            visualTransformation = remember(spans) {
                                VisualTransformation { text ->
                                    TransformedText(withinStyledText(text.text, spans), OffsetMapping.Identity)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)
                                .testTag("within_editor_body").semantics { contentDescription = "随笔正文" },
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxSize()) {
                                    if (body.text.isEmpty()) {
                                        Text("写下此刻的想法…", color = Muted, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    innerTextField()
                                }
                            },
                        )

                        if (tools != null && panelHeight > 0.dp) {
                            val activePanel = tools
                            key(activePanel) {
                                Column(Modifier.fillMaxWidth().background(Night.copy(alpha = .66f)).heightIn(max = panelHeight)
                                    .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp)) {
                                    if (activePanel == WithinEditorTools.Style) {
                                        val range = if (body.selection.collapsed) TextRange(0, body.text.length) else body.selection
                                        val currentStyle = if (body.text.isEmpty()) pendingStyle else withinStyleAt(spans, range.min)
                                        WithinFormatBar(currentStyle, enabled = !busy) { format ->
                                            if (body.text.isEmpty()) pendingStyle = format(pendingStyle)
                                            else spans = formatWithinText(spans, range.min, range.max, format)
                                            saveFailed = false
                                        }
                                    } else {
                                        WithinEditorImages(images, busy, importingImage, imageError, loadImage,
                                            onPick = { pickImage.launch("image/*") },
                                            onPaste = {
                                                val clipboard = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                                val uri = clipboard?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                                                if (uri == null) imageError = "剪贴板里没有可粘贴的图片。" else addImage(uri)
                                            },
                                            onRemove = { image ->
                                                images = images.filterNot { it.id == image.id }
                                                removeDraftImages(listOf(image))
                                            })
                                    }
                                }
                            }
                        }
                        WithinEditorToolbar(tools, images.size, !busy) { tools = if (tools == it) null else it }
                    }
                }
            }
        }
    }

    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("放弃未保存的内容？") },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
            confirmButton = {
                TextButton(onClick = { removeDraftImages(images, onDismiss) }, enabled = !busy,
                    modifier = Modifier.testTag("within_editor_discard")) {
                    Text("放弃")
                }
            },
        )
    }
}

private enum class WithinEditorTools { Style, Images }

@Composable
private fun WithinEditorToolbar(tools: WithinEditorTools?, imageCount: Int, enabled: Boolean,
    onTools: (WithinEditorTools) -> Unit) {
    HorizontalDivider(color = Line.copy(alpha = .58f))
    Row(Modifier.fillMaxWidth().background(Night.copy(alpha = .70f)).padding(horizontal = 10.dp)
        .testTag("within_editor_toolbar"), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val styleSelected = tools == WithinEditorTools.Style
        val imagesSelected = tools == WithinEditorTools.Images
        TextButton(onClick = { onTools(WithinEditorTools.Style) }, enabled = enabled,
            modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("within_editor_format_tools")
                .semantics { selected = styleSelected; contentDescription = "文字样式" }) {
            Text("Aa", fontSize = 19.sp, color = if (styleSelected) Gold else WithinWritingInk)
        }
        TextButton(onClick = { onTools(WithinEditorTools.Images) }, enabled = enabled,
            modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("within_editor_image_tools")
                .semantics { selected = imagesSelected }) {
            val color = if (imagesSelected) Gold else WithinWritingInk
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WithinPictureIcon(color)
                Text(if (imageCount == 0) "图片" else "图片 $imageCount", color = color)
            }
        }
    }
}

@Composable
private fun WithinPictureIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val edge = 2.dp.toPx()
        val line = 1.5.dp.toPx()
        drawRoundRect(color, Offset(edge, edge), Size(size.width - edge * 2, size.height - edge * 2),
            CornerRadius(2.dp.toPx()), style = Stroke(line))
        drawCircle(color, 1.15.dp.toPx(), Offset(size.width * .66f, size.height * .33f))
        val mountains = Path().apply {
            moveTo(size.width * .18f, size.height * .71f)
            lineTo(size.width * .38f, size.height * .50f)
            lineTo(size.width * .55f, size.height * .66f)
            lineTo(size.width * .67f, size.height * .53f)
            lineTo(size.width * .82f, size.height * .70f)
        }
        drawPath(mountains, color, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WithinEditorImages(images: List<WithinImageAttachment>, busy: Boolean, importing: Boolean,
    error: String?, loadImage: suspend (WithinImageAttachment) -> ByteArray, onPick: () -> Unit, onPaste: () -> Unit,
    onRemove: (WithinImageAttachment) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val canAdd = !busy && images.size < MAX_WITHIN_IMAGES
        TextButton(onClick = onPick, enabled = canAdd, modifier = Modifier.testTag("within_editor_pick_image")) {
            Text("插入图片")
        }
        TextButton(onClick = onPaste, enabled = canAdd, modifier = Modifier.testTag("within_editor_paste_image")) {
            Text("粘贴图片")
        }
    }
    if (importing) Text("正在处理图片…", color = Muted)
    error?.let { WithinWriteError(it) }
    if (images.size == MAX_WITHIN_IMAGES) Text("每篇最多 $MAX_WITHIN_IMAGES 张图片。", color = Muted)
    for (image in images.sortedBy { it.offset }) key(image.id) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(image.name, Modifier.weight(1f), color = WithinWritingInk, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onRemove(image) }, enabled = !busy) { Text("移除") }
        }
        WithinAttachmentImage(image, loadImage, Modifier.fillMaxWidth().heightIn(max = 120.dp))
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
private fun WithinWriteError(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
