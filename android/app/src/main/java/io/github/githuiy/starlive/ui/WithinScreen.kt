package io.github.githuiy.starlive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.data.WithinCategory
import io.github.githuiy.starlive.data.WithinDomain
import io.github.githuiy.starlive.data.WithinEssay
import io.github.githuiy.starlive.data.WithinRecordType
import io.github.githuiy.starlive.data.WithinRepository
import io.github.githuiy.starlive.data.WithinSource
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

private val WithinCardColor = Night.copy(alpha = .73f)

internal val WithinCategory.label: String
    get() = when (this) {
        WithinCategory.SELF -> "自我"
        WithinCategory.WORLD -> "世界"
        WithinCategory.MOMENT -> "瞬间"
        WithinCategory.ECHO -> "回响"
        WithinCategory.FOOTPRINT -> "足迹"
        WithinCategory.BEYOND -> "远方"
    }

internal val WithinCategory.description: String
    get() = when (this) {
        WithinCategory.SELF -> "对自己的理解、感受和变化"
        WithinCategory.WORLD -> "见闻、阅读和对外界的思考"
        WithinCategory.MOMENT -> "值得留下的人、事、风景和美好时刻"
        WithinCategory.ECHO -> "他人的评价、鼓励与触动"
        WithinCategory.FOOTPRINT -> "完成的事、作品和个人成果"
        WithinCategory.BEYOND -> "未来规划、愿望和正在探索的方向"
    }

