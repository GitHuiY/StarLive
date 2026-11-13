package io.github.githuiy.starlive.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.PlanItem
import kotlin.math.roundToInt

val Weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
val ShortWeekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
val StarNames = listOf("天枢", "天璇", "天玑", "天权", "玉衡", "开阳", "摇光")
internal val StarPositions = listOf(
    Offset(.83f, .20f), Offset(.88f, .39f), Offset(.67f, .53f),
    Offset(.57f, .41f), Offset(.407f, .435f), Offset(.238f, .445f), Offset(.08f, .61f)
)
internal val Starlight = Color(0xFF9ACFEF)
internal const val CompanionFocusMillis = 420
internal const val DayZoom = 2.75f
internal const val DayStarAnchorY = .34f
internal const val FocusedSkyBlendStart = .84f
internal fun focusedSkyOpacity(progress: Float): Float =
    ((progress - FocusedSkyBlendStart) / (1f - FocusedSkyBlendStart)).coerceIn(0f, 1f)
private val CompanionBlue = Color(0xFF80B4E4)
private val CompanionSlots = listOf(
    Offset(54f, -33f), Offset(-54f, -38f), Offset(54f, 21f), Offset(-54f, 16f),
    Offset(108f, -30f), Offset(-108f, -34f), Offset(108f, 24f), Offset(-108f, 20f)
)

