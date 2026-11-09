package io.github.githuiy.starlive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import io.github.githuiy.starlive.core.BackupCodec

val Categories = listOf("家务", "运动", "学习", "阅读", "社交", "探索", "休息", "交流思考", "喜欢")
fun categoryColor(category: String): Color = when (category) {
    "家务" -> Color(0xFFBBA987)
    "运动" -> Color(0xFF9FB5A4)
    "学习" -> Color(0xFFA4ADC3)
    "阅读" -> Color(0xFFC0B196)
    "社交", "交流思考" -> Color(0xFFB9A3B7)
    "探索" -> Color(0xFF91B5BC)
    "休息" -> Color(0xFFC5B692)
    else -> Color(0xFFC3A3A0)
}
fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
fun weekRange(monday: LocalDate): String = "${monday.format(DateTimeFormatter.ofPattern("M.d"))} — ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("M.d"))}"

@Composable
fun SkyCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = .88f)),
        border = BorderStroke(1.dp, Line.copy(alpha = .65f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun PageHeading(eyebrow: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(eyebrow, color = Gold.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun WeekPicker(offset: Int, onOffset: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("本周", "下周").forEachIndexed { index, label ->
            FilterChip(selected = offset == index, onClick = { onOffset(index) },
                label = { Text(label) }, modifier = Modifier.sizeIn(minHeight = 48.dp)
                    .testTag(if (index == 0) "week_this" else "week_next"))
        }
    }
}

@Composable
fun DayPicker(monday: LocalDate, selected: Int, today: LocalDate,
    onDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(selected, monday, scroll.maxValue) {
        scroll.scrollTo(with(density) { (selected * 55).dp.roundToPx() })
    }
    Row(modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        (0..6).forEach { day ->
            val date = monday.plusDays(day.toLong())
            Surface(color = if (selected == day) Gold else Panel,
                contentColor = if (selected == day) Night else Muted,
                shape = RoundedCornerShape(28.dp),
                border = if (date == today && selected != day) BorderStroke(1.dp, Gold.copy(alpha = .5f)) else null,
                modifier = Modifier.testTag("day_$day").semantics { contentDescription = "${Weekdays[day]}，${date.monthValue}月${date.dayOfMonth}日${if (date == today) "，今天" else ""}" }
                    .clickable { onDay(day) }.sizeIn(minWidth = 48.dp, minHeight = 66.dp)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Weekdays[day].removePrefix("周"), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(5.dp))
                    Text(date.dayOfMonth.toString(), fontWeight = if (selected == day) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun CategoryPicker(selected: String, onSelected: (String) -> Unit, includeAll: Boolean = false) {
    val choices = if (includeAll) listOf("全部") + Categories
        else if (selected !in Categories) listOf(selected.take(BackupCodec.MAX_CATEGORY_LENGTH)) + Categories
        else Categories
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        choices.forEach { category ->
            FilterChip(selected == category, onClick = { onSelected(category) }, label = { Text(category) },
                modifier = Modifier.sizeIn(minHeight = 48.dp))
        }
    }
}

@Composable
fun CategoryMark(category: String, modifier: Modifier = Modifier) {
    Surface(color = categoryColor(category).copy(alpha = .10f), shape = RoundedCornerShape(12.dp),
        modifier = modifier) {
        Text(when (category) {
            "家务" -> "⌂"; "运动" -> "↗"; "学习" -> "◇"; "阅读" -> "▤"
            "社交" -> "◌"; "探索" -> "✧"; "休息" -> "☾"; "交流思考" -> "∞"; else -> "♡"
        }, color = categoryColor(category), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp))
    }
}

@Composable
fun TaskEditorDialog(initialTitle: String, initialCategory: String, editing: Boolean,
    onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(initialTitle) }
    var category by remember { mutableStateOf(initialCategory) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (editing) "调整这份期待" else "留一件想做的小事") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { if (it.length <= BackupCodec.MAX_TITLE_LENGTH) title = it },
                    label = { Text("想做什么") }, minLines = 2,
                    supportingText = { Text("${title.length}/${BackupCodec.MAX_TITLE_LENGTH} · 保存在草稿中") })
                Text("选择类别", color = Muted)
                CategoryPicker(category, { category = it })
                if (editing) Text("改名会重置该事项的完成状态；调整类别和排序会保留完成状态。",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        },
        confirmButton = { Button(onClick = { onSave(title.trim(), category) }, enabled = title.isNotBlank()) {
            Text("保存到草稿")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun SmallNotice(text: String, modifier: Modifier = Modifier) {
    Surface(color = Gold.copy(alpha = .07f), border = BorderStroke(1.dp, Gold.copy(alpha = .16f)),
        shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, color = Gold.copy(alpha = .9f), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(13.dp))
    }
}
