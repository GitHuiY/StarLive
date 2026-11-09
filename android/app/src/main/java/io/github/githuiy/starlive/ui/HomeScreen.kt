package io.github.githuiy.starlive.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.core.PlanItem
import io.github.githuiy.starlive.core.WeekPlan
import java.time.LocalDate

private val DayTitles = listOf("轻轻开始，新的一周。", "动一动，让自己舒展。", "给好奇心，一点时间。",
    "和喜欢的人，靠近一点。", "把平常，过得有趣一点。", "去看看，新的风景。", "给自己，一点留白。")
private val DayIntros = listOf("整理一点身边的空间，也给心情留一个新起点。", "不追赶任何目标，只感受身体慢慢醒来。",
    "翻开一页书，学一点新东西。慢慢来也很好。", "一句问候，一次散步。让连接发生在日常里。",
    "为这一周留一点小小的期待，不需要特别的理由。", "走出熟悉的路线，跟着今天的好奇心走。", "不用把休息变成任务。今天，舒服地度过就好。")
private val StarEnglish = listOf("DUBHE", "MERAK", "PHECDA", "MEGREZ", "ALIOTH", "MIZAR", "ALKAID")

@Composable
fun HomeScreen(
    today: LocalDate, monday: LocalDate, week: WeekPlan, confirmed: Boolean,
    offset: Int, onOffset: (Int) -> Unit, selectedDay: Int, onDay: (Int) -> Unit,
    overview: Boolean, onOverview: () -> Unit, introPending: Boolean,
    demoMode: Boolean, onDemoMode: () -> Unit, reduceMotion: Boolean,
    onToggle: (PlanItem) -> Unit, onPlanner: () -> Unit
) {
    val ratios = week.days.map { items -> if (items.isEmpty()) 0f else items.count { it.done }.toFloat() / items.size }
    val todayIndex = if (today >= monday && today <= monday.plusDays(6)) today.dayOfWeek.value - 1 else -1
    val mapHeight by animateDpAsState(if (overview) 360.dp else 230.dp,
        tween(if (reduceMotion) 0 else 800), label = "sky-height")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                Text("每一天，都有自己的光", color = Ink.copy(alpha = .85f),
                    style = MaterialTheme.typography.labelLarge)
                Text("${weekRange(monday)}  ·  北斗 / 一周", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!overview) TextButton(onClick = onOverview, modifier = Modifier.testTag("mini_map").semantics {
                contentDescription = "点击缩略星图，返回完整一周星图"
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WeekStarMap(ratios, todayIndex, selectedDay, true, true, {},
                        Modifier.size(92.dp, 44.dp), miniature = true)
                    Text("回到整片星空", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            WeekPicker(offset, onOffset)
            TextButton(onClick = onDemoMode, modifier = Modifier.testTag("mode_toggle")) {
                Text(if (demoMode) "回到我的计划" else "体验示例", color = Gold)
            }
        }
        if (demoMode) SmallNotice("示例体验 · 以下安排不是真实日程，完成状态与个人计划完全分开。")
        Box(Modifier.fillMaxWidth().height(mapHeight).testTag(if (overview) "overview" else "focused_sky")) {
            WeekStarMap(ratios, todayIndex, selectedDay, overview, reduceMotion, onDay,
                Modifier.matchParentSize())
            if (!overview) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("0${selectedDay + 1} / ${Weekdays[selectedDay]}", color = Gold,
                    style = MaterialTheme.typography.labelSmall)
                Text(StarNames[selectedDay], style = MaterialTheme.typography.headlineMedium)
                Text(StarEnglish[selectedDay], color = Muted, style = MaterialTheme.typography.labelSmall)
                Box(Modifier.width(84.dp).height(2.dp).background(Line)) {
                    Box(Modifier.fillMaxWidth(ratios[selectedDay]).height(2.dp).background(Gold))
                }
            }
        }
        if (overview) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("七颗星，一周的期待", style = MaterialTheme.typography.titleLarge, color = Ink)
                Text(if (introPending) "停留片刻，自动走进今天" else "轻触一颗星，看看那一天的期待",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                if (!confirmed && !demoMode) {
                    Text("还没有确认${if (offset == 0) "本周" else "下周"}计划，先留一点期待吧。",
                        style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Button(onClick = onPlanner) { Text("制定${if (offset == 0) "本周" else "下周"}计划") }
                }
            }
        } else {
            SkyCard(Modifier.testTag("day_panel")) {
                DayPicker(monday, selectedDay, today, onDay)
                val date = monday.plusDays(selectedDay.toLong())
                Text("${if (date == today) "今天 · " else ""}${date.monthValue} 月 ${date.dayOfMonth} 日 / ${Weekdays[selectedDay]}",
                    color = Gold.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
                Text(DayTitles[selectedDay], style = MaterialTheme.typography.headlineMedium)
                Text(DayIntros[selectedDay], color = Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                val items = week.days[selectedDay]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (demoMode) "示例：提前安排好的一天" else "这一周，亲手留好的期待",
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${items.count { it.done }} / ${items.size}", color = Gold,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (items.isEmpty()) Text(if (confirmed) "这一天，给自己一点留白。\n按自己的节奏度过就好。"
                    else "还没有制定这一周的计划。\n先为一周留一点期待吧。",
                    modifier = Modifier.padding(vertical = 22.dp), color = Muted)
                items.forEach { item -> CompletionCard(item, { onToggle(item) }) }
                Text("◌  每完成一件小事，你的星就亮一点。", color = Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Text(if (demoMode) "示例周计划 · 非真实日程" else "我的周计划 · 仅保存在本机",
                    color = Muted.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onPlanner) { Text(if (confirmed) "调整我的周计划 ↗" else "制定我的周计划 ↗") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(Gold))
                Spacer(Modifier.width(7.dp))
                Text("按你的节奏，慢慢点亮", color = Muted.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
            }
            Text("把日子还给自己", color = Ink.copy(alpha = .7f),
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Serif)
        }
    }
}

@Composable
private fun CompletionCard(item: PlanItem, onToggle: () -> Unit) {
    Surface(color = if (item.done) Gold.copy(alpha = .045f) else PanelSoft,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, if (item.done) Gold.copy(alpha = .24f) else Line.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .testTag("plan_${item.id}")
            .semantics { contentDescription = "${if (item.done) "取消完成" else "完成"}：${item.title}" }
            .toggleable(value = item.done, role = Role.Checkbox) { onToggle() }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            CategoryMark(item.category)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, color = if (item.done) Ink else Muted,
                    style = MaterialTheme.typography.bodyLarge)
                Text(item.category, color = if (item.done) categoryColor(item.category) else Muted.copy(alpha = .65f),
                    style = MaterialTheme.typography.bodySmall)
            }
            Checkbox(checked = item.done, onCheckedChange = null)
        }
    }
}
