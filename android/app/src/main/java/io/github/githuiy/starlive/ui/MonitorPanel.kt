package io.github.githuiy.starlive.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.githuiy.starlive.monitor.MonitorController
import io.github.githuiy.starlive.monitor.MonitorDataQuality
import io.github.githuiy.starlive.monitor.MonitorPhase
import io.github.githuiy.starlive.monitor.MonitorSnoozeOutcome
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun MonitorPanel(
    controller: MonitorController,
    today: LocalDate,
    onUsageAccess: () -> Unit,
    onNotificationPermission: () -> Unit,
    onNotificationSettings: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    val state by controller.snapshot.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val updatedElapsed = state.lastUpdateElapsedMillis
    var heartbeatExpired by remember(updatedElapsed) {
        mutableStateOf(updatedElapsed != null &&
            SystemClock.elapsedRealtime() - updatedElapsed >= MonitorController.STALE_AFTER_MILLIS)
    }
    LaunchedEffect(updatedElapsed, lifecycle) {
        if (updatedElapsed != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val remaining = MonitorController.STALE_AFTER_MILLIS -
                    (SystemClock.elapsedRealtime() - updatedElapsed)
                heartbeatExpired = remaining <= 0
                if (remaining > 0) delay(remaining)
                heartbeatExpired = true
            }
        }
    }
    val phase = if (state.phase == MonitorPhase.RUNNING && heartbeatExpired) MonitorPhase.STALE
        else state.phase
    val names = remember(state.apps) { state.apps.associate { it.packageName to it.label } }
    var selectionDraft by remember { mutableStateOf<Set<String>?>(null) }
    val ready = state.usageAccess && state.notificationsAllowed && state.selectedPackages.isNotEmpty()
    val total = state.todayTotalMillis?.takeIf {
        state.enabledIntent && state.usageAccess && state.selectedToday == today.toString() &&
            (it > 0 || state.dataQuality == MonitorDataQuality.COMPLETE)
    }

    SkyCard(containerColor = Night.copy(alpha = .68f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("屏幕使用情况", style = MaterialTheme.typography.titleLarge)
                Text(if (state.initialized) phaseLabel(phase) else "正在检查监测条件",
                    color = Gold, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.enabledIntent, onCheckedChange = controller::setEnabled,
                enabled = ready || state.enabledIntent,
                modifier = Modifier.testTag("monitor_enabled").semantics { contentDescription = "监测开关" })
        }
        if (state.initialized && !state.usageAccess) {
            Text("开启监测前，请在系统设置中允许 StarLive 访问应用使用情况。", color = Ink)
            OutlinedButton(onClick = onUsageAccess, modifier = Modifier.testTag("monitor_usage_access")) {
                Text("打开使用情况访问设置")
            }
        }
        if (state.initialized && !state.notificationsAllowed) {
            Text("监测状态和使用提醒各用一个通知渠道。开启监测前，请允许通知并启用两个渠道。",
                color = Ink)
            OutlinedButton(onClick = onNotificationPermission,
                modifier = Modifier.testTag("monitor_notification_permission")) { Text("允许通知") }
            TextButton(onClick = onNotificationSettings) { Text("检查通知设置") }
        }
        OutlinedButton(onClick = { selectionDraft = state.selectedPackages },
            modifier = Modifier.fillMaxWidth().testTag("monitor_choose_apps")) {
            Text("选择监测应用（已选 ${state.selectedPackages.size} 个）")
        }
        if (state.enabledIntent && ready && phase in setOf(MonitorPhase.WAITING_SYSTEM, MonitorPhase.STALE)) {
            Button(onClick = { controller.setEnabled(true) }, modifier = Modifier.testTag("monitor_resume")) {
                Text("恢复监测")
            }
        }
        Text(if (total == null) "—" else durationLabel(total), color = Ink,
            style = MaterialTheme.typography.headlineLarge)
        Text(when {
            total == null -> "当前没有可用的使用记录"
            phase == MonitorPhase.STALE -> "上次确认的今日合计"
            state.dataQuality == MonitorDataQuality.PARTIAL -> "今日合计 · 仅含可确认区间"
            else -> "所选应用今日合计"
        }, style = MaterialTheme.typography.bodyLarge)
        if (state.enabledIntent && state.usageAccess) {
            state.sessionMillis?.let { Text("本次连续使用 ${durationLabel(it)}", color = Ink) }
            state.lastUpdateWallMillis?.let {
                val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(it))
                Text("上次更新 ${time}", color = Ink, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("按系统可确认的前台使用区间统计。所选应用同时可见的时间在合计中只算一次。",
            color = Ink, style = MaterialTheme.typography.bodySmall)
    }
    if (total != null && state.selectedPackages.isNotEmpty()) SkyCard(containerColor = Night.copy(alpha = .82f)) {
        Text("所选应用", style = MaterialTheme.typography.titleLarge)
        for (id in state.selectedPackages.sortedBy { names[it] ?: it }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(names[id] ?: id, modifier = Modifier.weight(1f))
                val used = state.todayByAppMillis[id]?.takeIf {
                    it > 0 || state.dataQuality == MonitorDataQuality.COMPLETE
                }
                Text(used?.let(::durationLabel) ?: "未确认", color = Muted)
            }
        }
    }
    val snooze = snoozeLabel(state.lastSnoozeOutcome)
    if (snooze != null) SmallNotice(snooze)
    TextButton(onClick = onExportDiagnostics, modifier = Modifier.testTag("monitor_diagnostics")) {
        Text("导出监测诊断")
    }

    selectionDraft?.let { draft ->
        var query by remember { mutableStateOf("") }
        val candidates = remember(names, draft, query) {
            (names.keys + draft).filter { id -> (names[id] ?: id).contains(query.trim(), ignoreCase = true) }
                .sortedBy { names[it] ?: it }
        }
        AlertDialog(
            onDismissRequest = { selectionDraft = null },
            title = { Text("选择监测应用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("列表显示系统允许查询、可从桌面启动的应用。更改选择会开始新的连续会话。",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(query, { query = it }, label = { Text("搜索应用") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    if (candidates.isEmpty()) Text("没有匹配的应用", color = Muted)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(candidates, key = { it }) { id ->
                            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                                .toggleable(id in draft, role = Role.Checkbox) { checked ->
                                    selectionDraft = if (checked) draft + id else draft - id
                                }.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in draft, onCheckedChange = null)
                                Text(names[id] ?: id, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    controller.setSelectedPackages(draft)
                    selectionDraft = null
                }, modifier = Modifier.testTag("monitor_confirm_apps")) { Text("保存选择（${draft.size}）") }
            },
            dismissButton = { TextButton(onClick = { selectionDraft = null }) { Text("取消") } }
        )
    }
}

