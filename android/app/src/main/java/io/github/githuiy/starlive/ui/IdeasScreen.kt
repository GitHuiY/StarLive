package io.github.githuiy.starlive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.WeekPlan
import java.time.LocalDate

data class LifeIdea(val title: String, val category: String)
private data class IdeaTarget(val week: String, val day: Int)
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
    onAdd: (String, Int, String, String) -> Unit, onPlanner: () -> Unit) {
    var category by rememberSaveable { mutableStateOf("全部") }
    var custom by remember { mutableStateOf<IdeaTarget?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
        PageHeading("A LITTLE INSPIRATION", "给生活，\n添一点喜欢。",
            "不急着完成。先把让你心动的小事，放进一周的期待里。")
        SkyCard {
            Text("有一点喜欢，就值得留点时间。", style = MaterialTheme.typography.titleLarge)
            Text("安排在哪一周、哪一天？", color = Muted)
            WeekPicker(offset, onOffset)
            Text(weekRange(monday), color = Gold, style = MaterialTheme.typography.bodySmall)
            DayPicker(monday, day, today, onDay)
            Text("选择的小事只会加入指定日的草稿；确认周计划后，才出现在首页。", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onPlanner) { Text("查看周计划草稿 ↗") }
        }
        CategoryPicker(category, { category = it }, includeAll = true)
        LifeIdeas.filter { category == "全部" || it.category == category }.forEachIndexed { index, idea ->
            val exists = draft.days[day].any { it.title == idea.title }
            SkyCard {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryMark(idea.category)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(idea.title, style = MaterialTheme.typography.bodyLarge)
                        Text(idea.category, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                OutlinedButton(onClick = { onAdd(monday.toString(), day, idea.title, idea.category) },
                    enabled = !exists, modifier = Modifier.fillMaxWidth().testTag("idea_add_$index")) {
                    Text(if (exists) "已加入${Weekdays[day]}草稿 ✓" else "＋ 加入${Weekdays[day]}草稿")
                }
            }
        }
        Button(onClick = { custom = IdeaTarget(monday.toString(), day) }, modifier = Modifier.fillMaxWidth()
            .sizeIn(minHeight = 54.dp).testTag("idea_custom")) { Text("＋ 写下自己的小计划") }
    }
    custom?.let { target ->
        TaskEditorDialog("", "喜欢", false, { custom = null }) { title, selectedCategory ->
            onAdd(target.week, target.day, title, selectedCategory)
            custom = null
        }
    }
}
