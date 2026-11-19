package io.github.githuiy.starlive.ui

import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.data.WithinCategory
import io.github.githuiy.starlive.data.WithinEssay
import io.github.githuiy.starlive.data.WithinEssayDraft
import io.github.githuiy.starlive.data.WithinImageAttachment
import io.github.githuiy.starlive.data.WithinListStyle
import io.github.githuiy.starlive.data.WithinParagraphAlignment
import io.github.githuiy.starlive.data.WithinParagraphStyle
import io.github.githuiy.starlive.data.WithinTextStyle
import io.github.githuiy.starlive.data.MAX_WITHIN_IMAGES
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var savedBody by rememberSaveable(initial?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(originalBody))
    }
    var savedSpans by rememberSaveable(initial?.id, stateSaver = withinSpanSaver) {
        mutableStateOf(original?.spans.orEmpty())
    }
    var savedImages by rememberSaveable(initial?.id, stateSaver = withinImageSaver) {
        mutableStateOf(original?.images.orEmpty())
    }
    var savedParagraphs by rememberSaveable(initial?.id, stateSaver = withinParagraphSaver) {
        mutableStateOf(original?.paragraphs.orEmpty())
    }
    var savedPendingStyle by rememberSaveable(initial?.id, stateSaver = withinStyleSaver) {
        mutableStateOf(WithinTextStyle())
    }
    var importedImages by rememberSaveable(initial?.id, stateSaver = withinImageSaver) {
        mutableStateOf(emptyList())
    }
    val session = rememberWithinEditorSession(initial?.id,
        WithinEditSnapshot(savedBody, savedSpans, savedParagraphs, savedImages, savedPendingStyle))
    var history by session.history
    val (body, spans, paragraphs, images, pendingStyle) = history.current

    fun applyHistory(next: WithinEditHistory) {
        history = next
        val snapshot = next.current
        savedBody = snapshot.body
        savedSpans = snapshot.spans
        savedParagraphs = snapshot.paragraphs
        savedImages = snapshot.images
        savedPendingStyle = snapshot.pendingStyle
    }
    var date by rememberSaveable(initial?.id) { mutableStateOf(originalDate) }
    var categories by rememberSaveable(initial?.id, defaultCategory) { mutableStateOf(originalCategories.toList()) }
    var discard by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var importingImage by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = saving || importingImage || closing
    val dirty = body.text != originalBody || spans != original?.spans.orEmpty() ||
        images != original?.images.orEmpty() || paragraphs != original?.paragraphs.orEmpty() ||
        date != originalDate || categories.toSet() != originalCategories

    val releasableImages = remember(history, importedImages) {
        if (importedImages.isEmpty()) emptyList() else {
            val retained = history.retainedImages.mapTo(mutableSetOf()) { it.id }
            importedImages.filterNot { it.id in retained }
        }
    }
    LaunchedEffect(releasableImages) {
        if (releasableImages.isNotEmpty()) {
            try {
                discardImages(releasableImages)
                val removed = releasableImages.mapTo(mutableSetOf()) { it.id }
                importedImages = importedImages.filterNot { it.id in removed }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retry cleanup when the editor closes.
            }
        }
    }

    suspend fun finishEditor() {
        if (importedImages.isNotEmpty()) {
            try {
                // The repository keeps every image referenced by a committed essay.
                discardImages(importedImages)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A successful save remains valid if unused files could not be removed.
            }
        }
        session.release()
        onDismiss()
    }

    fun dismissDraft() {
        closing = true
        scope.launch {
            try {
                finishEditor()
            } finally {
                closing = false
            }
        }
    }

    fun requestDismiss() {
        if (busy) return
        if (dirty) discard = true else dismissDraft()
    }

    fun addImage(uri: Uri, name: String? = null) {
        if (busy || images.size >= MAX_WITHIN_IMAGES) {
            scope.launch { discardWithinTemporaryImage(context, uri) }
            return
        }
        val position = history.current.body.selection.max
        importingImage = true
        imageError = null
        scope.launch {
            try {
                val imported = importImage(uri, position)
                val attachment = name?.let { imported.copy(name = it) } ?: imported
                val current = history.current
                importedImages = importedImages + attachment
                applyHistory(history.record(current.copy(images = current.images + attachment)))
                saveFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                imageError = failure.message ?: "无法打开这张图片。"
            } finally {
                importingImage = false
                withContext(NonCancellable) { discardWithinTemporaryImage(context, uri) }
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addImage(uri)
    }
    val takePhoto = rememberWithinPhotoCapture(onCaptured = { addImage(it, "拍照") }, onError = { imageError = it })

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    val imeTargetPx = WindowInsets.imeAnimationTarget.getBottom(density)
    val navigationPx = WindowInsets.navigationBars.getBottom(density)
    val keyboardElevation = with(density) { (imePx - navigationPx).coerceAtLeast(0).toDp() }
    val keyboardTargetElevation = with(density) { (imeTargetPx - navigationPx).coerceAtLeast(0).toDp() }
    val keyboardVisible = keyboardElevation > 0.dp
    var tools by rememberSaveable { mutableStateOf<WithinEditorTools?>(null) }
    var drawing by rememberSaveable { mutableStateOf(false) }
    var lastKeyboardHeight by rememberSaveable(LocalConfiguration.current.orientation) { mutableFloatStateOf(0f) }
    var returningToKeyboard by remember { mutableStateOf(false) }
    val switchToKeyboard by rememberUpdatedState<() -> Unit> {
        if (tools != null && !busy) {
            tools = null
            returningToKeyboard = true
        }
    }

    LaunchedEffect(keyboardTargetElevation) {
        if (keyboardTargetElevation > 0.dp) lastKeyboardHeight = keyboardTargetElevation.value
    }
    LaunchedEffect(returningToKeyboard) {
        if (returningToKeyboard) {
            keyboard?.show()
            delay(380)
            returningToKeyboard = false
        }
    }
    LaunchedEffect(returningToKeyboard, keyboardElevation, keyboardTargetElevation) {
        if (returningToKeyboard && keyboardTargetElevation > 0.dp && keyboardElevation >= keyboardTargetElevation) {
            returningToKeyboard = false
        }
    }

    BackHandler {
        when {
            tools != null -> tools = null
            keyboardVisible -> keyboard?.hide()
            else -> requestDismiss()
        }
    }

    fun restoreHistory(redo: Boolean) {
        if (busy) return
        val restored = if (redo) history.redo() else history.undo()
        applyHistory(restored)
        saveFailed = false
    }

    WithinDocumentPage(Modifier.testTag("within_editor").onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            false
        } else if (event.key == Key.Z || event.key == Key.Y) {
            restoreHistory(redo = event.key == Key.Y || event.isShiftPressed)
            true
        } else false
    }) {
        WithinDocumentHeader(if (initial == null) "写随笔" else "编辑随笔", "within_editor_cancel",
            onBack = {
                focus.clearFocus()
                keyboard?.hide()
                requestDismiss()
            }, backEnabled = !busy) {
            WithinHistoryButton(redo = false, enabled = !busy && history.canUndo) { restoreHistory(redo = false) }
            WithinHistoryButton(redo = true, enabled = !busy && history.canRedo) { restoreHistory(redo = true) }
            val canSave = (body.text.isNotBlank() || images.isNotEmpty()) && !busy
            TextButton(enabled = canSave, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("within_editor_save"),
                onClick = {
                    val recordedOn = date.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                    val snapshot = history.current
                    val draft = WithinEssayDraft(body = snapshot.body.text, date = recordedOn, categories = categories.toSet(),
                        spans = snapshot.spans, images = snapshot.images, paragraphs = snapshot.paragraphs)
                    focus.clearFocus()
                    keyboard?.hide()
                    saving = true
                    saveFailed = false
                    scope.launch {
                        try {
                            onSave(draft)
                            finishEditor()
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
                    val panelTarget = if (lastKeyboardHeight > 0f) lastKeyboardHeight.dp else 264.dp
                    val panelHeight = (panelTarget - keyboardElevation).coerceIn(0.dp,
                        (maxHeight - 49.dp - 72.dp).coerceAtLeast(0.dp))
                    Column(Modifier.fillMaxSize()) {
                        BasicTextField(
                            value = body,
                            onValueChange = { value ->
                                val current = history.current
                                val field = current.body
                                val exitList = withinListExitOnEnter(field, value, current.paragraphs, current.images)
                                if (exitList != null) {
                                    applyHistory(history.record(current.copy(paragraphs = exitList)))
                                } else {
                                    val edit = withinTextEdit(field, value)
                                    val next = if (edit == null) {
                                        current.copy(body = value)
                                    } else {
                                        val style = if (field.text.isEmpty()) current.pendingStyle else {
                                            val index = if (field.selection.collapsed) (edit.start - 1).coerceAtLeast(0) else edit.start
                                            withinStyleAt(current.spans, index)
                                        }
                                        current.copy(body = value, spans = edit.updateSpans(current.spans, style),
                                            images = edit.updateImages(current.images),
                                            paragraphs = edit.updateParagraphs(field.text, value.text, current.paragraphs),
                                            pendingStyle = if (value.text.isEmpty()) style else current.pendingStyle)
                                    }
                                    applyHistory(history.record(next, WithinEditKind.TEXT))
                                }
                                saveFailed = false
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = WithinWritingInk, lineHeight = 28.sp),
                            cursorBrush = SolidColor(Gold),
                            visualTransformation = remember(spans, paragraphs) {
                                VisualTransformation { text -> withinEditorText(text.text, spans, paragraphs) }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        var shortTap = true
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                                change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis ||
                                                event.changes.count { it.pressed } > 1) shortTap = false
                                            if (!change.pressed) {
                                                if (shortTap) switchToKeyboard()
                                                break
                                            }
                                        } while (true)
                                    }
                                }
                                .testTag("within_editor_body").semantics { contentDescription = "随笔正文" },
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxSize()) {
                                    if (body.text.isEmpty() && paragraphs.isEmpty()) {
                                        Text("写下此刻的想法…", color = Muted, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    innerTextField()
                                }
                            },
                        )

                        WithinEditorToolbar(tools, images.size, !busy) { chosen ->
                            returningToKeyboard = false
                            if (tools == chosen) tools = null else {
                                tools = chosen
                                keyboard?.hide()
                            }
                        }
                        val activePanel = tools
                        if ((activePanel != null || returningToKeyboard) && panelHeight > 0.dp) {
                            key(activePanel) {
                                Column(Modifier.fillMaxWidth().height(panelHeight).background(Night.copy(alpha = .83f))
                                    .testTag("within_editor_tool_panel").verticalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    when (activePanel) {
                                    WithinEditorTools.Style -> {
                                        val range = if (body.selection.collapsed) TextRange(0, body.text.length) else body.selection
                                        val currentStyle = if (body.text.isEmpty()) pendingStyle else withinStyleAt(spans, range.min)
                                        WithinFormatBar(currentStyle, enabled = !busy) { format ->
                                            val current = history.current
                                            val field = current.body
                                            val selection = if (field.selection.collapsed) TextRange(0, field.text.length) else field.selection
                                            val next = if (field.text.isEmpty()) current.copy(pendingStyle = format(current.pendingStyle))
                                            else current.copy(spans = formatWithinText(current.spans, selection.min, selection.max, format))
                                            applyHistory(history.record(next))
                                            saveFailed = false
                                        }
                                    }
                                    WithinEditorTools.Paragraph -> {
                                        val style = withinParagraphStyleAt(body.text, paragraphs, body.selection.min)
                                        WithinParagraphTools(style.alignment, style.list, !busy) { change ->
                                            val current = history.current
                                            val field = current.body
                                            val updated = formatWithinParagraphs(field.text, current.paragraphs,
                                                field.selection.min, field.selection.max, change)
                                            applyHistory(history.record(current.copy(paragraphs = updated)))
                                            saveFailed = false
                                        }
                                    }
                                    WithinEditorTools.Images -> {
                                        WithinEditorImages(images, busy, importingImage, imageError, loadImage,
                                            onPick = { pickImage.launch("image/*") },
                                            onCamera = takePhoto,
                                            onPaste = {
                                                val clipboard = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                                val uri = clipboard?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                                                if (uri == null) imageError = "剪贴板里没有可粘贴的图片。" else addImage(uri)
                                            },
                                            onRemove = { image ->
                                                val current = history.current
                                                applyHistory(history.record(current.copy(images = current.images.filterNot { it.id == image.id })))
                                            })
                                    }
                                    WithinEditorTools.More -> WithinToolTile("within_editor_doodle", "涂鸦",
                                        enabled = !busy && images.size < MAX_WITHIN_IMAGES,
                                        onClick = { keyboard?.hide(); drawing = true }) { color ->
                                        Text("✎", color = color, fontSize = 25.sp)
                                    }
                                    null -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (drawing) WithinDoodlePage(onDismiss = { drawing = false }, onComplete = { uri ->
        drawing = false
        addImage(uri, "涂鸦")
        tools = WithinEditorTools.Images
    })

    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("放弃未保存的内容？") },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
            confirmButton = {
                TextButton(onClick = ::dismissDraft, enabled = !busy,
                    modifier = Modifier.testTag("within_editor_discard")) {
                    Text("放弃")
                }
            },
        )
    }
}

@Composable
private fun WithinHistoryButton(redo: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = if (redo) "重做" else "撤销"
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(if (redo) "within_editor_redo" else "within_editor_undo")
            .semantics { contentDescription = description }) {
        Canvas(Modifier.size(21.dp)) {
            fun x(fraction: Float) = size.width * if (redo) 1f - fraction else fraction
            val arrow = Path().apply {
                moveTo(x(.38f), size.height * .25f)
                lineTo(x(.16f), size.height * .47f)
                lineTo(x(.38f), size.height * .69f)
                moveTo(x(.17f), size.height * .47f)
                lineTo(x(.60f), size.height * .47f)
                cubicTo(x(.89f), size.height * .47f, x(.88f), size.height * .78f, x(.65f), size.height * .81f)
            }
            drawPath(arrow, if (enabled) Gold else Muted.copy(alpha = .53f),
                style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private enum class WithinEditorTools { Style, Paragraph, Images, More }

@Composable
private fun WithinEditorToolbar(tools: WithinEditorTools?, imageCount: Int, enabled: Boolean,
    onTools: (WithinEditorTools) -> Unit) {
    HorizontalDivider(color = Line.copy(alpha = .58f))
    Row(Modifier.fillMaxWidth().background(Night.copy(alpha = .78f)).padding(horizontal = 10.dp)
        .testTag("within_editor_toolbar"),
        verticalAlignment = Alignment.CenterVertically) {
        WithinFormatChoice("within_editor_format_tools", "文字样式", tools == WithinEditorTools.Style, enabled,
            { onTools(WithinEditorTools.Style) }, Modifier.weight(1f)) { color ->
            Text("Aa", fontSize = 19.sp, color = color)
        }
        WithinFormatChoice("within_editor_paragraph_tools", "段落", tools == WithinEditorTools.Paragraph, enabled,
            { onTools(WithinEditorTools.Paragraph) }, Modifier.weight(1f)) { color ->
            WithinParagraphIcon(WithinParagraphAlignment.START, color)
        }
        WithinFormatChoice("within_editor_image_tools", if (imageCount == 0) "插图" else "插图，已有 $imageCount 张",
            tools == WithinEditorTools.Images, enabled, { onTools(WithinEditorTools.Images) }, Modifier.weight(1f)) { color ->
            WithinPictureIcon(color)
        }
        WithinFormatChoice("within_editor_more_tools", "更多工具", tools == WithinEditorTools.More, enabled,
            { onTools(WithinEditorTools.More) }, Modifier.weight(1f)) { color ->
            Text("+", fontSize = 26.sp, color = color)
        }
    }
}

@Composable
private fun WithinParagraphTools(alignment: WithinParagraphAlignment, list: WithinListStyle, enabled: Boolean,
    onFormat: ((WithinParagraphStyle) -> WithinParagraphStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, option) in listOf("左对齐" to WithinParagraphAlignment.START,
                "居中" to WithinParagraphAlignment.CENTER, "右对齐" to WithinParagraphAlignment.END)) {
                WithinFormatChoice("within_paragraph_" + option.name, name, alignment == option, enabled,
                    { onFormat { it.copy(alignment = option) } }, Modifier.weight(1f)) { color ->
                    WithinParagraphIcon(option, color)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val styles = listOf(Triple("圆点", "•", WithinListStyle.BULLET),
                Triple("数字", "1.", WithinListStyle.DECIMAL), Triple("字母", "a.", WithinListStyle.LETTERS))
            for ((name, mark, option) in styles) {
                WithinFormatChoice("within_list_" + option.name, name, list == option, enabled,
                    { onFormat { it.copy(list = if (list == option) WithinListStyle.NONE else option) } },
                    Modifier.weight(1f)) { color -> Text("$mark $name", color = color) }
            }
        }
    }
}

@Composable
private fun WithinParagraphIcon(alignment: WithinParagraphAlignment, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val margin = 2.dp.toPx()
        val available = size.width - margin * 2
        for (row in 0..3) {
            val length = available * if (row % 2 == 0) 1f else .63f
            val x = margin + when (alignment) {
                WithinParagraphAlignment.START -> 0f
                WithinParagraphAlignment.CENTER -> (available - length) / 2f
                WithinParagraphAlignment.END -> available - length
            }
            val y = size.height * (.20f + row * .2f)
            drawLine(color, Offset(x, y), Offset(x + length, y), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
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

@Composable
private fun WithinEditorImages(images: List<WithinImageAttachment>, busy: Boolean, importing: Boolean,
    error: String?, loadImage: suspend (WithinImageAttachment) -> ByteArray, onPick: () -> Unit, onPaste: () -> Unit,
    onCamera: () -> Unit, onRemove: (WithinImageAttachment) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val canAdd = !busy && images.size < MAX_WITHIN_IMAGES
        WithinToolTile("within_editor_pick_image", "相册", canAdd, onPick, Modifier.weight(1f)) { color ->
            WithinPictureIcon(color)
        }
        WithinToolTile("within_editor_take_photo", "拍照", canAdd, onCamera, Modifier.weight(1f)) { color ->
            Canvas(Modifier.size(20.dp)) {
                val stroke = Stroke(1.5.dp.toPx())
                drawRoundRect(color, Offset(size.width * .10f, size.height * .28f), Size(size.width * .80f, size.height * .58f),
                    CornerRadius(2.dp.toPx()), style = stroke)
                drawCircle(color, size.width * .16f, Offset(size.width * .50f, size.height * .57f), style = stroke)
                drawLine(color, Offset(size.width * .34f, size.height * .18f), Offset(size.width * .66f, size.height * .18f),
                    strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        WithinToolTile("within_editor_paste_image", "粘贴图片", canAdd, onPaste, Modifier.weight(1f)) { color ->
            Text("▤", color = color, fontSize = 21.sp)
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

@Composable
private fun WithinToolTile(tag: String, label: String, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, icon: @Composable (Color) -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Ink.copy(alpha = .08f),
        shape = RoundedCornerShape(14.dp), modifier = modifier.sizeIn(minWidth = 86.dp, minHeight = 78.dp).testTag(tag)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            icon(if (enabled) Gold else Muted)
            Text(label, color = if (enabled) WithinWritingInk else Muted, style = MaterialTheme.typography.labelMedium)
        }
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
