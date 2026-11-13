package io.github.githuiy.starlive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.PlanItem
import io.github.githuiy.starlive.core.WeekPlan
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val CompletionHoldMillis = 1_500L

private data class DragEnd(val center: Offset, val absorbed: Boolean)

private class PlanDrag(
    val item: PlanItem, val card: Rect, val grip: Offset, pointerInRoot: Offset
) {
    var pointer by mutableStateOf(pointerInRoot)
    var end by mutableStateOf<DragEnd?>(null)
    val center get() = pointer - grip + card.center - card.topLeft
}

@Composable
fun HomeScreen(
    today: LocalDate, monday: LocalDate, week: WeekPlan,
    selectedDay: Int, onDay: (Int) -> Unit, overview: Boolean, onOverview: () -> Unit,
    reduceMotion: Boolean, onToggle: (PlanItem) -> Unit, pageScroll: ScrollState? = null,
    externalOverview: Boolean = false, focusProgress: Float = if (overview) 0f else 1f,
    onSkyBounds: (Rect) -> Unit = {}
) {
    val date = monday.plusDays(selectedDay.toLong())
    val items = week.days[selectedDay]
    val unfinished = items.filterNot { it.done }
    val reveal = rememberPlanReveal(reduceMotion, date, overview)
    var focusId by remember(date, overview) { mutableStateOf<String?>(null) }
    var companionDetailId by remember(date, overview) { mutableStateOf<String?>(null) }
    var primaryBounds by remember(date, overview) { mutableStateOf<Rect?>(null) }
    var activeDrag by remember(date, overview) { mutableStateOf<PlanDrag?>(null) }
    var homeOrigin by remember { mutableStateOf(Offset.Zero) }
    val drag = activeDrag
    val landing = drag?.end
    val absorbingId = drag?.item?.id?.takeIf { landing?.absorbed == true }
    val companions = items.filter { it.done && it.id != absorbingId }
    val focusedCompanion = companions.firstOrNull { it.id == focusId }
    val latestToggle by rememberUpdatedState(onToggle)
    val absorption = remember(drag) { Animatable(0f) }
    val dropTargetHighlighted by remember(drag) {
        derivedStateOf {
            drag != null && (drag.end?.absorbed
                ?: (primaryBounds?.contains(drag.pointer) == true))
        }
    }
    val haptics = LocalHapticFeedback.current

    fun closeCompanion() {
        companionDetailId = null
        focusId = null
    }

    LaunchedEffect(focusedCompanion?.id, reduceMotion) {
        if (focusedCompanion != null) {
            if (!reduceMotion) delay(CompanionFocusMillis.toLong())
            companionDetailId = focusedCompanion.id
        } else {
            companionDetailId = null
        }
    }
    BackHandler(enabled = focusedCompanion != null, onBack = ::closeCompanion)

    LaunchedEffect(drag?.takeIf { it.end == null }) {
        if (drag != null && drag.end == null && primaryBounds?.isEmpty != false && pageScroll != null) {
            if (reduceMotion) pageScroll.scrollTo(0)
            else pageScroll.animateScrollTo(0, tween(340))
        }
    }
    LaunchedEffect(landing, drag, reduceMotion) {
        if (drag != null && landing != null) {
            if (!reduceMotion) {
                absorption.animateTo(1f, tween(if (landing.absorbed) 280 else 180,
                    easing = FastOutSlowInEasing))
            }
            activeDrag = null
        }
    }

    fun releaseDrag(allowDrop: Boolean) {
        val moving = activeDrag ?: return
        if (moving.end != null) return
        val target = primaryBounds?.takeUnless { it.isEmpty }
        val destination = target?.takeIf { allowDrop && it.contains(moving.pointer) }
        moving.end = DragEnd(destination?.center ?: moving.card.center + homeOrigin, destination != null)
        if (destination != null) latestToggle(moving.item)
    }

    val ratios = week.days.map { day ->
        if (day.isEmpty()) 0f else day.count { it.done }.toFloat() / day.size
    }
    val todayIndex = if (today in monday..monday.plusDays(6)) today.dayOfWeek.value - 1 else -1
    val mapHeight by animateDpAsState(if (overview && !externalOverview) 348.dp else 212.dp,
        tween(if (reduceMotion) 0 else 800), label = "sky-height")
    val focusedOpacity = if (externalOverview) focusedSkyOpacity(focusProgress) else 1f
    val viewport = if (externalOverview) Modifier.onGloballyPositioned { coordinates ->
        onSkyBounds(Rect(coordinates.positionInRoot(),
            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
    } else Modifier

    Box(Modifier.fillMaxWidth().onGloballyPositioned { homeOrigin = it.positionInRoot() }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (overview && !externalOverview) {
                Text(weekRange(monday), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            val mapTag = when {
                !overview -> Modifier.testTag("focused_sky")
                !externalOverview -> Modifier.testTag("overview")
                else -> Modifier
            }
            Box(Modifier.fillMaxWidth().height(mapHeight).then(viewport).then(mapTag)) {
                if (!externalOverview || !overview || focusProgress > 0f) {
                    WeekStarMap(ratios, todayIndex, selectedDay, overview && !externalOverview, reduceMotion, onDay,
                        onOverview = {
                            if (focusedCompanion != null) closeCompanion() else onOverview()
                        }, modifier = Modifier.matchParentSize().graphicsLayer { alpha = focusedOpacity },
                        companions = if (overview) emptyList() else companions,
                        focusedCompanionId = focusedCompanion?.id,
                        onCompanion = {
                            if (activeDrag == null) focusId = it.id
                        },
                        onPrimaryBounds = { primaryBounds = it },
                        highlightPrimary = dropTargetHighlighted, interactive = !externalOverview || !overview)
                }
                if (overview && externalOverview) {
                    Text(weekRange(monday), color = Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.graphicsLayer { alpha = 1f - focusProgress })
                }
                if (!overview) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                    .graphicsLayer { alpha = focusedOpacity },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ShortWeekdays[selectedDay], color = Gold, style = MaterialTheme.typography.labelSmall)
                    Text(StarNames[selectedDay], style = MaterialTheme.typography.headlineMedium)
                    Box(Modifier.width(84.dp).height(2.dp).background(Line)) {
                        Box(Modifier.fillMaxWidth(ratios[selectedDay]).height(2.dp).background(Gold))
                    }
                }
            }
            if (!overview) {
                SkyCard(Modifier.testTag("day_panel").graphicsLayer { alpha = focusedOpacity }) {
                    Text("${if (date == today) "今天 · " else ""}${date.monthValue} 月 ${date.dayOfMonth} 日",
                        color = Gold.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
                    if (unfinished.isEmpty()) {
                        Text(if (items.isEmpty()) "当天暂无计划" else "当天计划已完成", color = Muted)
                    }
                    unfinished.chunked(2).forEach { row ->
                        key(date, row.first().id) {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { item ->
                                    key(item.id) {
                                        DraggablePlanTile(item, Modifier.weight(1f).fillMaxHeight(),
                                            hidden = drag?.item?.id == item.id,
                                            flash = { reveal.progress(item.id) },
                                            onDetail = {
                                                if (activeDrag == null) reveal.requestOpen(item.id)
                                            },
                                            onAccessibleComplete = {
                                                if (activeDrag == null) latestToggle(item)
                                            },
                                            onPickup = { bounds, grip, pointer ->
                                                if (activeDrag != null) false
                                                else {
                                                    activeDrag = PlanDrag(item, bounds.translate(-homeOrigin), grip, pointer)
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    true
                                                }
                                            },
                                            onMove = { activeDrag?.pointer = it },
                                            onDrop = { releaseDrag(allowDrop = true) },
                                            onCancel = { releaseDrag(allowDrop = false) })
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        if (drag != null) {
            Box(Modifier.matchParentSize()) {
                DraggedPlanTile(drag, homeOrigin, absorption)
            }
        }
    }

    val detail = if (focusedCompanion?.id == companionDetailId && focusedCompanion != null) focusedCompanion
        else unfinished.firstOrNull { it.id == reveal.openId }
    detail?.let { item ->
        val close = if (item.done) ::closeCompanion else reveal::close
        StarDetailDialog(item.title, item.category, "plan_detail", onDismiss = close,
            actions = {
                if (item.done) TextButton(onClick = {
                    closeCompanion()
                    latestToggle(item)
                }) { Text("恢复为未完成") }
            }) {
            Text("${date.monthValue} 月 ${date.dayOfMonth} 日 · ${if (item.done) "已完成" else "未完成"}",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(item.body.ifBlank { "暂无正文" }, color = if (item.body.isBlank()) Muted else Ink)
        }
    }
}

@Composable
private fun DraggedPlanTile(drag: PlanDrag, homeOrigin: Offset, absorption: Animatable<Float, *>) {
    val density = LocalDensity.current
    PlanTile(drag.item.title, drag.item.category,
        Modifier.offset {
            val target = drag.end?.center ?: drag.center
            val center = lerp(drag.center, target, absorption.value) - homeOrigin
            IntOffset((center.x - drag.card.width / 2).roundToInt(), (center.y - drag.card.height / 2).roundToInt())
        }.size(with(density) { drag.card.width.toDp() }, with(density) { drag.card.height.toDp() })
            .graphicsLayer {
                val shrink = if (drag.end?.absorbed == true) absorption.value else 0f
                scaleX = 1.035f - shrink
                scaleY = scaleX
                alpha = 1f - shrink
                shadowElevation = 9.dp.toPx() * alpha
            }.clearAndSetSemantics { })
}

@Composable
private fun DraggablePlanTile(item: PlanItem, modifier: Modifier, hidden: Boolean, flash: () -> Float,
    onDetail: () -> Unit, onAccessibleComplete: () -> Unit,
    onPickup: (Rect, Offset, Offset) -> Boolean, onMove: (Offset) -> Unit,
    onDrop: () -> Unit, onCancel: () -> Unit) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val showDetail by rememberUpdatedState(onDetail)
    val pickup by rememberUpdatedState(onPickup)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)

    PlanTile(item.title, item.category, modifier.testTag("plan_${item.id}")
        .graphicsLayer { alpha = if (hidden) 0f else 1f }
        .onGloballyPositioned { coordinates = it }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            stateDescription = "未完成"
            onClick("查看计划详情") {
                onDetail()
                true
            }
            onLongClick("标记完成") {
                onAccessibleComplete()
                true
            }
        }
        .pointerInput(item.id) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var latestPosition = down.position
                val armed = withTimeoutOrNull(CompletionHoldMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull false
                        if (change.isConsumed || event.changes.count { it.pressed } > 1) {
                            return@withTimeoutOrNull false
                        }
                        val elapsed = change.uptimeMillis - down.uptimeMillis
                        if (!change.pressed) {
                            change.consume()
                            if (elapsed < CompletionHoldMillis) showDetail()
                            return@withTimeoutOrNull false
                        }
                        latestPosition = change.position
                        if (elapsed >= CompletionHoldMillis) {
                            change.consume()
                            return@withTimeoutOrNull true
                        }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            return@withTimeoutOrNull false
                        }
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.any { it.isConsumed }) return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                if (armed == false) return@awaitEachGesture

                val layout = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val bounds = Rect(layout.positionInRoot(), Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                if (!pickup(bounds, down.position, layout.localToRoot(latestPosition))) return@awaitEachGesture
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!layout.isAttached || change.isConsumed || event.changes.count { it.pressed } > 1) break
                        change.consume()
                        move(layout.localToRoot(change.position))
                        if (!change.pressed) {
                            released = true
                            drop()
                            break
                        }
                    }
                } finally {
                    if (!released) cancel()
                }
            }
        }, done = false, flash = flash)
}
