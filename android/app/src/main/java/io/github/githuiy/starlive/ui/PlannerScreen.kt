package io.github.githuiy.starlive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.PlanItem
import io.github.githuiy.starlive.core.WeekPlan
import java.time.LocalDate

private data class EditTarget(val week: String, val day: Int, val item: PlanItem?)
private data class ItemTarget(val week: String, val day: Int, val index: Int, val item: PlanItem)

@Composable
fun PlannerScreen(
    today: LocalDate, monday: LocalDate, offset: Int, onOffset: (Int) -> Unit,
    day: Int, onDay: (Int) -> Unit, draft: WeekPlan, confirmed: Boolean, onIdeas: () -> Unit,
    onAdd: (String, Int, String, String, String) -> Unit,
    onUpdate: (String, Int, String, String, String, String) -> Unit,
    onDelete: (String, Int, String) -> Unit,
    onMoveBy: (String, Int, String, Int) -> Unit,
    onMoveToDay: (String, Int, String, Int) -> Unit,
    onConfirm: (String) -> Unit
) {
    var editor by remember { mutableStateOf<EditTarget?>(null) }
    var deleting by remember { mutableStateOf<ItemTarget?>(null) }
    var moving by remember { mutableStateOf<ItemTarget?>(null) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val week = monday.toString()
    Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
        PageHeading("A WEEK TO LOOK FORWARD TO", "先给一周，\n留一点期待。",
            "在一周开始或结束时，亲手安排。日常只需回来，慢慢完成。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WeekPicker(offset, onOffset)
            TextButton(onClick = onIdeas) { Text("去生活灵感挑一挑 ↗") }
        }
        Text("${if (offset == 0) "本周" else "下周"} · ${weekRange(monday)}", color = Gold)
        SmallNotice("草稿自动保存在本机，确认后才更新首页。未完成事项留在原日期，不自动移到下一周。")
        DayPicker(monday, day, today, onDay)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${Weekdays[day]} · ${StarNames[day]}", style = MaterialTheme.typography.titleLarge)
            Text("${draft.days[day].size} 份期待", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (draft.days[day].isEmpty()) SkyCard {
            Text("这一天还没有草稿。", color = Muted)
            Text("留白也算一种计划，无需填满每一天。", color = Muted,
                style = MaterialTheme.typography.bodySmall)
        }
        draft.days[day].forEachIndexed { index, item ->
            SkyCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryMark(item.category)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title.ifBlank { "尚未填写的事项" },
                            color = if (item.done) Ink else Muted,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(item.category + if (item.done) " · 已完成" else " · 未完成",
                            color = if (item.done) categoryColor(item.category) else Muted.copy(alpha = .75f),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { editor = EditTarget(week, day, item) },
                    modifier = Modifier.fillMaxWidth().testTag("planner_edit_${item.id}")) { Text("编辑事项") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onMoveBy(week, day, item.id, -1) },
                        enabled = index > 0, modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                            contentDescription = "上移：${item.title}"
                        }) { Text("↑ 上移") }
                    TextButton(onClick = { onMoveBy(week, day, item.id, 1) },
                        enabled = index < draft.days[day].lastIndex,
                        modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                            contentDescription = "下移：${item.title}"
                        }) { Text("↓ 下移") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { moving = ItemTarget(week, day, index, item) }) { Text("移到其他日") }
                    TextButton(onClick = { deleting = ItemTarget(week, day, index, item) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error.copy(alpha = .85f))
                    }
                }
            }
        }
        OutlinedButton(onClick = { editor = EditTarget(week, day, null) },
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 54.dp).testTag("planner_add")) {
            Text("＋ 给${Weekdays[day]}留一点期待")
        }
        Text("留白也算一种计划。无需填满每一天。", color = Muted,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { confirming = week },
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 54.dp).testTag("planner_confirm")) {
            Text(if (confirmed) "确认调整后的${if (offset == 0) "本周" else "下周"}计划" else "确认这一周的计划  ✓")
        }
        Text("只保存在本机。修改草稿不会立即覆盖首页。", color = Muted,
            style = MaterialTheme.typography.bodySmall)
    }
    editor?.let { target ->
        TaskEditorDialog(target.item?.title.orEmpty(), target.item?.category ?: "喜欢",
            target.item != null, { editor = null }, initialBody = target.item?.body.orEmpty()) { title, category, body ->
            if (target.item == null) onAdd(target.week, target.day, title, category, body)
            else onUpdate(target.week, target.day, target.item.id, title, category, body)
            editor = null
        }
    }
    deleting?.let { target ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条草稿？") },
            text = { Text("“${target.item.title}”会从草稿中删除，已确认计划在再次确认前保持原样。") },
            confirmButton = { TextButton(onClick = {
                onDelete(target.week, target.day, target.item.id); deleting = null
            }) { Text("删除草稿") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
    moving?.let { target ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text("移到哪一天？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("移到其他日期会重置该事项的完成状态。", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
                Weekdays.forEachIndexed { index, label ->
                    TextButton(onClick = {
                        onMoveToDay(target.week, target.day, target.item.id, index)
                        moving = null
                    }, enabled = index != target.day, modifier = Modifier.fillMaxWidth()) {
                        Text("$label · ${StarNames[index]}")
                    }
                }
            } }, confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("取消") } })
    }
    confirming?.let { target ->
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text("确认这一周的安排？") },
            text = { Text("首页将显示这份手动计划。之后仍可编辑草稿，再次确认后更新。空白的日子会保留为自由时间。") },
            confirmButton = { Button(onClick = { onConfirm(target); confirming = null },
                modifier = Modifier.testTag("planner_confirm_dialog")) { Text("确认计划") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("再看看") } })
    }
}
