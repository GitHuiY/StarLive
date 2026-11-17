package io.github.githuiy.starlive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.core.PlanningHorizon
import java.time.LocalDate

@Composable
internal fun SparksWeekPicker(today: LocalDate, selectedWeek: LocalDate, onWeekSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, menuHeight: Dp = 200.dp) {
    var expanded by remember(selectedWeek) { mutableStateOf(false) }
    val isOpen = enabled && expanded
    val first = PlanningHorizon.firstMonday(today)
    val last = PlanningHorizon.lastMonday(today)
    val weeks = remember(first, last) {
        generateSequence(first) { it.plusWeeks(1).takeUnless { next -> next.isAfter(last) } }.toList()
    }
    val density = LocalDensity.current
    val rowHeight = maxOf(48.dp, with(density) { 22.sp.toDp() } + 12.dp)
    val shape = RoundedCornerShape(14.dp)
    BackHandler(enabled = isOpen) { expanded = false }
    Column(modifier.widthIn(max = 234.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(modifier = Modifier.fillMaxWidth(),
            shape = shape, color = Night.copy(alpha = .68f), contentColor = Ink,
            border = BorderStroke(1.dp, Ink.copy(alpha = .22f))) {
            Box(Modifier.fillMaxWidth().height(rowHeight).testTag("sparks_week_picker")
                .semantics { stateDescription = if (isOpen) "周次列表已展开" else "周次列表已收起" }
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = !expanded },
                contentAlignment = Alignment.Center) {
                WeekLabel(selectedWeek, today, first, if (enabled) Gold else Muted, emphasized = true)
                Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(12.dp, 8.dp)) {
                    val edge = if (isOpen) size.height * .70f else size.height * .30f
                    val middle = size.height - edge
                    drawLine(Ink, Offset(1.dp.toPx(), edge), Offset(size.width / 2, middle),
                        strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Ink, Offset(size.width / 2, middle), Offset(size.width - 1.dp.toPx(), edge),
                        strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        if (isOpen) {
            Surface(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                .height(minOf(menuHeight, rowHeight * weeks.size)),
                shape = shape, color = PanelSoft.copy(alpha = .66f),
                border = BorderStroke(1.dp, Ink.copy(alpha = .22f))) {
                BoxWithConstraints(Modifier.fillMaxWidth().testTag("sparks_week_menu")) {
                    val selectedIndex = weeks.indexOf(selectedWeek).coerceAtLeast(0)
                    val rowPx = with(density) { rowHeight.roundToPx() }
                    val viewportPx = constraints.maxHeight
                    val maxScroll = (rowPx * weeks.size - viewportPx).coerceAtLeast(0)
                    val centeredScroll = (selectedIndex * rowPx - (viewportPx - rowPx) / 2)
                        .coerceIn(0, maxScroll)
                    val scroll = rememberScrollState(centeredScroll)
                    Column(Modifier.fillMaxWidth().selectableGroup().verticalScroll(scroll)) {
                        weeks.forEach { monday ->
                            val isSelected = monday == selectedWeek
                            Box(Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) Gold.copy(alpha = .10f) else Color.Transparent)
                                .testTag("sparks_week_$monday")
                                .selectable(selected = isSelected, role = Role.RadioButton, onClick = {
                                    expanded = false
                                    if (!isSelected) onWeekSelected(monday)
                                }), contentAlignment = Alignment.Center) {
                                WeekLabel(monday, today, first, if (isSelected) Gold else Ink,
                                    emphasized = isSelected)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekLabel(monday: LocalDate, today: LocalDate, first: LocalDate, color: Color,
    emphasized: Boolean) {
    val suffix = when {
        monday.isBefore(first) -> "  ·  历史"
        monday == first -> "  ·  本周"
        monday == first.plusWeeks(1) -> "  ·  下周"
        else -> ""
    }
    BasicText(sparksWeekDateRange(monday, today) + suffix,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        style = MaterialTheme.typography.labelLarge.copy(color = color, textAlign = TextAlign.Center,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal),
        softWrap = false, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp))
}

internal fun sparksWeekDateRange(monday: LocalDate, today: LocalDate): String =
    "${sparksWeekEndpointLabel(monday, today)} — ${sparksWeekEndpointLabel(monday.plusDays(6), today)}"

private fun sparksWeekEndpointLabel(date: LocalDate, today: LocalDate): String =
    if (date.year < today.year) "${date.year}.${sparksDateLabel(date, today)}" else sparksDateLabel(date, today)

internal fun sparksDateLabel(date: LocalDate, today: LocalDate): String {
    val yearPrefix = if (date.year == today.year + 1) "+1 " else ""
    return "$yearPrefix${date.monthValue}.${date.dayOfMonth}"
}
