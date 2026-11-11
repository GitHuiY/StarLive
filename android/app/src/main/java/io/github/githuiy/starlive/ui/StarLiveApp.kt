package io.github.githuiy.starlive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.core.PlanEngine
import io.github.githuiy.starlive.data.BackupPreview
import io.github.githuiy.starlive.data.PlanRepository
import java.time.LocalDate
import kotlinx.coroutines.delay

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StarLiveApp(data: AppState?, loading: Boolean, error: String?, today: LocalDate,
    timezone: String, reduceMotion: Boolean, repository: PlanRepository,
    notice: String?, onNoticeConsumed: () -> Unit, backupBusy: Boolean,
    backupPreview: BackupPreview?, restoring: Boolean, onCancelPreview: () -> Unit,
    onConfirmPreview: () -> Unit, onExport: () -> Unit, onImport: () -> Unit,
    onRestore: () -> Unit, onCalendarTick: () -> Unit,
    openMonitorRequest: Int, monitoringContent: @Composable () -> Unit) {
    var page by rememberSaveable { mutableStateOf("stars") }
    var offset by rememberSaveable { mutableStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf(today.dayOfWeek.value - 1) }
    var overview by rememberSaveable { mutableStateOf(true) }
    var introConsumed by rememberSaveable { mutableStateOf(false) }
    var followToday by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val scroll = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val monday = mondayOf(today).plusWeeks(offset.toLong())
    val weekKey = monday.toString()

    fun navigate(target: String) { introConsumed = true; page = target }
    fun chooseDay(day: Int) {
        introConsumed = true; selectedDay = day; overview = false
        followToday = offset == 0 && day == today.dayOfWeek.value - 1
    }
    fun chooseWeek(value: Int) {
        introConsumed = true; offset = value; overview = true; followToday = value == 0
    }
    fun chooseEditorDay(value: Int) {
        introConsumed = true; selectedDay = value
        followToday = offset == 0 && value == today.dayOfWeek.value - 1
    }
    LaunchedEffect(openMonitorRequest) {
        if (openMonitorRequest > 0) {
            navigate("space")
            scroll.scrollTo(0)
        }
    }
    LaunchedEffect(data != null, page, introConsumed, today) {
        if (data != null && page == "stars" && !introConsumed) {
            delay(2500)
            selectedDay = today.dayOfWeek.value - 1
            overview = false; followToday = true; introConsumed = true
        }
    }
    LaunchedEffect(today, timezone) {
        if (followToday && offset == 0) selectedDay = today.dayOfWeek.value - 1
    }
    LaunchedEffect(page) { scroll.scrollTo(0) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(30_000); onCalendarTick() }
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) { snackbar.showSnackbar(notice); onNoticeConsumed() }
    }
    BackHandler(enabled = page != "stars" || !overview) {
        introConsumed = true
        if (page != "stars") page = "stars"
        overview = true
    }
    StarBackdrop(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = Ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).sizeIn(minHeight = 54.dp).clickable {
                        navigate("stars"); overview = true
                    }, verticalArrangement = Arrangement.Center) {
                        Text("✦  七日星图", fontFamily = FontFamily.Serif, fontSize = 23.sp, color = Ink)
                        Text("SEVEN DAYS, YOUR WAY", style = MaterialTheme.typography.labelSmall,
                            color = Muted.copy(alpha = .75f))
                    }
                    OutlinedButton(onClick = { navigate("planner") },
                        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("open_planner")) { Text("周计划 ↗") }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Night.copy(alpha = .96f), windowInsets = WindowInsets.navigationBars) {
                    listOf(Triple("stars", "✦", "Starmap"), Triple("ideas", "✧", "Sparks"),
                        Triple("space", "☾", "Daylight")).forEach { (route, icon, label) ->
                        NavigationBarItem(selected = page == route, onClick = { navigate(route) },
                            icon = { Text(icon, fontSize = 23.sp, fontFamily = FontFamily.Serif) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("nav_$route"))
                    }
                }
            }, snackbarHost = { SnackbarHost(snackbar) }) { inner ->
            Column(Modifier.fillMaxSize().padding(inner).imePadding().verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (error != null) SmallNotice(error)
                when {
                    loading && data == null -> {
                        Spacer(Modifier.height(60.dp)); CircularProgressIndicator(color = Gold)
                        Text("正在打开本机计划…", color = Muted)
                    }
                    data == null -> SkyCard {
                        Text("先把原来的期待找回来。", style = MaterialTheme.typography.titleLarge)
                        Text("本地计划暂时无法读取，原文件已保留。请先选择有效备份预览恢复。", color = Muted)
                        Button(onClick = onImport, enabled = !backupBusy) { Text("选择备份并预览") }
                        TextButton(onClick = onRestore, enabled = !backupBusy) { Text("预览上次覆盖前的备份") }
                    }
                    page == "stars" -> HomeScreen(today, monday, PlanEngine.confirmed(data, weekKey),
                        selectedDay, ::chooseDay, overview, { introConsumed = true; overview = true },
                        reduceMotion, onToggle = { item ->
                            repository.setCompletion(weekKey, selectedDay, item.id, !item.done)
                        }, pageScroll = scroll)
                    page == "ideas" -> IdeasScreen(today, monday, offset, ::chooseWeek,
                        selectedDay, ::chooseEditorDay, PlanEngine.draft(data, weekKey), repository::addDraftItem,
                        onPlanner = { navigate("planner") }, reduceMotion = reduceMotion)
                    page == "planner" -> PlannerScreen(today, monday, offset, ::chooseWeek,
                        selectedDay, ::chooseEditorDay, PlanEngine.draft(data, weekKey), data.weeks.containsKey(weekKey),
                        onIdeas = { navigate("ideas") }, onAdd = repository::addDraftItem,
                        onUpdate = repository::updateDraftItem, onDelete = repository::deleteDraftItem,
                        onMoveBy = repository::moveDraftItemBy,
                        onMoveToDay = repository::moveDraftItemToDay, onConfirm = { confirmedWeek ->
                            repository.confirmDraft(confirmedWeek)
                            page = "stars"; overview = false
                            offset = if (confirmedWeek == mondayOf(today).toString()) 0 else 1
                        })
                    page == "space" -> SpaceScreen(data.settings, timezone, { transform -> repository.updateSettings(transform) },
                        backupBusy, onExport, onImport, onRestore, monitoringContent)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        backupPreview?.let { preview ->
            val summary = preview.summary
            AlertDialog(onDismissRequest = { if (!backupBusy) onCancelPreview() },
                title = { Text(if (restoring) "预览恢复上次备份" else "预览导入的备份") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("已确认周计划：${summary.weekCount} 周\n草稿：${summary.draftWeekCount} 周\n正式事项：${summary.itemCount} 条\n草稿事项：${summary.draftItemCount} 条\n已完成：${summary.completedItemCount} 条")
                    Text(if (preview.firstWeek == null) "日期范围：无周计划"
                        else "周起始日期：${preview.firstWeek} — ${preview.lastWeek}", color = Gold)
                    val settings = preview.settings
                    Text("将恢复的提醒规则\n连续：${settings.continuousMinutes} 分钟 · ${if (settings.continuousEnabled) "开启" else "关闭"}\n单应用每日：${settings.perAppDailyMinutes} 分钟 · ${if (settings.perAppDailyEnabled) "开启" else "关闭"}\n每日合计：${settings.totalDailyMinutes} 分钟 · ${if (settings.totalDailyEnabled) "开启" else "关闭"}",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("确认后将覆盖本机计划、草稿和提醒设置。覆盖前会保留一份本机备份，可手动恢复。",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                } }, confirmButton = { Button(onClick = onConfirmPreview, enabled = !backupBusy,
                    modifier = Modifier.testTag("backup_confirm")) { Text(if (backupBusy) "正在恢复…" else "确认覆盖并恢复") } },
                dismissButton = { TextButton(onClick = onCancelPreview, enabled = !backupBusy) { Text("取消，保留现有数据") } })
        }
    }
}
