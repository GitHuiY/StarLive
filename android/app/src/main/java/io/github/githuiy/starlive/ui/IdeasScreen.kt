package io.github.githuiy.starlive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.WeekPlan
import java.time.LocalDate

data class LifeIdea(val title: String, val category: String)
private data class IdeaTarget(val week: String, val day: Int)
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
fun IdeasScreen(today: LocalDate, monday: LocalDate, offset: Int, onOffset: (Int) -> Unit,
    day: Int, onDay: (Int) -> Unit, draft: WeekPlan,
    onAdd: (String, Int, String, String, String) -> Unit, onPlanner: () -> Unit,
    reduceMotion: Boolean = false) {
    var category by rememberSaveable { mutableStateOf("全部") }
    var custom by remember { mutableStateOf<IdeaTarget?>(null) }
    var detail by remember { mutableStateOf<IdeaDetail?>(null) }
    val reveal = rememberPlanReveal(reduceMotion, category)
    val ideas = LifeIdeas.filter { category == "全部" || it.category == category }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkyCard(containerColor = Night.copy(alpha = .68f)) {
            Text("加入草稿的日期", style = MaterialTheme.typography.bodyMedium)
            WeekPicker(offset, onOffset)
            Text(weekRange(monday), color = Gold, style = MaterialTheme.typography.bodySmall)
            DayPicker(monday, day, today, onDay)
            TextButton(onClick = onPlanner) { Text("查看周计划草稿 ↗") }
        }
        CategoryPicker(category, { category = it }, includeAll = true)
        Button(onClick = { custom = IdeaTarget(monday.toString(), day) }, modifier = Modifier.fillMaxWidth()
            .sizeIn(minHeight = 54.dp).testTag("idea_custom")) { Text("＋ 写下自己的小计划") }
        // The host page already scrolls vertically. These rows keep both columns in that scroll.
        ideas.withIndex().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (index, idea) ->
                    val cardId = index.toString()
                    PlanTile(idea.title, idea.category, Modifier.weight(1f).fillMaxHeight()
                        .testTag("idea_card_$index").clickable(role = Role.Button) {
                            if (reveal.requestOpen(cardId)) {
                                detail = IdeaDetail(index, idea, IdeaTarget(monday.toString(), day))
                            }
                        }, containerColor = Night.copy(alpha = .82f), flash = { reveal.progress(cardId) })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    detail?.takeIf { reveal.openId == it.index.toString() }?.let { selected ->
        val (week, targetDay) = selected.target
        val targetAvailable = week == monday.toString()
        val exists = targetAvailable && draft.days[targetDay].any { it.title == selected.idea.title }
        val date = LocalDate.parse(week).plusDays(targetDay.toLong())
        var body by rememberSaveable(selected.index, week, targetDay) { mutableStateOf("") }
        var submitted by remember(selected) { mutableStateOf(false) }
        val canAdd = targetAvailable && !exists && !submitted
        StarDetailDialog(title = selected.idea.title, category = selected.idea.category,
            tag = "idea_detail", onDismiss = {
                reveal.close()
                detail = null
            }, actions = { close ->
                Button(onClick = {
                    if (!submitted) {
                        submitted = true
                        onAdd(week, targetDay, selected.idea.title, selected.idea.category, body)
                        close()
                    }
                }, enabled = canAdd, modifier = Modifier.testTag("idea_add_${selected.index}")) {
                    Text(if (exists) "已加入${Weekdays[targetDay]}草稿 ✓" else "＋ 加入${Weekdays[targetDay]}草稿")
                }
            }) {
            Text("${date.monthValue} 月 ${date.dayOfMonth} 日 · ${Weekdays[targetDay]}", color = Ink)
            OutlinedTextField(value = body,
                onValueChange = { if (it.length <= BackupCodec.MAX_BODY_LENGTH) body = it },
                enabled = canAdd, label = { Text("正文（可选）") }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("idea_body"))
            if (!targetAvailable) {
                Text("所选周已变化，请重新打开这条灵感。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    custom?.let { target ->
        TaskEditorDialog("", "喜欢", false, { custom = null }) { title, selectedCategory, body ->
            onAdd(target.week, target.day, title, selectedCategory, body)
            custom = null
        }
    }
}
