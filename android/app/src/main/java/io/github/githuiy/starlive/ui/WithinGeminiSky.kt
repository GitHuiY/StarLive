package io.github.githuiy.starlive.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.data.WithinCategory
import kotlin.math.roundToInt

private enum class GeminiLabelSide { Above, Left, Right, Below }

private data class GeminiPoint(
    val reference: Offset,
    val category: WithinCategory? = null,
    val labelSide: GeminiLabelSide = GeminiLabelSide.Below,
    val warm: Boolean = false
)

// Trace the points and connections from the supplied illustration. The two
// figures themselves are never drawn.
private val GeminiPoints = listOf(
    GeminiPoint(Offset(386f, 132f), WithinCategory.SELF, GeminiLabelSide.Above, warm = true),
    GeminiPoint(Offset(327f, 170f)),
    GeminiPoint(Offset(394f, 174f)),
    GeminiPoint(Offset(437f, 192f)),
    GeminiPoint(Offset(360f, 281f), WithinCategory.MOMENT, GeminiLabelSide.Left),
    GeminiPoint(Offset(276f, 349f)),
    GeminiPoint(Offset(385f, 350f)),
    GeminiPoint(Offset(322f, 505f), WithinCategory.FOOTPRINT),
    GeminiPoint(Offset(403f, 485f)),
    GeminiPoint(Offset(480f, 120f), WithinCategory.WORLD, GeminiLabelSide.Above, warm = true),
    GeminiPoint(Offset(519f, 211f)),
    GeminiPoint(Offset(629f, 217f)),
    GeminiPoint(Offset(517f, 355f), WithinCategory.ECHO, GeminiLabelSide.Right),
    GeminiPoint(Offset(489f, 460f)),
    GeminiPoint(Offset(545f, 449f)),
    GeminiPoint(Offset(570f, 475f)),
    GeminiPoint(Offset(620f, 498f), WithinCategory.BEYOND)
)
private val GeminiOrigin = Offset(276f, 120f)
private val GeminiSpan = Offset(353f, 385f)
private val GeminiLines = listOf(
    listOf(0, 2, 3, 10, 11),
    listOf(1, 2, 4, 5, 7),
    listOf(4, 6, 8),
    listOf(9, 10, 12, 13),
    listOf(12, 14, 15, 16)
)
private val GeminiEntries = GeminiPoints.indices.filter { GeminiPoints[it].category != null }

