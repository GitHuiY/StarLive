package io.github.githuiy.starlive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.PlanItem
import io.github.githuiy.starlive.core.PlanningHorizon
import io.github.githuiy.starlive.core.WeekPlan
import java.time.LocalDate
import kotlin.math.roundToInt

data class LifeIdea(val title: String, val category: String)
private data class IdeaTarget(val monday: LocalDate, val day: Int) {
    val date: LocalDate get() = monday.plusDays(day.toLong())
}
private data class CustomIdea(val target: IdeaTarget, val category: String?)
private data class IdeaDetail(val index: Int, val idea: LifeIdea, val target: IdeaTarget)
private data class SparksPlanEdit(val target: IdeaTarget, val item: PlanItem)
val LifeIdeas = listOf(
    LifeIdea("给房间一个小小整理", "家务"), LifeIdea("认真做一顿喜欢的饭", "家务"),
    LifeIdea("散步，看看路边的树", "运动"), LifeIdea("让身体舒展 15 分钟", "运动"),
    LifeIdea("学一点一直好奇的东西", "学习"), LifeIdea("练习一个新技能", "学习"),
    LifeIdea("读几页想读的书", "阅读"), LifeIdea("去书店随手翻一本书", "阅读"),
    LifeIdea("和朋友好好聊聊天", "社交"), LifeIdea("给在意的人打个电话", "社交"),
    LifeIdea("探索一条没走过的路", "探索"), LifeIdea("去附近的小店坐坐", "探索"),
    LifeIdea("听一张喜欢的专辑", "休息"), LifeIdea("发会儿呆，什么也不做", "休息"),
    LifeIdea("和人或 AI 交流最近的思考", "交流思考")
)

