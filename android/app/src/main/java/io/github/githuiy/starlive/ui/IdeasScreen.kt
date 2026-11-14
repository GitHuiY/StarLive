package io.github.githuiy.starlive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.BackupCodec
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
    day: Int?, onDay: (Int) -> Unit, draft: WeekPlan,
    onAdd: (String, Int, String, String, String) -> Unit,
    modifier: Modifier = Modifier, reduceMotion: Boolean = false,
    onOverview: () -> Unit = {}, notice: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    confirmedWeek: (LocalDate) -> WeekPlan = { WeekPlan.empty() }) {
    var category by rememberSaveable { mutableStateOf("全部") }
    var custom by remember { mutableStateOf<CustomIdea?>(null) }
    var detail by remember { mutableStateOf<IdeaDetail?>(null) }
    val slide = rememberSparksWeekSlide(monday, reduceMotion)
    val weekReady = slide.settled
    val target = day?.let { IdeaTarget(monday, it) }
        ?.takeIf { weekReady && PlanningHorizon.includes(today, it.date) }
    val focused = target != null
    val reveal = rememberPlanReveal(reduceMotion, category, focused)
    val ideas = LifeIdeas.filter { category == "全部" || it.category == category }
    var lastDay by remember { mutableIntStateOf(day ?: 0) }
    SideEffect { target?.let { lastDay = it.day } }
    val focusDay = target?.day ?: lastDay
    val focusProgress by animateFloatAsState(if (focused) 1f else 0f,
        tween(if (reduceMotion) 0 else if (focused) 800 else 1100, easing = FastOutSlowInEasing),
        label = "sparks-day-camera")
    var focusBounds by remember { mutableStateOf<Rect?>(null) }
    var nameAnchor by remember { mutableStateOf<Offset?>(null) }
    val confirmed = confirmedWeek(monday)
    val ratios = remember(confirmed) { weekCompletionRatios(confirmed) }
    val todayIndex = if (monday == mondayOf(today)) today.dayOfWeek.value - 1 else -1
    BoxWithConstraints(modifier.fillMaxSize()) {
        val top = contentPadding.calculateTopPadding()
        val bottom = contentPadding.calculateBottomPadding()
        val bodyHeight = (maxHeight - top - bottom - 24.dp).coerceAtLeast(0.dp)
        val focusHeight = homeDaySkyHeight(maxWidth, bodyHeight)
        val railWidth = (maxWidth * .185f).coerceIn(58.dp, 76.dp)
        val columns = if (maxWidth >= 400.dp) 2 else 1
        val shortLandscape = bodyHeight < 235.dp && maxWidth >= 440.dp
        val density = LocalDensity.current
        val rise = with(density) { 28.dp.roundToPx() }
        val widthPx = constraints.maxWidth.toFloat()
        val overviewBottom = with(density) {
            landingSkyBottom(widthPx, constraints.maxHeight.toFloat(), 24.dp.toPx()).toDp()
        }
        val heightBeforePicker = (overviewBottom - top - 12.dp).coerceAtLeast(0.dp)
        HomeDaySky(ratios, todayIndex, focusDay, overview = !focused, reduceMotion = reduceMotion,
            onDay = {}, onOverview = onOverview,
            modifier = Modifier.padding(top = top + 12.dp).fillMaxWidth().padding(horizontal = 20.dp)
                .height(focusHeight).testTag("sparks_focused_sky").onGloballyPositioned { coordinates ->
                    focusBounds = Rect(coordinates.positionInRoot(),
                        Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                },
            focusProgress = focusProgress,
            companions = if (focused) confirmed.days[focusDay].filter { it.done } else emptyList(),
            onStarNameAnchor = { nameAnchor = it },
            backgroundTag = "sparks_sky_background", primaryTag = "sparks_selected_date",
            primaryDescription = sparksDateDescription(monday, focusDay, today),
            showWeekday = false,
            focusLabel = {
                if (target != null) {
                    Text(sparksIdeaDate(target, today, showWeekday = false), color = Gold,
                        modifier = Modifier.padding(top = 4.dp).testTag("sparks_plan_date")
                            .graphicsLayer { alpha = focusProgress },
                        style = MaterialTheme.typography.bodySmall)
                }
            })
        Column(Modifier.fillMaxSize().padding(contentPadding).imePadding().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (notice != null) SmallNotice(notice, Modifier.padding(horizontal = 20.dp))
            if (!focused) {
                Spacer(Modifier.height(heightBeforePicker))
                val visibleMonday = if (slide.fraction < .5f) slide.monday else slide.nextMonday
                SparksWeekPicker(today, visibleMonday, onWeekSelected, enabled = weekReady && focusProgress < .05f)
            } else {
                Spacer(Modifier.height(focusHeight))
            }
            AnimatedVisibility(visible = focused,
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                enter = if (reduceMotion) EnterTransition.None else
                    fadeIn(tween(260)) + slideInVertically(tween(430, easing = FastOutSlowInEasing)) { rise },
                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(170))) {
                val addCustom = {
                    if (target != null) custom = CustomIdea(target, category.takeUnless { it == "全部" })
                }
                val categoryRail: @Composable (Modifier) -> Unit = { railModifier ->
                    SparksCategories(category, { category = it }, railModifier.testTag("idea_categories"))
                }
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ideas.withIndex().chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (index, idea) ->
                                    val cardId = index.toString()
                                    PlanTile(idea.title, idea.category, Modifier.weight(1f).fillMaxHeight()
                                        .testTag("idea_card_$index").clickable(enabled = focused, role = Role.Button) {
                                            if (target != null && reveal.requestOpen(cardId)) {
                                                detail = IdeaDetail(index, idea, target)
                                            }
                                        }, filledCategory = true, flash = { reveal.progress(cardId) })
                                }
                                if (row.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (shortLandscape) {
                        SparksAddButton(addCustom)
                        categoryRail(Modifier.width(railWidth).fillMaxHeight())
                    } else {
                        Column(Modifier.width(railWidth).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            categoryRail(Modifier.fillMaxWidth().weight(1f))
                            SparksAddButton(addCustom, Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        if (slide.settled) {
            MondayLandingSky(ratios, todayIndex, onDay, Modifier.matchParentSize(),
                overview = !focused && focusProgress < .05f, selectedDay = focusDay,
                focusBounds = focusBounds, focusProgress = focusProgress, reduceMotion = reduceMotion,
                starNameAnchor = nameAnchor, showFocusedWeekday = false,
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
        val exists = targetAvailable && draft.days[targetDay].any { it.title == selected.idea.title }
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
                    Text(if (exists) "已加入${Weekdays[targetDay]}草稿 ✓" else "＋ 加入${Weekdays[targetDay]}草稿")
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