@Composable
internal fun WithinGeminiSky(
    selected: WithinCategory?,
    onSelect: (WithinCategory) -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val focusProgress = animateFloatAsState(
        targetValue = if (selected == null) 0f else 1f,
        animationSpec = tween(if (reduceMotion) 0 else 560, easing = FastOutSlowInEasing),
        label = "within-gemini-focus"
    )
    var lastSelection by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) { if (selected != null) lastSelection = selected }
    val focusIndex = GeminiPoints.indexOfFirst {
        it.category != null && it.category == (selected ?: lastSelection)
    }
    val canSelect by remember { derivedStateOf { focusProgress.value <= .02f } }
    val plotDensity = LocalDensity.current
    val labelSize = with(plotDensity) { 13.sp.toPx() }
    val focusLabelSize = with(plotDensity) { 22.sp.toPx() }
    val layoutPaint = remember(labelSize) { geminiLabelPaint(labelSize) }
    val drawPaint = remember(labelSize) { geminiLabelPaint(labelSize) }
    val labelHeight = layoutPaint.descent() - layoutPaint.ascent()

    BoxWithConstraints(modifier.clipToBounds().testTag("within_gemini_sky")) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (width <= 0f || height <= 0f) return@BoxWithConstraints
        val points = with(plotDensity) {
            // Reserve enough room to place names outside the upper and lower
            // branches, including at a larger system font size.
            val verticalMargin = maxOf(25.dp.toPx(), labelHeight + 16.dp.toPx())
                .coerceAtMost(height * .27f)
            val horizontalMargin = minOf(24.dp.toPx(), width * .12f)
            val plotHeight = height - 2 * verticalMargin
            val plotWidth = minOf(width - 2 * horizontalMargin, maxOf(plotHeight * 1.1f, 226.dp.toPx()))
            val left = (width - plotWidth) / 2
            GeminiPoints.map {
                val proportion = it.reference - GeminiOrigin
                Offset(
                    left + plotWidth * proportion.x / GeminiSpan.x,
                    verticalMargin + plotHeight * proportion.y / GeminiSpan.y
                )
            }
        }
        val labels = with(plotDensity) {
            GeminiEntries.associateWith { index ->
                val point = GeminiPoints[index]
                val anchor = points[index]
                val labelWidth = layoutPaint.measureText(checkNotNull(point.category).label)
                val gap = 13.dp.toPx()
                val center = when (point.labelSide) {
                    GeminiLabelSide.Above -> {
                        val shift = if (point.category == WithinCategory.SELF) -6.dp.toPx() else 6.dp.toPx()
                        anchor + Offset(shift, -gap - labelHeight / 2)
                    }
                    GeminiLabelSide.Left -> anchor - Offset(gap + labelWidth / 2, 0f)
                    GeminiLabelSide.Right -> anchor + Offset(gap + labelWidth / 2, 0f)
                    GeminiLabelSide.Below -> anchor + Offset(0f, gap + labelHeight / 2)
                }
                val left = (center.x - labelWidth / 2).coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
                val top = (center.y - labelHeight / 2).coerceIn(0f, (height - labelHeight).coerceAtLeast(0f))
                Rect(left, top, left + labelWidth, top + labelHeight)
            }
        }
        val selectedLabel = if (selected != null) {
            Modifier.testTag("within_focused_star").semantics { contentDescription = selected.label }
        } else Modifier
        Canvas(Modifier.matchParentSize().then(selectedLabel)) {
            val progress = if (focusIndex < 0) 0f else focusProgress.value
            val transparency = 1f - progress
            val otherOpacity = transparency * transparency
            val center = Offset(size.width * .5f, size.height * .43f)
            val selectedPoint = points.getOrElse(focusIndex) { center }
            val focusedPoint = lerp(selectedPoint, center, progress)
            val positions = points.map { point ->
                focusedPoint + (point - selectedPoint) * (1f + .18f * progress)
            }
            if (otherOpacity > 0f) {
                val path = Path().apply {
                    GeminiLines.forEach { indices ->
                        val start = positions[indices.first()]
                        moveTo(start.x, start.y)
                        indices.drop(1).forEach { lineTo(positions[it].x, positions[it].y) }
                    }
                }
                drawPath(
                    path,
                    Brush.linearGradient(
                        listOf(Color(0xFFABC8E8).copy(alpha = .48f), Color(0xFFB4D8ED).copy(alpha = .66f),
                            Color(0xFFC1CCDE).copy(alpha = .51f)),
                        start = Offset(size.width * .18f, 0f),
                        end = Offset(size.width * .82f, size.height)
                    ),
                    alpha = otherOpacity,
                    style = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            GeminiPoints.forEachIndexed { index, point ->
                val primary = point.category != null
                val opacity = if (index == focusIndex) 1f else otherOpacity
                if (opacity > 0f) {
                    val zoom = if (index == focusIndex) 1f + 1.18f * progress else 1f
                    val scale = (if (primary) .19.dp else .115.dp).toPx() * zoom
                    val light = if (primary) .42f else .13f
                    drawLandingHalo(positions[index], scale, light, point.warm, opacity)
                    drawLandingStar(positions[index], scale, light, point.warm, opacity)
                }
            }
            val canvas = drawContext.canvas.nativeCanvas
            labels.forEach { (index, bounds) ->
                val focus = if (index == focusIndex) progress else 0f
                val opacity = if (index == focusIndex) 1f else otherOpacity
                if (opacity > 0f) {
                    val label = checkNotNull(GeminiPoints[index].category).label
                    val originalCenter = bounds.center + positions[index] - points[index]
                    val labelCenter = if (index == focusIndex) {
                        lerp(bounds.center, center + Offset(0f, 35.dp.toPx()), focus)
                    } else originalCenter
                    drawPaint.textSize = labelSize + (focusLabelSize - labelSize) * focus
                    drawPaint.color = lerpColor(Ink, Gold, focus * .65f).copy(alpha = opacity).toArgb()
                    drawPaint.setShadowLayer(2.dp.toPx(), 0f, 1.dp.toPx(), Night.copy(alpha = opacity).toArgb())
                    canvas.drawText(label, labelCenter.x,
                        labelCenter.y - (drawPaint.ascent() + drawPaint.descent()) / 2, drawPaint)
                }
            }
        }
        if (selected == null && canSelect) {
            GeminiEntries.forEach { index ->
                val category = checkNotNull(GeminiPoints[index].category)
                val center = points[index]
                val label = labels.getValue(index)
                with(plotDensity) {
                    val radius = 24.dp.toPx()
                    val extra = 4.dp.toPx()
                    val left = minOf(center.x - radius, label.left - extra).coerceAtLeast(0f)
                    val top = minOf(center.y - radius, label.top - extra).coerceAtLeast(0f)
                    val right = maxOf(center.x + radius, label.right + extra).coerceAtMost(width)
                    val bottom = maxOf(center.y + radius, label.bottom + extra).coerceAtMost(height)
                    Box(
                        Modifier.absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                            .size((right - left).toDp(), (bottom - top).toDp())
                            .testTag("within_star_${category.name}")
                            .semantics { contentDescription = category.label }
                            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                                onSelect(category)
                            }
                    )
                }
            }
        }
    }
}

private fun geminiLabelPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = Typeface.create("serif", Typeface.NORMAL)
    textSize = size
    isSubpixelText = true
}
