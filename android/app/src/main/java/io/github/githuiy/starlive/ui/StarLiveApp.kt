package io.github.githuiy.starlive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import io.github.githuiy.starlive.BuildConfig
import io.github.githuiy.starlive.R
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.core.PlanEngine
import io.github.githuiy.starlive.core.PlanningHorizon
import io.github.githuiy.starlive.core.WeekPlan
import io.github.githuiy.starlive.data.BackupPreview
import io.github.githuiy.starlive.data.PlanRepository
import java.time.DayOfWeek
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
    openMonitorRequest: Int, debugIntroRequest: Int = 0, allowStartupIntro: Boolean = true,
    monitoringContent: @Composable () -> Unit) {
    var page by rememberSaveable { mutableStateOf(if (openMonitorRequest > 0) "space" else "stars") }
    var offset by rememberSaveable { mutableStateOf(0) }
    var archivedWeek by rememberSaveable { mutableStateOf<String?>(null) }
    var energyExpanded by rememberSaveable { mutableStateOf(false) }
    var coldEnergyOpened by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf(today.dayOfWeek.value - 1) }
    var sparksDay by rememberSaveable { mutableStateOf<Int?>(null) }
    var sparksWeekKey by rememberSaveable { mutableStateOf<String?>(null) }
    var overview by rememberSaveable { mutableStateOf(true) }
    var followToday by rememberSaveable { mutableStateOf(true) }
    var autoFocusToday by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    var musicError by remember { mutableStateOf<String?>(null) }
    val music = rememberBackgroundMusic { musicError = it }
    val scroll = rememberScrollState()
    val withinState = rememberSaveableStateHolder()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    var introDecisionMade by rememberSaveable { mutableStateOf(false) }
    var handledDebugRequest by remember { mutableIntStateOf(0) }
    var playingIntro by remember { mutableStateOf(false) }
    var settlingIntro by remember { mutableStateOf(false) }
    val posterOpacity = remember(settlingIntro) { Animatable(1f) }
    var focusBounds by remember { mutableStateOf<Rect?>(null) }
    var weekdayAnchor by remember { mutableStateOf<Offset?>(null) }
    var starNameAnchor by remember { mutableStateOf<Offset?>(null) }
    var skyWindowSize by remember { mutableStateOf(Size.Zero) }
    val focusProgress by animateFloatAsState(
        targetValue = if (overview) 0f else 1f,
        animationSpec = tween(if (reduceMotion) 0 else if (overview) 1100 else 800, easing = FastOutSlowInEasing),
        label = "day-sky-camera"
    )
    val currentMonday = mondayOf(today)
    val monday = archivedWeek?.let(LocalDate::parse) ?: currentMonday.plusWeeks(offset.toLong())
    val historicalWeek = monday.isBefore(currentMonday)
    val weekKey = monday.toString()
    val requestedSparksMonday = sparksWeekKey?.let(LocalDate::parse)
    val sparksWeeks = firstSparksBrowsingWeek(today)..PlanningHorizon.lastMonday(today)
    val sparksMonday = (requestedSparksMonday ?: currentMonday.plusWeeks(offset.toLong()))
        .coerceIn(sparksWeeks)
    val displayedSparksDay = sparksDay?.takeIf {
        it in 0..6 && (requestedSparksMonday == null || requestedSparksMonday == sparksMonday)
    }

    fun navigate(target: String) {
        introDecisionMade = true
        autoFocusToday = false
        playingIntro = false
        settlingIntro = false
        if (target != "stars") energyExpanded = false
        if (target == "planner" || target == "ideas") archivedWeek = null
        if (target == "ideas" && page != "ideas" && sparksWeekKey == null) {
            sparksWeekKey = currentMonday.plusWeeks(offset.toLong()).toString()
        }
        if (target != page) sparksDay = null
        page = target
    }
    fun chooseDay(day: Int) {
        introDecisionMade = true; selectedDay = day; overview = false
        autoFocusToday = false
        energyExpanded = false
        playingIntro = false
        settlingIntro = false
        followToday = monday == currentMonday && day == today.dayOfWeek.value - 1
    }
    fun chooseWeek(value: Int) {
        archivedWeek = null
        introDecisionMade = true; offset = value; overview = true; followToday = value == 0
        autoFocusToday = false
    }
    fun openEnergyWeek(date: LocalDate) {
        chooseWeek(0)
        archivedWeek = date.takeUnless { it == currentMonday }?.toString()
        followToday = archivedWeek == null
        selectedDay = if (followToday) today.dayOfWeek.value - 1 else 0
    }
    fun chooseEditorDay(value: Int) {
        introDecisionMade = true; selectedDay = value
        followToday = offset == 0 && value == today.dayOfWeek.value - 1
    }
    LaunchedEffect(openMonitorRequest) {
        if (openMonitorRequest > 0) {
            navigate("space")
            scroll.scrollTo(0)
        }
    }
    val dataReady = data != null && !loading
    val resumed = lifecycleState == Lifecycle.State.RESUMED
    val eligibleStartupIntro = allowStartupIntro && !reduceMotion && page == "stars" && offset == 0 &&
        overview && openMonitorRequest == 0 && debugIntroRequest == 0
    val pendingStartupIntro = !introDecisionMade && eligibleStartupIntro
    val pendingDebugIntro = BuildConfig.DEBUG && debugIntroRequest > handledDebugRequest
    LaunchedEffect(dataReady, resumed, eligibleStartupIntro) {
        if (introDecisionMade || !dataReady || !resumed) return@LaunchedEffect
        playingIntro = eligibleStartupIntro
        introDecisionMade = true
    }
    val openColdEnergy = allowStartupIntro && !coldEnergyOpened && dataReady && resumed &&
        introDecisionMade && !playingIntro && !settlingIntro && page == "stars" && overview &&
        monday == currentMonday && openMonitorRequest == 0 && debugIntroRequest == 0
    LaunchedEffect(openColdEnergy) {
        if (openColdEnergy) {
            coldEnergyOpened = true
            energyExpanded = true
        }
    }
    val waitingToFocus = dataReady && introDecisionMade && !playingIntro && !settlingIntro &&
        autoFocusToday && resumed && page == "stars" && offset == 0 && overview &&
        today.dayOfWeek != DayOfWeek.MONDAY
    LaunchedEffect(waitingToFocus, today) {
        if (waitingToFocus) {
            delay(2500)
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED && autoFocusToday &&
                page == "stars" && overview && offset == 0) {
                chooseDay(today.dayOfWeek.value - 1)
            }
        }
    }
    LaunchedEffect(debugIntroRequest, dataReady, resumed) {
        if (pendingDebugIntro && dataReady && resumed) {
            introDecisionMade = true
            autoFocusToday = false
            sparksDay = null
            page = "stars"
            offset = 0
            archivedWeek = null
            overview = true
            scroll.scrollTo(0)
            settlingIntro = false
            playingIntro = true
            handledDebugRequest = debugIntroRequest
        }
    }
    LaunchedEffect(settlingIntro) {
        if (settlingIntro) {
            posterOpacity.animateTo(0f, tween(if (reduceMotion) 0 else 260))
            settlingIntro = false
        }
    }
    LaunchedEffect(today, timezone) {
        if (followToday && monday == currentMonday) selectedDay = today.dayOfWeek.value - 1
        if (requestedSparksMonday != null && requestedSparksMonday != sparksMonday) {
            sparksWeekKey = sparksMonday.toString()
            sparksDay = null
        }
        if (displayedSparksDay == null) sparksDay = null
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
    LaunchedEffect(musicError) {
        musicError?.let {
            snackbar.showSnackbar(it)
            musicError = null
        }
    }
    BackHandler(enabled = page != "stars" || !overview || historicalWeek) {
        introDecisionMade = true
        autoFocusToday = false
        if (page == "ideas" && sparksDay != null) {
            sparksDay = null
        } else {
            if (page != "stars") page = "stars"
            else if (overview && historicalWeek) openEnergyWeek(currentMonday)
            overview = true
        }
    }
    if (playingIntro || ((pendingStartupIntro || pendingDebugIntro) && (loading || data != null))) {
        val introFrame = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
        if (playingIntro) {
            MondayIntroPlayer(modifier = introFrame) { completed ->
                settlingIntro = completed && page == "stars" && data != null && overview
                playingIntro = false
            }
        } else {
            Box(introFrame.background(Night))
        }
        return
    }
    StarBackdrop(Modifier.fillMaxSize().onSizeChanged { skyWindowSize = Size(it.width.toFloat(), it.height.toFloat()) }
        .semantics { testTagsAsResourceId = true }) {
        val homeSky = page == "stars" && data != null
        val sparksSky = page == "ideas" && data != null
        val within = page == "ability"
        val contentOverSky = page == "ideas" || page == "space" || within
        // Keep the fitted illustration mounted across tab changes, including the planner.
        Image(painterResource(R.drawable.monday_sky_plate), contentDescription = null,
            modifier = Modifier.fillMaxSize().background(Night), contentScale = ContentScale.Fit)
        if (homeSky && focusProgress < 1f) {
            SkyMotto(Modifier.graphicsLayer { alpha = 1f - focusProgress })
        }
        val displayedWeek = data?.let { PlanEngine.confirmed(it, weekKey) }
        val ratios = remember(displayedWeek) {
            weekCompletionRatios(displayedWeek ?: WeekPlan.empty())
        }
        val todayIndex = if (monday == currentMonday) today.dayOfWeek.value - 1 else -1
        if (page == "space") {
            MondayLandingSky(ratios, todayIndex, {}, Modifier.fillMaxSize(),
                reduceMotion = reduceMotion, showLabels = false, interactive = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Night.copy(alpha = .13f), Night.copy(alpha = .24f)))))
        } else if (!homeSky && !sparksSky && !within) {
            Box(Modifier.fillMaxSize().background(Night))
        }
        Scaffold(containerColor = Color.Transparent, contentColor = Ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                val horizontalInsets = if (within) {
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                } else Modifier
                Row(Modifier.fillMaxWidth().statusBarsPadding().then(horizontalInsets)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).sizeIn(minHeight = 54.dp)
                        .clickable { navigate("stars"); overview = true },
                        verticalArrangement = Arrangement.Center) {
                        Text("✦  StarLive", fontFamily = FontFamily.Serif, fontSize = 23.sp, color = Ink)
                        Text("SEVEN DAYS, YOUR WAY", style = MaterialTheme.typography.labelSmall,
                            color = Muted.copy(alpha = .75f))
                    }
                    Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                        MusicControl(playing = music.isPlaying, onToggle = music::toggle, reduceMotion = reduceMotion)
                    }
                }
            },
            bottomBar = {
                NavigationBar(
                    modifier = if (homeSky || contentOverSky) Modifier.background(Brush.verticalGradient(
                        listOf(Color.Transparent, Night.copy(alpha = .62f)))) else Modifier,
                    containerColor = if (homeSky || contentOverSky) Color.Transparent else Night,
                    windowInsets = WindowInsets.navigationBars
                ) {
                    listOf(Triple("stars", "✦", "Starmap"), Triple("ideas", "✧", "Sparks"),
                        Triple("space", "☾", "Daylight"), Triple("ability", "◇", "Within")).forEach { (route, icon, label) ->
                        val selected = page == route
                        NavigationBarItem(selected = selected, onClick = { navigate(route) },
                            icon = { Text(icon, fontSize = 23.sp, fontFamily = FontFamily.Serif) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                shadow = Shadow(Night, Offset(0f, 2f), 5f))) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Night,
                                selectedTextColor = Color(0xFFFFF3D8),
                                indicatorColor = Color(0xFFFFE7B4),
                                unselectedIconColor = Color(0xFFDBE6F5),
                                unselectedTextColor = Color(0xFFE9F0FC)
                            ),
                            modifier = Modifier.testTag("nav_$route"))
                    }
                }
            }, snackbarHost = { SnackbarHost(snackbar) }) { inner ->
            val content = Modifier.fillMaxSize().padding(inner).imePadding()
            val daySkyHeight = with(LocalDensity.current) {
                val bodyHeight = skyWindowSize.height.toDp() - inner.calculateTopPadding() -
                    inner.calculateBottomPadding() - 24.dp
                homeDaySkyHeight(skyWindowSize.width.toDp(), bodyHeight)
            }
            if (page == "ideas" && data != null) {
                IdeasScreen(today, sparksMonday, onWeekSelected = { week ->
                    if (week in sparksWeeks) {
                        sparksWeekKey = week.toString()
                        sparksDay = null
                    }
                }, day = displayedSparksDay, onDay = { day ->
                    sparksDay = day
                    if (sparksMonday == monday) chooseEditorDay(day)
                }, plans = PlanEngine.confirmed(data, sparksMonday.toString()), onAdd = repository::addPlan,
                    reduceMotion = reduceMotion,
                    onOverview = { sparksDay = null }, notice = error,
                    contentPadding = inner, confirmedWeek = { PlanEngine.confirmed(data, it.toString()) },
                    onOpenPlanner = { week, day ->
                        chooseWeek(if (week == currentMonday) 0 else 1)
                        chooseEditorDay(day)
                        navigate("planner")
                    }, onUpdate = repository::updatePlan, onDelete = repository::deletePlan)
            } else if (within) {
                withinState.SaveableStateProvider("within") {
                    WithinScreen(contentPadding = inner, today = today, reduceMotion = reduceMotion)
                }
            } else Column(content.verticalScroll(scroll)
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
                        selectedDay, ::chooseDay, overview, {
                            introDecisionMade = true; autoFocusToday = false; overview = true
                        },
                        reduceMotion, onToggle = { item ->
                            repository.setCompletion(weekKey, selectedDay, item.id, !item.done)
                        }, pageScroll = scroll, externalOverview = true, focusProgress = focusProgress,
                        canCompletePlans = !historicalWeek,
                        onSkyBounds = { focusBounds = it }, onWeekdayAnchor = { weekdayAnchor = it },
                        onStarNameAnchor = { starNameAnchor = it }, daySkyHeight = daySkyHeight)
                    page == "planner" -> PlannerScreen(today, monday, offset, ::chooseWeek,
                        selectedDay, ::chooseEditorDay, PlanEngine.confirmed(data, weekKey),
                        onIdeas = { navigate("ideas") }, onAdd = repository::addPlan,
                        onUpdate = repository::updatePlan, onDelete = repository::deletePlan,
                        onMoveBy = repository::movePlanBy, onMoveToDay = repository::movePlanToDay)
                    page == "space" -> SpaceScreen(data.settings, timezone, { transform -> repository.updateSettings(transform) },
                        backupBusy, onExport, onImport, onRestore, monitoringContent)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (page == "stars" && data != null) {
                WeeklyRecordsPanel(data.weeks, currentMonday, monday, overview, energyExpanded, reduceMotion,
                    onExpandedChange = { energyExpanded = it }, onWeekSelected = ::openEnergyWeek,
                    modifier = Modifier.fillMaxSize().padding(inner).padding(bottom = 16.dp))
            }
        }
        if (homeSky) {
            MondayLandingSky(ratios, todayIndex, ::chooseDay, Modifier.fillMaxSize(),
                overview = overview, selectedDay = selectedDay, focusBounds = focusBounds,
                focusProgress = focusProgress, reduceMotion = reduceMotion,
                weekdayAnchor = weekdayAnchor, starNameAnchor = starNameAnchor)
        }
        if (settlingIntro) {
            Image(painterResource(R.drawable.monday_intro_landing), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterOpacity.value }
                    .background(Night), contentScale = ContentScale.Fit)
        }
        backupPreview?.let { preview ->
            val summary = preview.summary
            AlertDialog(onDismissRequest = { if (!backupBusy) onCancelPreview() },
                title = { Text(if (restoring) "预览恢复上次备份" else "预览导入的备份") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("周计划：${summary.weekCount} 周\n事项：${summary.itemCount} 条\n已完成：${summary.completedItemCount} 条")
                    Text(if (preview.firstWeek == null) "日期范围：无周计划"
                        else "周起始日期：${preview.firstWeek} — ${preview.lastWeek}", color = Gold)
                    val settings = preview.settings
                    Text("将恢复的提醒规则\n连续：${settings.continuousMinutes} 分钟 · ${if (settings.continuousEnabled) "开启" else "关闭"}\n单应用每日：${settings.perAppDailyMinutes} 分钟 · ${if (settings.perAppDailyEnabled) "开启" else "关闭"}\n每日合计：${settings.totalDailyMinutes} 分钟 · ${if (settings.totalDailyEnabled) "开启" else "关闭"}",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("确认后将覆盖本机计划和提醒设置。覆盖前会保留一份本机备份，可手动恢复。",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                } }, confirmButton = { Button(onClick = onConfirmPreview, enabled = !backupBusy,
                    modifier = Modifier.testTag("backup_confirm")) { Text(if (backupBusy) "正在恢复…" else "确认覆盖并恢复") } },
                dismissButton = { TextButton(onClick = onCancelPreview, enabled = !backupBusy) { Text("取消，保留现有数据") } })
        }
    }
}

@Composable
private fun SkyMotto(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Anchor the copy to the fitted illustration just above the character.
        val artWidth = minOf(maxWidth, maxHeight * .45f)
        val artHeight = artWidth / .45f
        Text(
            "按你的节奏，慢慢点亮",
            modifier = Modifier.absoluteOffset(
                x = (maxWidth - artWidth) / 2 + artWidth * .06f,
                y = (maxHeight - artHeight) / 2 + artHeight * .55f
            ),
            color = Ink,
            style = MaterialTheme.typography.labelSmall.copy(
                shadow = Shadow(Night, Offset(0f, 1f), 4f)
            )
        )
    }
}
