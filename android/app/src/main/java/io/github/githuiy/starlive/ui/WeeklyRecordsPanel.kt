package io.github.githuiy.starlive.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.WeekPlan
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private val EnergyInk = Color(0xFFF1F4FA)
private val EnergySecondary = Color(0xFFDBE4F0)
private val EnergyGold = Color(0xFFFFE7B4)
private val EnergyShape = RoundedCornerShape(16.dp)
private val GripHeights = listOf(17.dp, 12.dp, 7.dp)

@Composable
internal fun WeeklyRecordsPanel(weeks: Map<String, WeekPlan>, currentMonday: LocalDate,
    selectedMonday: LocalDate, overview: Boolean, expanded: Boolean, reduceMotion: Boolean,
    onExpandedChange: (Boolean) -> Unit, onWeekSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier) {
    val target = if (!overview) 2f else if (expanded) 0f else 1f
    val position = remember { Animatable(target) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val progress = dragPosition ?: position.value

    LaunchedEffect(overview) {
        if (!overview) dragging = false
    }
    LaunchedEffect(target, dragging, reduceMotion) {
        if (!dragging) {
            dragPosition?.let { position.snapTo(it) }
            dragPosition = null
            if (reduceMotion) position.snapTo(target)
            else position.animateTo(target, tween(300, easing = FastOutSlowInEasing))
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val safeRight = with(density) { WindowInsets.systemGestures.getRight(this, direction).toDp() }
        val gripWidth = 38.dp
        val edgeWidth = safeRight + gripWidth
        val panelWidth = (maxWidth * .56f).coerceAtMost(244.dp)
        val panelHeight = (maxHeight * .37f).coerceAtMost(260.dp)
        val margin = 16.dp
        val hiddenDistance = with(density) { (panelWidth + margin + 2.dp).toPx() }
        val collapsedDistance = with(density) { (panelWidth + margin - edgeWidth).toPx() }.coerceAtLeast(1f)
        val translation = progress.coerceIn(0f, 1f) * collapsedDistance +
            (progress - 1f).coerceIn(0f, 1f) * (hiddenDistance - collapsedDistance)
        val canRead = overview && expanded
        val drag = Modifier.draggable(
            state = rememberDraggableState { delta ->
                dragPosition = ((dragPosition ?: position.value) + delta / collapsedDistance).coerceIn(0f, 1f)
            },
            orientation = Orientation.Horizontal, enabled = overview,
            onDragStarted = {
                dragPosition = progress.coerceIn(0f, 1f)
                dragging = true
            },
            onDragStopped = { velocity ->
                if (overview) {
                    val offset = dragPosition ?: position.value
                    val flingThreshold = with(density) { 500.dp.toPx() }
                    onExpandedChange(if (abs(velocity) > flingThreshold) velocity < 0f else offset < .5f)
                }
                dragging = false
            }
        )

        if (canRead) Box(Modifier.fillMaxSize().testTag("weekly_records_backdrop")
            .clickable(interactionSource = null, indication = null, onClick = { onExpandedChange(false) })
            .clearAndSetSemantics {})
        if (progress < 2f) Surface(
            modifier = Modifier.align(Alignment.BottomEnd).absolutePadding(right = margin)
                .absoluteOffset { IntOffset(translation.roundToInt(), 0) }
                .width(panelWidth).height(panelHeight)
                .then(drag)
                .then(if (canRead) Modifier.testTag("weekly_records_panel") else Modifier.clearAndSetSemantics {}),
            color = Night.copy(alpha = .60f), contentColor = EnergyInk, shape = EnergyShape,
            border = BorderStroke(1.dp, EnergySecondary.copy(alpha = .26f))
        ) {
            Row {
                Box(Modifier.width(gripWidth).fillMaxHeight().clickable(
                    enabled = overview, interactionSource = null, indication = null, role = Role.Button,
                    onClickLabel = if (expanded) "收起每周能量" else "展开每周能量",
                    onClick = { onExpandedChange(!expanded) })
                    .testTag(if (expanded) "weekly_records_grip" else "weekly_records_edge")
                    .semantics { contentDescription = if (expanded) "收起每周能量" else "展开每周能量" },
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.width(18.dp).height(20.dp)) {
                        GripHeights.forEachIndexed { index, height ->
                            val x = size.width * (.2f + index * .3f)
                            val half = height.toPx() / 2f
                            drawLine(EnergyGold.copy(alpha = .78f), Offset(x, center.y - half),
                                Offset(x, center.y + half), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
                if (canRead || progress < 1f) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(top = 9.dp, bottom = 8.dp, end = 10.dp)) {
                        Text("Weekly Energy", color = EnergyInk, fontFamily = FontFamily.Serif,
                            style = MaterialTheme.typography.titleMedium)
                        val savedWeeks = remember(weeks, currentMonday) {
                            weeks.entries.mapNotNull { (key, plan) ->
                                val monday = LocalDate.parse(key)
                                if (monday.isAfter(currentMonday)) null else monday to plan
                            }.toMap()
                        }
                        val oldest = minOf(currentMonday.minusWeeks(2), savedWeeks.keys.minOrNull() ?: currentMonday)
                        val count = ChronoUnit.WEEKS.between(oldest, currentMonday).toInt() + 1
                        LazyColumn(Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            items(count, key = { currentMonday.minusWeeks(it.toLong()).toString() }) { index ->
                                val monday = currentMonday.minusWeeks(index.toLong())
                                val plan = savedWeeks[monday]
                                val total = plan?.days?.sumOf { it.size } ?: 0
                                val complete = plan?.days?.sumOf { day -> day.count { it.done } } ?: 0
                                val progress = if (total == 0) 0f else complete.toFloat() / total
                                WeekEnergyRow(monday, progress, index, selectedMonday == monday, canRead) {
                                    onWeekSelected(monday)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekEnergyRow(monday: LocalDate, progress: Float, weeksAgo: Int, selected: Boolean,
    enabled: Boolean, onSelect: () -> Unit) {
    val label = when (weeksAgo) {
        0 -> "本周"
        1 -> "上周"
        else -> null
    }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().background(if (selected) EnergyGold.copy(alpha = .09f) else Color.Transparent, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
        .testTag("weekly_energy_$monday").padding(horizontal = 5.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label ?: weekRange(monday), Modifier.weight(1f), color = EnergyInk,
                style = MaterialTheme.typography.labelMedium)
            Text("${(progress * 100).roundToInt()}%", color = EnergyGold, style = MaterialTheme.typography.labelMedium)
        }
        if (label != null) Text(weekRange(monday), color = EnergySecondary, style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = EnergyGold, trackColor = EnergySecondary.copy(alpha = .26f), strokeCap = StrokeCap.Round,
            gapSize = 0.dp, drawStopIndicator = {})
    }
}