private fun durationLabel(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分钟"
        minutes > 0 -> "${minutes} 分 ${seconds % 60} 秒"
        else -> "${seconds} 秒"
    }
}

private fun phaseLabel(phase: MonitorPhase) = when (phase) {
    MonitorPhase.OFF -> "监测已关闭"
    MonitorPhase.STARTING -> "正在启动监测"
    MonitorPhase.RUNNING -> "监测运行中"
    MonitorPhase.WAITING_USAGE_ACCESS -> "等待使用情况授权"
    MonitorPhase.WAITING_NOTIFICATIONS -> "等待通知授权"
    MonitorPhase.WAITING_SELECTION -> "请选择监测应用"
    MonitorPhase.WAITING_SYSTEM -> "等待系统恢复监测"
    MonitorPhase.STALE -> "监测数据暂未更新"
    MonitorPhase.STORAGE_ERROR -> "无法读取或保存监测设置"
}

private fun snoozeLabel(outcome: MonitorSnoozeOutcome): String? = when (outcome) {
    MonitorSnoozeOutcome.NONE -> null
    MonitorSnoozeOutcome.PENDING -> "已安排 30 分钟后提醒"
    MonitorSnoozeOutcome.SUBMITTED -> "稍后提醒已提交给系统"
    MonitorSnoozeOutcome.CANCELLED_NOT_USING -> "稍后提醒已取消，到期或发送时未在使用所选应用"
    MonitorSnoozeOutcome.CANCELLED_UNCERTAIN -> "稍后提醒已取消，无法确认到期时的使用情况"
    MonitorSnoozeOutcome.CANCELLED_SCOPE -> "应用选择已变化，原稍后提醒已取消"
    MonitorSnoozeOutcome.CANCELLED_STOPPED -> "监测已停止，稍后提醒已取消"
    MonitorSnoozeOutcome.CANCELLED_REBOOT -> "手机已重启，重启前的稍后提醒已取消"
    MonitorSnoozeOutcome.CANCELLED_CLOCK -> "系统时间变化，无法确认稍后提醒的使用条件"
    MonitorSnoozeOutcome.CANCELLED_PERMISSION -> "权限已变化，稍后提醒已取消"
    MonitorSnoozeOutcome.SUBMISSION_FAILED -> "稍后提醒未能提交给系统"
}
