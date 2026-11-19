package io.github.githuiy.starlive.ui

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WithinDoodleSaver = listSaver<List<WithinDoodleStroke>, Any>(
    save = { strokes ->
        strokes.map { stroke ->
            arrayListOf<Any>(stroke.erasing).apply {
                stroke.points.forEach { point -> add(point.x); add(point.y) }
            }
        }
    },
    restore = { saved ->
        saved.map { value ->
            val stroke = value as List<*>
            WithinDoodleStroke(stroke.drop(1).chunked(2).map { Offset(it[0] as Float, it[1] as Float) },
                erasing = stroke[0] as Boolean)
        }
    },
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WithinDoodlePage(onDismiss: () -> Unit, onComplete: (Uri) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var strokes by rememberSaveable(stateSaver = WithinDoodleSaver) { mutableStateOf(emptyList<WithinDoodleStroke>()) }
    var erasing by rememberSaveable { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val activePoints = remember { mutableStateListOf<Offset>() }
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Night)) {
            WithinDocumentPage(Modifier.testTag("within_doodle").semantics { testTagsAsResourceId = true }) {
                WithinDocumentHeader("涂鸦", "within_doodle_cancel", onDismiss, backEnabled = !exporting) {
                    TextButton(enabled = !exporting && activePoints.isEmpty() && strokes.any { !it.erasing },
                        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("within_doodle_done"),
                        onClick = {
                            exporting = true
                            error = null
                            val completed = strokes
                            val size = viewport
                            scope.launch {
                                var temporary: Uri? = null
                                var rendered: Bitmap? = null
                                try {
                                    val bitmap = withContext(Dispatchers.Default) {
                                        renderWithinDoodle(completed, size).also { rendered = it }
                                    }
                                    if (bitmap == null) {
                                        onDismiss()
                                    } else {
                                        val uri = writeWithinDoodleImage(context, bitmap)
                                        temporary = uri
                                        onComplete(uri)
                                        temporary = null
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "无法保存涂鸦，请重试。"
                                } finally {
                                    temporary?.let { discardWithinTemporaryImage(context, it) }
                                    rendered?.recycle()
                                    exporting = false
                                }
                            }
                        }) { Text(if (exporting) "正在保存…" else "完成") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Canvas(Modifier.fillMaxSize().clipToBounds().background(Color.White)
                        .testTag("within_doodle_canvas").semantics { contentDescription = "涂鸦画布" }
                        .onSizeChanged { viewport = it }
                        .pointerInput(erasing, exporting) {
                            if (exporting) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                fun normalized(point: Offset) = Offset(
                                    (point.x / size.width).coerceIn(0f, 1f),
                                    (point.y / size.height).coerceIn(0f, 1f),
                                )
                                activePoints.clear()
                                activePoints.add(normalized(down.position))
                                error = null
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val point = normalized(pointer.position)
                                        if (point != activePoints.last()) activePoints.add(point)
                                        pointer.consume()
                                    } while (pointer.pressed)
                                    strokes = strokes + WithinDoodleStroke(activePoints.toList(), erasing)
                                } finally {
                                    activePoints.clear()
                                }
                            }
                        }) {
                        strokes.forEach { drawDoodleStroke(it.points, it.erasing) }
                        drawDoodleStroke(activePoints, erasing)
                    }
                }
                error?.let { Text(it, Modifier.padding(horizontal = 14.dp).testTag("within_doodle_error"), color = Ink) }
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = !erasing, enabled = !exporting, onClick = { erasing = false },
                        label = { Text("画笔") }, modifier = Modifier.testTag("within_doodle_pen"))
                    FilterChip(selected = erasing, enabled = !exporting, onClick = { erasing = true },
                        label = { Text("橡皮") }, modifier = Modifier.testTag("within_doodle_eraser"))
                    TextButton(onClick = { strokes = strokes.dropLast(1) },
                        enabled = !exporting && activePoints.isEmpty() && strokes.isNotEmpty(),
                        modifier = Modifier.testTag("within_doodle_undo")) { Text("撤销") }
                }
            }
        }
    }
}

private fun DrawScope.drawDoodleStroke(points: List<Offset>, erasing: Boolean) {
    if (points.isEmpty()) return
    val color = if (erasing) Color.White else Color(WithinDoodleInk)
    val width = (if (erasing) WithinDoodleEraserWidth else WithinDoodlePenWidth) * size.minDimension
    val first = Offset(points.first().x * size.width, points.first().y * size.height)
    if (points.size == 1) {
        drawCircle(color, width / 2, first)
    } else {
        val path = Path().apply {
            moveTo(first.x, first.y)
            points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
        }
        drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