@Composable
fun IdeasScreen(today: LocalDate, monday: LocalDate, onWeekSelected: (LocalDate) -> Unit,
    day: Int?, onDay: (Int) -> Unit, plans: WeekPlan,
    onAdd: (String, Int, String, String, String) -> Unit,
    modifier: Modifier = Modifier, reduceMotion: Boolean = false,
    onOverview: () -> Unit = {}, notice: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    confirmedWeek: (LocalDate) -> WeekPlan = { WeekPlan.empty() },
    onOpenPlanner: ((LocalDate, Int) -> Unit)? = null,
    onUpdate: ((String, Int, String, String, String, String) -> Unit)? = null,
    onDelete: ((String, Int, String) -> Unit)? = null) {
    var category by rememberSaveable { mutableStateOf("全部") }
    var custom by remember { mutableStateOf<CustomIdea?>(null) }
    var detail by remember { mutableStateOf<IdeaDetail?>(null) }
    var editing by remember { mutableStateOf<SparksPlanEdit?>(null) }
    val slide = rememberSparksWeekSlide(monday, reduceMotion)
    val weekReady = slide.settled
    val currentMonday = mondayOf(today)
    val historical = monday < currentMonday
    val availableWeeks = firstSparksBrowsingWeek(today)..PlanningHorizon.lastMonday(today)
    val target = day?.takeIf { it in 0..6 && weekReady && monday in availableWeeks }
        ?.let { IdeaTarget(monday, it) }
    val focused = target != null
    var configuredRequested by rememberSaveable(monday.toString(), day) { mutableStateOf(false) }
    var showingConfigured by remember(monday, day) { mutableStateOf(configuredRequested) }
    val listProgress = remember(monday, day) { Animatable(1f) }
    val recommendationScroll = rememberSaveable(monday.toString(), day, saver = ScrollState.Saver) { ScrollState(0) }
    LaunchedEffect(configuredRequested, reduceMotion, listProgress) {
        if (showingConfigured != configuredRequested) {
            if (!reduceMotion) listProgress.animateTo(0f, tween(155, easing = FastOutSlowInEasing))
            showingConfigured = configuredRequested
        }
        if (listProgress.value < 1f) {
            if (reduceMotion) listProgress.snapTo(1f)
            else listProgress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        }
    }
    val hint = if (showingConfigured) "点击星星，返回备选计划" else "点击星星，查看当天计划"
    val reveal = rememberPlanReveal(reduceMotion, category, focused)
    val ideas = LifeIdeas.filter { category == "全部" || it.category == category }
    var lastDay by remember { mutableIntStateOf(day ?: 0) }
    SideEffect { target?.let { lastDay = it.day } }
    val focusDay = target?.day ?: lastDay
    val focusProgress by animateFloatAsState(if (focused) 1f else 0f,
        tween(if (reduceMotion) 0 else if (focused) 800 else 1100, easing = FastOutSlowInEasing),
        label = "sparks-day-camera")
    var focusBounds by remember { mutableStateOf<Rect?>(null) }
    var weekdayAnchor by remember { mutableStateOf<Offset?>(null) }
    var nameAnchor by remember { mutableStateOf<Offset?>(null) }
    val confirmed = confirmedWeek(monday)
    val ratios = remember(confirmed) { weekCompletionRatios(confirmed) }
    val todayIndex = if (monday == mondayOf(today)) today.dayOfWeek.value - 1 else -1
    val overviewDayAction by rememberUpdatedState(onDay)
    val focusDayAction: (Int) -> Unit = {
        if (focused && !historical && focusProgress >= .99f) configuredRequested = !configuredRequested
    }
    val swipeWeekAction by rememberUpdatedState(onWeekSelected)
    val canSwipeWeeks = !focused && weekReady && focusProgress < .05f
    BoxWithConstraints(modifier.fillMaxSize().then(if (canSwipeWeeks) {
        Modifier.pointerInput(monday, availableWeeks, contentPadding) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val areaTop = contentPadding.calculateTopPadding().toPx()
                val areaBottom = landingSkyBottom(size.width.toFloat(), size.height.toFloat(), 24.dp.toPx())
                if (down.position.y !in areaTop..areaBottom) return@awaitEachGesture
                var distance = 0f
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    change.consume()
                    distance = overSlop
                } ?: return@awaitEachGesture
                val finished = horizontalDrag(drag.id) { change ->
                    distance += change.positionChange().x
                    change.consume()
                }
                if (!finished) return@awaitEachGesture
                val threshold = 40.dp.toPx()
                val destination = when {
                    distance <= -threshold -> monday.minusWeeks(1)
                    distance >= threshold -> monday.plusWeeks(1)
                    else -> monday
                }
                if (destination != monday && destination in availableWeeks) swipeWeekAction(destination)
            }
        }
    } else Modifier)) {
        val top = contentPadding.calculateTopPadding()
        val bottom = contentPadding.calculateBottomPadding()
        val bodyHeight = (maxHeight - top - bottom - 24.dp).coerceAtLeast(0.dp)
        val focusHeight = homeDaySkyHeight(maxWidth, bodyHeight)
        val railWidth = (maxWidth * .185f).coerceIn(58.dp, 76.dp)
        val columns = if (maxWidth >= 400.dp) 2 else 1
        val shortLandscape = bodyHeight < 310.dp && maxWidth >= 440.dp
        val density = LocalDensity.current
        val rise = with(density) { 28.dp.roundToPx() }
        val widthPx = constraints.maxWidth.toFloat()
        val overviewBottom = with(density) {
            landingSkyBottom(widthPx, constraints.maxHeight.toFloat(), 24.dp.toPx()).toDp()
        }
        val heightBeforePicker = (overviewBottom - top - 12.dp).coerceAtLeast(0.dp)
        val showingPlans = historical || showingConfigured
        val configuredUsesSidePicker = showingPlans && focused && bodyHeight < 220.dp && maxWidth >= 440.dp
        HomeDaySky(ratios, todayIndex, focusDay, overview = !focused, reduceMotion = reduceMotion,
            onDay = focusDayAction,
            onOverview = onOverview,
            modifier = Modifier.padding(top = top + 12.dp).fillMaxWidth().padding(horizontal = 20.dp)
                .height(focusHeight).testTag("sparks_focused_sky").onGloballyPositioned { coordinates ->
                    focusBounds = Rect(coordinates.positionInRoot(),
                        Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                },
            focusProgress = focusProgress,
            companions = if (focused) confirmed.days[focusDay].filter { it.done } else emptyList(),
            onWeekdayAnchor = { weekdayAnchor = it },
            onStarNameAnchor = { nameAnchor = it },
            backgroundTag = "sparks_sky_background", primaryTag = "sparks_selected_date",
            primaryDescription = sparksDateDescription(monday, focusDay, today),
            primaryClickLabel = hint,
            primaryActionEnabled = !historical,
            focusLabel = {
                if (target != null && !showingPlans) {
                    Text(sparksIdeaDate(target, today, showWeekday = false), color = Gold,
                        modifier = Modifier.padding(top = 4.dp).testTag("sparks_plan_date")
                            .graphicsLayer { alpha = focusProgress },
                        style = MaterialTheme.typography.bodySmall)
                }
            })
        if (configuredUsesSidePicker) {
            SparksWeekPicker(today, monday, onWeekSelected,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = top + 16.dp, end = 20.dp)
                    .widthIn(max = 188.dp),
                enabled = showingConfigured == configuredRequested,
                menuHeight = (focusHeight - 24.dp).coerceAtLeast(48.dp))
        }
        Column(Modifier.fillMaxSize().padding(contentPadding).imePadding().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (notice != null) SmallNotice(notice, Modifier.padding(horizontal = 20.dp))
            if (!focused) {
                Spacer(Modifier.height(heightBeforePicker))
                val visibleMonday = if (slide.fraction < .5f) slide.monday else slide.nextMonday
                SparksWeekPicker(today, visibleMonday, onWeekSelected, enabled = weekReady && focusProgress < .05f)
            } else {
                Spacer(Modifier.height(focusHeight))
                if (!historical) {
                    Text(hint, color = Ink.copy(alpha = .84f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp)
                            .testTag("sparks_focus_hint").graphicsLayer { alpha = focusProgress })
                }
            }
            AnimatedVisibility(visible = focused,
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                enter = if (reduceMotion) EnterTransition.None else
                    fadeIn(tween(260)) + slideInVertically(tween(430, easing = FastOutSlowInEasing)) { rise },
                exit = ExitTransition.None) {
                val switchingLists = showingConfigured != configuredRequested
                val listMotion = Modifier.graphicsLayer {
                    alpha = listProgress.value
                    val direction = if (showingConfigured) -1f else 1f
                    translationY = direction * rise * (1f - listProgress.value)
                }
                if (showingPlans && target != null) {
                    val canEditWeek = !historical && monday <= currentMonday.plusWeeks(1)
                    val edit = onOpenPlanner?.takeIf { canEditWeek }
                    SparksConfiguredPlans(target, today, plans.days[target.day], onWeekSelected,
                        weekPickerEnabled = !switchingLists,
                        onEdit = edit?.let { action -> { action(target.monday, target.day) } },
                        onEditItem = onUpdate?.takeUnless { historical }
                            ?.let { { item -> editing = SparksPlanEdit(target, item) } },
                        modifier = listMotion, compact = configuredUsesSidePicker, showCompletion = historical)
                } else if (focused) {
                    val controlsEnabled = focused && !switchingLists
                    SparksRecommendations(ideas, category, { category = it }, columns, railWidth,
                        shortLandscape, controlsEnabled, reveal, recommendationScroll,
                        onAdd = {
                            if (target != null && controlsEnabled)
                                custom = CustomIdea(target, category.takeUnless { it == "全部" })
                        },
                        onIdea = { index, idea ->
                            if (target != null && reveal.requestOpen(index.toString())) {
                                detail = IdeaDetail(index, idea, target)
                            }
                        }, modifier = Modifier.fillMaxSize().then(listMotion))
                }
            }
        }
        if (slide.settled) {
            MondayLandingSky(ratios, todayIndex, { overviewDayAction(it) }, Modifier.matchParentSize(),
                overview = !focused && focusProgress < .05f, selectedDay = focusDay,
                focusBounds = focusBounds, focusProgress = focusProgress, reduceMotion = reduceMotion,
                weekdayAnchor = weekdayAnchor, starNameAnchor = nameAnchor,
                overviewTag = "sparks_overview", starTag = "sparks_star_",
                dateDescription = { sparksDateDescription(monday, it, today) })
        } else {
            val travel = slide.fraction * slide.direction
            WeeksInMotion(slide.monday, today, confirmedWeek, Modifier.matchParentSize().absoluteOffset {
                IntOffset((-widthPx * travel).roundToInt(), 0)
            })
            if (slide.fraction > 0f) {
                WeeksInMotion(slide.nextMonday, today, confirmedWeek, Modifier.matchParentSize().absoluteOffset {
                    IntOffset((widthPx * (slide.direction - travel)).roundToInt(), 0)
                })
            }
        }
    }
    detail?.takeIf { reveal.openId == it.index.toString() }?.let { selected ->
        val (week, targetDay) = selected.target
        val targetAvailable = week == monday && PlanningHorizon.includes(today, selected.target.date)
        val exists = targetAvailable && plans.days[targetDay].any { it.title.trim() == selected.idea.title }
        var body by rememberSaveable(selected.index, week, targetDay) { mutableStateOf("") }
        var submitted by remember(selected) { mutableStateOf(false) }
        val canAdd = targetAvailable && !exists && !submitted
        StarDetailDialog(title = selected.idea.title, category = selected.idea.category,
            tag = "idea_detail", onDismiss = {
                reveal.close()
                detail = null
            }, actions = { close ->
                Button(onClick = {
                    if (canAdd) {
                        submitted = true
                        onAdd(week.toString(), targetDay, selected.idea.title, selected.idea.category, body)
                        close()
                    }
                }, enabled = canAdd, modifier = Modifier.testTag("idea_add_${selected.index}")) {
                    Text(if (exists) "已加入${Weekdays[targetDay]}计划 ✓" else "＋ 加入${Weekdays[targetDay]}计划")
                }
            }) {
            Text(sparksIdeaDate(selected.target, today), color = Ink)
            OutlinedTextField(value = body,
                onValueChange = { if (it.length <= BackupCodec.MAX_BODY_LENGTH) body = it },
                enabled = canAdd, label = { Text("正文（可选）") }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("idea_body"))
            if (!targetAvailable) {
                Text("所选周已变化，请重新打开这条灵感。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    custom?.let { (original, selectedCategory) ->
        TaskEditorDialog("", selectedCategory, false, { custom = null },
            fixedCategory = selectedCategory != null, dateLabel = sparksIdeaDate(original, today)) { title, chosenCategory, body ->
            onAdd(original.monday.toString(), original.day, title, chosenCategory, body)
            custom = null
        }
    }
    editing?.let { edit ->
        val week = edit.target.monday.toString()
        val item = edit.item
        TaskEditorDialog(item.title, item.category, true, onDismiss = { editing = null },
            initialBody = item.body, dateLabel = sparksIdeaDate(edit.target, today),
            onDelete = onDelete?.let { delete ->
                {
                    delete(week, edit.target.day, item.id)
                    editing = null
                }
            }) { title, chosenCategory, body ->
            onUpdate?.invoke(week, edit.target.day, item.id, title, chosenCategory, body)
            editing = null
        }
    }
}

@Composable
private fun SparksRecommendations(ideas: List<LifeIdea>, category: String, onCategory: (String) -> Unit,
    columns: Int, railWidth: Dp, shortLandscape: Boolean, enabled: Boolean, reveal: PlanRevealState, scroll: ScrollState,
    onAdd: () -> Unit, onIdea: (Int, LifeIdea) -> Unit, modifier: Modifier = Modifier) {
    val categoryRail: @Composable (Modifier) -> Unit = { railModifier ->
        SparksCategories(category, onCategory, railModifier.testTag("idea_categories"))
    }
    Row(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)
            .padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ideas.withIndex().chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (index, idea) ->
                        PlanTile(idea.title, idea.category, Modifier.weight(1f).fillMaxHeight()
                            .testTag("idea_card_$index").clickable(enabled = enabled, role = Role.Button) {
                                onIdea(index, idea)
                            }, filledCategory = true, flash = { reveal.progress(index.toString()) })
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (shortLandscape) {
            SparksAddButton(onAdd)
            categoryRail(Modifier.width(railWidth).fillMaxHeight())
        } else {
            Column(Modifier.width(railWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                categoryRail(Modifier.fillMaxWidth().weight(1f))
                SparksAddButton(onAdd, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SparksConfiguredPlans(target: IdeaTarget, today: LocalDate, plans: List<PlanItem>,
    onWeekSelected: (LocalDate) -> Unit, weekPickerEnabled: Boolean, onEdit: (() -> Unit)?,
    onEditItem: ((PlanItem) -> Unit)?, modifier: Modifier, compact: Boolean, showCompletion: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val menuHeight = (maxHeight - 52.dp).coerceIn(0.dp, 148.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!compact) {
                SparksWeekPicker(today, target.monday, onWeekSelected, enabled = weekPickerEnabled,
                    menuHeight = menuHeight)
            }
            Column(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                Column(Modifier.fillMaxSize().then(modifier).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = if (compact) 2.dp else 8.dp)
                    .testTag("sparks_configured_plans"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(Modifier.fillMaxWidth(), color = Night.copy(alpha = .68f),
                        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line.copy(alpha = .65f))) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 7.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 9.dp)) {
                            Text(sparksIdeaDate(target, today, showWeekday = false), color = Gold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("sparks_configured_date"))
                            if (plans.isEmpty()) {
                                Text("这一天还没有计划", color = Ink.copy(alpha = .85f),
                                    modifier = Modifier.testTag("sparks_configured_empty"))
                            }
                            plans.forEach { plan ->
                                val editItem = onEditItem?.let { edit -> { edit(plan) } }
                                Column(Modifier.fillMaxWidth().testTag("sparks_plan_${plan.id}")
                                    .then(if (editItem != null) Modifier.clickable(enabled = weekPickerEnabled,
                                        role = Role.Button, onClickLabel = "编辑计划", onClick = editItem) else Modifier)
                                    .sizeIn(minHeight = 48.dp).padding(vertical = 4.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    if (showCompletion) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text(plan.title.ifBlank { "尚未填写的事项" }, color = Ink,
                                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                            Text(if (plan.done) "已完成" else "未完成",
                                                color = if (plan.done) Gold else Ink.copy(alpha = .72f),
                                                modifier = Modifier.testTag("sparks_plan_status_${plan.id}"),
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                    } else {
                                        Text(plan.title.ifBlank { "尚未填写的事项" }, color = Ink,
                                            style = MaterialTheme.typography.bodyLarge)
                                    }
                                    if (plan.body.isNotBlank()) {
                                        Text(plan.body, color = Ink.copy(alpha = .82f),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (onEdit != null) {
                                TextButton(onClick = onEdit, enabled = weekPickerEnabled,
                                    modifier = Modifier.testTag("open_planner")) { Text("编辑周计划") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeksInMotion(monday: LocalDate, today: LocalDate, confirmedWeek: (LocalDate) -> WeekPlan,
    modifier: Modifier) {
    val week = confirmedWeek(monday)
    val ratios = remember(week) { weekCompletionRatios(week) }
    MondayLandingSky(ratios, if (monday == mondayOf(today)) today.dayOfWeek.value - 1 else -1,
        {}, modifier, interactive = false, reduceMotion = true)
}

private fun sparksDateDescription(monday: LocalDate, day: Int, today: LocalDate): String {
    val date = monday.plusDays(day.toLong())
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日，${Weekdays[day]}" +
        if (date == today) "，今天" else ""
}

private fun sparksIdeaDate(target: IdeaTarget, today: LocalDate, showWeekday: Boolean = true): String {
    val date = target.date
    val nextYear = if (date.year == today.year + 1) "+1 " else ""
    val weekday = if (showWeekday) " · ${Weekdays[target.day]}" else ""
    return "$nextYear${date.monthValue} 月 ${date.dayOfMonth} 日$weekday"
}

@Composable
private fun SparksAddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(onClick = onClick,
        modifier = modifier.testTag("idea_custom").semantics { contentDescription = "写下自己的小计划" },
        containerColor = Gold, contentColor = Night) {
        Text("+", style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun SparksCategories(category: String, onCategory: (String) -> Unit, modifier: Modifier) {
    Surface(modifier, color = Night.copy(alpha = .60f), contentColor = Ink,
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        border = BorderStroke(1.dp, Ink.copy(alpha = .26f))) {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (listOf("全部") + Categories).forEach { label ->
                val displayLabel = categoryLabel(label)
                val selected = category == label
                val tint = if (label == "全部") Gold else categoryColor(label)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .background(if (selected) tint.copy(alpha = .23f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onCategory(label) })
                    .testTag("idea_category_$displayLabel").sizeIn(minHeight = 48.dp).padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(tint, RoundedCornerShape(50)))
                    Text(displayLabel, color = if (selected) tint else Ink,
                        style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