@Composable
internal fun WithinScreen(contentPadding: PaddingValues, today: LocalDate, reduceMotion: Boolean) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { WithinRepository(context) }
    var readResult by remember { mutableStateOf<Result<List<WithinEssay>>?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf<WithinCategory?>(null) }
    var readingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    val listScroll = rememberLazyListState()
    val essays = readResult?.getOrNull()
    val currentCategory = category
    val essay = essays?.firstOrNull { it.id == readingId }

    LaunchedEffect(repository, loadAttempt) {
        readResult = try {
            Result.success(repository.load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Result.failure(failed)
        }
    }
    LaunchedEffect(essays, readingId) {
        if (essays != null && readingId != null && essay == null) {
            readingId = null
            editorOpen = false
            deleteOpen = false
        }
    }

    BackHandler(enabled = readingId != null || currentCategory != null) {
        if (readingId != null) readingId = null else category = null
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .padding(contentPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val skyHeight = (maxWidth * .94f).coerceIn(240.dp, 314.dp)
            .coerceAtMost((maxHeight - 6.dp).coerceAtLeast(160.dp))
        val body = Modifier.widthIn(max = 560.dp).fillMaxSize()
        val listPadding = PaddingValues(top = 4.dp, bottom = 18.dp)
        if (readingId != null && essay != null) {
            WithinEssayReader(
                essay = essay,
                onBack = { readingId = null },
                onEdit = { editorOpen = true },
                onDelete = { deleteOpen = true },
                modifier = body,
                contentPadding = listPadding,
            )
        } else {
            LazyColumn(
                body.testTag("within_essays"),
                state = listScroll,
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "sky") {
                    WithinGeminiSky(
                        selected = currentCategory,
                        onSelect = { category = it },
                        reduceMotion = reduceMotion,
                        modifier = Modifier.fillMaxWidth().height(skyHeight)
                    )
                }
                if (currentCategory == null) {
                    item(key = "motto") {
                        Text("向内看见自己，向外遇见世界。", Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = .82f))
                    }
                } else {
                    item(key = "return") {
                        TextButton(onClick = { category = null }, modifier = Modifier.testTag("within_all_stars")) {
                            Text("‹  返回完整星图")
                        }
                    }
                }
                item(key = "section") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(currentCategory?.label ?: "全部随笔", Modifier.testTag("within_list_title").semantics { heading() },
                                color = Ink, style = MaterialTheme.typography.titleLarge)
                            currentCategory?.let {
                                Text(it.description, color = Ink.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = { editorOpen = true }, enabled = essays != null,
                            modifier = Modifier.testTag("within_add")) { Text("+  随笔") }
                    }
                }
                if (essays == null) {
                    item(key = "loading") {
                        SkyCard(containerColor = WithinCardColor) {
                            if (readResult?.isFailure == true) {
                                Text("暂时读不到随笔，原文件已保留。", color = Ink)
                                TextButton(onClick = { readResult = null; loadAttempt++ }) { Text("重试") }
                            } else {
                                Text("正在打开随笔…", color = Ink)
                            }
                        }
                    }
                } else {
                    val visible = if (currentCategory == null) essays else essays.filter { currentCategory in it.draft.categories }
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            SkyCard(Modifier.testTag("within_empty"), containerColor = WithinCardColor) {
                                Text("这里还是空的。想留下什么时，写一句就好。",
                                    color = Ink.copy(alpha = .85f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    items(visible, key = { "essay-${it.id}" }) { entry ->
                        SkyCard(
                            Modifier.testTag("within_essay_${entry.id}")
                                .clickable(role = Role.Button) { readingId = entry.id },
                            containerColor = WithinCardColor
                        ) {
                            EssayMetadata(entry)
                            Text(entry.draft.body, color = Ink, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (editorOpen && essays != null && (readingId == null || essay != null)) {
        WithinEssayEditor(initial = essay, defaultCategory = currentCategory, today = today,
            onDismiss = { editorOpen = false }, onSave = { draft ->
                readResult = Result.success(repository.save(essay?.id, draft))
            })
    }
    if (deleteOpen && essay != null) {
        WithinDeleteDialog(onDismiss = { deleteOpen = false }, onDelete = {
            readResult = Result.success(repository.delete(essay.id))
            readingId = null
        })
    }
}

@Composable
private fun WithinEssayReader(
    essay: WithinEssay, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    modifier: Modifier, contentPadding: PaddingValues
) {
    LazyColumn(modifier.testTag("within_reader"), contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.testTag("within_reader_back")) { Text("‹  返回随笔") }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag("within_edit")) { Text("编辑随笔") }
                TextButton(onClick = onDelete, modifier = Modifier.testTag("within_remove")) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            SkyCard(containerColor = WithinCardColor) {
                EssayMetadata(essay)
                Text(essay.draft.body, Modifier.testTag("within_essay_body"), color = Ink,
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
        essay.legacy?.let { legacy ->
            item {
                SkyCard(Modifier.testTag("within_legacy"), containerColor = WithinCardColor) {
                    Text("原画像", color = Gold, style = MaterialTheme.typography.titleMedium)
                    Text("${legacy.domain.legacyLabel} · ${legacy.type.legacyLabel}", color = Ink,
                        style = MaterialTheme.typography.bodyMedium)
                    legacy.example?.let {
                        Text("例子或依据", color = Gold, style = MaterialTheme.typography.labelLarge)
                        Text(it, color = Ink, style = MaterialTheme.typography.bodyMedium)
                    }
                    legacy.source?.let {
                        Text("来源 · ${it.legacyLabel}", color = Ink, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EssayMetadata(essay: WithinEssay) {
    val categories = WithinCategory.entries.filter { it in essay.draft.categories }
    Text(essay.draft.date?.toString() ?: "日期未记录", color = Gold, style = MaterialTheme.typography.labelLarge)
    if (categories.isNotEmpty()) {
        Text(categories.joinToString(" · ") { it.label }, color = Ink.copy(alpha = .85f),
            style = MaterialTheme.typography.bodySmall)
    }
}

private val WithinDomain.legacyLabel: String
    get() = when (this) {
        WithinDomain.ABILITY -> "能力与实践"
        WithinDomain.LEARNING -> "学习与成长"
        WithinDomain.RELATIONSHIPS -> "表达与关系"
        WithinDomain.INTERESTS -> "兴趣与热爱"
        WithinDomain.WELLBEING -> "生活与状态"
        WithinDomain.VALUES -> "价值与方向"
    }

private val WithinRecordType.legacyLabel: String
    get() = when (this) {
        WithinRecordType.TRAIT -> "我的特点"
        WithinRecordType.STRENGTH -> "我的优势"
        WithinRecordType.IMPROVEMENT -> "想要改善"
        WithinRecordType.EXPLORATION -> "还在探索"
    }

private val WithinSource.legacyLabel: String
    get() = when (this) {
        WithinSource.SELF -> "我的判断"
        WithinSource.FEEDBACK -> "他人的反馈"
        WithinSource.AI -> "AI 的观察"
    }