/** Six consecutive segments deliberately leave Monday–Thursday unconnected. */
@Composable
fun WeekStarMap(
    ratios: List<Float>, todayIndex: Int, selectedDay: Int, overview: Boolean,
    reduceMotion: Boolean, onDay: (Int) -> Unit, onOverview: () -> Unit,
    modifier: Modifier = Modifier,
    companions: List<PlanItem> = emptyList(), focusedCompanionId: String? = null,
    onCompanion: (PlanItem) -> Unit = {}, onPrimaryBounds: (Rect?) -> Unit = {},
    highlightPrimary: Boolean = false, interactive: Boolean = true
) {
    val layoutDensity = LocalDensity.current
    val updatePrimaryBounds by rememberUpdatedState(onPrimaryBounds)
    val primaryAvailable = interactive && !overview && focusedCompanionId == null
    DisposableEffect(primaryAvailable, selectedDay) {
        if (!primaryAvailable) updatePrimaryBounds(null)
        onDispose { if (primaryAvailable) updatePrimaryBounds(null) }
    }
    val lights = (0..6).map { index ->
        animateFloatAsState(ratios.getOrElse(index) { 0f }.coerceIn(0f, 1f),
            tween(if (reduceMotion) 0 else 700), label = "star-light-$index").value
    }
    var page by remember(selectedDay) { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val pageSize = if (maxWidth >= 268.dp) CompanionSlots.size else 4
        val companionIds = remember(companions) { companions.map { it.id } }
        val lastPage = companions.lastIndex.coerceAtLeast(0) / pageSize
        var previousCompanions by remember(selectedDay) { mutableStateOf(companionIds.toSet()) }
        LaunchedEffect(selectedDay, companionIds, pageSize) {
            val newIndex = companionIds.indexOfLast { it !in previousCompanions }
            page = if (newIndex >= 0) newIndex / pageSize else page.coerceAtMost(lastPage)
            previousCompanions = companionIds.toSet()
        }
        val mainPosition = StarPositions[selectedDay]
        val maxHorizontalOffset = (widthPx / 2 - with(layoutDensity) { 25.dp.toPx() }).coerceAtLeast(0f)
        fun companionPosition(slot: Int): Offset = with(layoutDensity) {
            val offset = CompanionSlots[slot]
            val dx = offset.x.dp.toPx().coerceIn(-maxHorizontalOffset, maxHorizontalOffset)
            mainPosition + Offset(dx / (widthPx * DayZoom), offset.y.dp.toPx() / (heightPx * DayZoom))
        }
        val focusedIndex = if (overview) -1 else companions.indexOfFirst { it.id == focusedCompanionId }
        val cameraTarget = when {
            overview -> Offset(.5f, .5f)
            focusedIndex >= 0 -> companionPosition(focusedIndex % pageSize)
            else -> mainPosition
        }
        val duration = when {
            reduceMotion -> 0
            overview -> 1100
            else -> CompanionFocusMillis
        }
        val zoom by animateFloatAsState(if (overview) 1f else DayZoom, tween(duration), label = "star-zoom")
        val cameraX by animateFloatAsState(cameraTarget.x, tween(duration), label = "star-camera-x")
        val cameraY by animateFloatAsState(cameraTarget.y, tween(duration), label = "star-camera-y")
        val anchorY by animateFloatAsState(if (overview) .5f else DayStarAnchorY,
            tween(duration), label = "star-anchor")
        val displayedPage = if (focusedIndex >= 0) focusedIndex / pageSize else page.coerceAtMost(lastPage)
        val firstSlotIndex = displayedPage * pageSize
        val visibleCompanions = if (overview) emptyList() else
            companions.subList(firstSlotIndex, (firstSlotIndex + pageSize).coerceAtMost(companions.size))
        val companionVisibility = visibleCompanions.map { item ->
            key(item.id) {
                val emergence = remember { Animatable(if (reduceMotion) 1f else 0f) }
                LaunchedEffect(reduceMotion) {
                    if (reduceMotion) emergence.snapTo(1f) else emergence.animateTo(1f, tween(200))
                }
                emergence
            }
        }
        fun point(position: Offset): Offset = Offset(
            (position.x - cameraX) * widthPx * zoom + widthPx / 2,
            (position.y - cameraY) * heightPx * zoom + heightPx * anchorY
        )
        fun point(index: Int): Offset = point(StarPositions[index])
        Canvas(Modifier.matchParentSize()) {
            for (index in 0..5) {
                drawLine(Starlight.copy(alpha = if (overview) .48f else .10f),
                    point(index), point(index + 1), 1.dp.toPx(),
                    cap = StrokeCap.Round)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Muted.toArgb(); textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 11.5f * density * fontScale
            }
            fun label(index: Int) = "${ShortWeekdays[index]} · ${StarNames[index]}"
            val handleLabelWidth = (paint.measureText(label(4)) + paint.measureText(label(5))) / 2
            val stackLabels = fontScale > 1.3f ||
                handleLabelWidth + 3.dp.toPx() > (StarPositions[4].x - StarPositions[5].x) * widthPx
            for (index in 0..6) {
                val center = point(index)
                val ratio = lights[index]
                val visible = overview || index == selectedDay
                val alpha = if (visible) 1f else .12f
                val glow = if (index == todayIndex || ratio > 0f) Gold else Starlight
                val halo = (26f + ratio * 26f) * density
                drawCircle(Brush.radialGradient(listOf(
                    glow.copy(alpha = (.24f + ratio * .26f) * alpha), Color.Transparent
                ), center, halo), radius = halo, center = center)
                if (index == todayIndex) drawCircle(
                    Gold.copy(alpha = .38f * alpha), radius = 10.dp.toPx(), center = center,
                    style = Stroke(.7.dp.toPx()))
                if (highlightPrimary && primaryAvailable && index == selectedDay) {
                    drawCircle(glow.copy(alpha = .52f), 17.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                }
                drawCircle(glow.copy(alpha = (.90f + ratio * .10f) * alpha),
                    (3.2f + ratio * 2.1f).dp.toPx(), center)
                drawCircle(Ink.copy(alpha = .96f * alpha), 1.5.dp.toPx(), center)
                if (visible) {
                    val cross = (7 + ratio * 4).dp.toPx()
                    drawLine(glow.copy(alpha = (.25f + ratio * .45f) * alpha),
                        center - Offset(cross, 0f), center + Offset(cross, 0f), .6.dp.toPx())
                    drawLine(glow.copy(alpha = (.25f + ratio * .45f) * alpha),
                        center - Offset(0f, cross), center + Offset(0f, cross), .6.dp.toPx())
                }
                if (overview) {
                    paint.color = (if (index == todayIndex) Gold else Ink).toArgb()
                    val lines = if (stackLabels) listOf(ShortWeekdays[index], StarNames[index]) else listOf(label(index))
                    val lineHeight = paint.fontSpacing
                    val labelTop = when (index) {
                        0, 3 -> center.y - 17.dp.toPx() - lineHeight * lines.lastIndex
                        1, 5 -> center.y + 36.dp.toPx()
                        else -> center.y + 24.dp.toPx()
                    }
                    val halfWidth = (lines.maxOf { paint.measureText(it) } / 2 + 2.dp.toPx())
                        .coerceAtMost(widthPx / 2)
                    val labelX = center.x.coerceIn(halfWidth, widthPx - halfWidth)
                    lines.forEachIndexed { line, text ->
                        drawContext.canvas.nativeCanvas.drawText(text, labelX, labelTop + line * lineHeight, paint)
                    }
                }
            }
            visibleCompanions.forEachIndexed { slot, item ->
                val center = point(companionPosition(slot))
                val focused = focusedCompanionId == item.id
                val brightness = (if (focusedCompanionId == null || focused) 1f else .48f) *
                    companionVisibility[slot].value
                val halo = (if (focused) 18.dp else 12.dp).toPx()
                drawCircle(Brush.radialGradient(
                    listOf(CompanionBlue.copy(alpha = .22f * brightness), Color.Transparent), center, halo
                ), radius = halo, center = center)
                drawCircle(CompanionBlue.copy(alpha = .86f * brightness),
                    (if (focused) 2.7.dp else 2.3.dp).toPx(), center)
                drawCircle(Starlight.copy(alpha = .76f * brightness), .85.dp.toPx(), center)
            }
        }
        if (interactive && !overview) Box(Modifier.matchParentSize().testTag("sky_background")
            .semantics { contentDescription = if (focusedIndex >= 0) "回到主星" else "回到整片星空" }
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onOverview))
        for (index in 0..6) {
            if (interactive && (overview || (primaryAvailable && index == selectedDay))) {
                val center = point(index)
                val dropBounds = if (overview) Modifier else Modifier.onGloballyPositioned { coordinates ->
                    updatePrimaryBounds(coordinates.boundsInRoot().takeIf {
                        it.width >= coordinates.size.width - 1f && it.height >= coordinates.size.height - 1f
                    })
                }
                Box(Modifier.offset { IntOffset(
                    (center.x - 24.dp.toPx()).roundToInt(),
                    (center.y - 24.dp.toPx()).roundToInt())
                }.size(48.dp).then(dropBounds).testTag(if (overview) "star_$index" else "primary_star").semantics {
                    contentDescription = "${ShortWeekdays[index]} · ${StarNames[index]}${if (index == todayIndex) "，今天" else ""}，完成${(ratios.getOrElse(index) { 0f } * 100).roundToInt()}%"
                }.clickable(role = Role.Button) { onDay(index) })
            }
        }
        visibleCompanions.forEachIndexed { slot, item ->
            if (interactive && (focusedIndex < 0 || item.id == focusedCompanionId)) {
                val center = point(companionPosition(slot))
                Box(Modifier.offset { IntOffset(
                    (center.x - 24.dp.toPx()).roundToInt(),
                    (center.y - 24.dp.toPx()).roundToInt())
                }.size(48.dp).testTag("companion_${item.id}").semantics {
                    contentDescription = item.title
                    stateDescription = "已完成"
                }.clickable(interactionSource = null, indication = null, role = Role.Button,
                    onClick = { onCompanion(item) }))
            }
        }
        if (interactive && !overview && focusedIndex < 0) {
            if (displayedPage > 0) TextButton(onClick = { page = displayedPage - 1 },
                modifier = Modifier.align(Alignment.BottomStart)
                    .semantics { contentDescription = "上一组伴星" }) {
                Text("‹", color = Muted, style = MaterialTheme.typography.headlineMedium)
            }
            if (displayedPage < lastPage) TextButton(onClick = { page = displayedPage + 1 },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .semantics { contentDescription = "下一组伴星" }) {
                Text("›", color = Muted, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
