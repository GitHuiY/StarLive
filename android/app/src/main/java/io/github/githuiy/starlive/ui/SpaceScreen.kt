package io.github.githuiy.starlive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.ReminderSettings

@Composable
fun SpaceScreen(settings: ReminderSettings, timezone: String,
    onSettings: ((ReminderSettings) -> ReminderSettings) -> Unit,
    backupBusy: Boolean, onExport: () -> Unit, onImport: () -> Unit, onRestore: () -> Unit,
    monitoringContent: @Composable () -> Unit = {}) {
    fun changed(continuous: Int? = null, perApp: Int? = null, total: Int? = null,
        continuousOn: Boolean? = null, perAppOn: Boolean? = null, totalOn: Boolean? = null):
            (ReminderSettings) -> ReminderSettings = { current ->
        ReminderSettings(continuous ?: current.continuousMinutes, perApp ?: current.perAppDailyMinutes,
            total ?: current.totalDailyMinutes, continuousOn ?: current.continuousEnabled,
            perAppOn ?: current.perAppDailyEnabled, totalOn ?: current.totalDailyEnabled)
    }
    Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
        monitoringContent()
        ThresholdCard("连续使用提醒", "所选应用切换接续；离开或锁屏两分钟内返回接续，间隔不计入使用时长。",
            settings.continuousMinutes, settings.continuousEnabled,
            { onSettings(changed(continuous = it)) }, { onSettings(changed(continuousOn = it)) }, "continuous")
        ThresholdCard("单应用每日累计提醒", "每个所选应用使用相同阈值，每日达到条件时提醒一次。",
            settings.perAppDailyMinutes, settings.perAppDailyEnabled,
            { onSettings(changed(perApp = it)) }, { onSettings(changed(perAppOn = it)) }, "per_app")
        ThresholdCard("所选应用每日合计提醒", "所选应用的当日使用时间合计；同时触发多个条件时合并提醒。",
            settings.totalDailyMinutes, settings.totalDailyEnabled,
            { onSettings(changed(total = it)) }, { onSettings(changed(totalOn = it)) }, "total")
        SkyCard(containerColor = Night.copy(alpha = .68f)) {
            Text("让提醒，轻一点", style = MaterialTheme.typography.titleLarge)
            Text("“要不要，去做一件喜欢的小事？”", color = Gold,
                style = MaterialTheme.typography.bodyLarge)
            Text("稍后提醒：30 分钟。到期和发送时都在使用所选应用，才会再次提醒。停止监测会取消待发提醒。",
                color = Ink, style = MaterialTheme.typography.bodyMedium)
            Text("统计时区：$timezone · 每日零点重置累计；连续会话可跨午夜。",
                color = Ink, style = MaterialTheme.typography.bodySmall)
        }
        SkyCard(containerColor = Night.copy(alpha = .78f)) {
            Text("把期待，好好保存。", style = MaterialTheme.typography.titleLarge)
            Text("手动备份包含已确认计划、草稿、完成状态和提醒设置。",
                color = Ink, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onExport, enabled = !backupBusy,
                modifier = Modifier.fillMaxWidth().testTag("backup_export")) { Text("导出本地备份") }
            OutlinedButton(onClick = onImport, enabled = !backupBusy,
                modifier = Modifier.fillMaxWidth().testTag("backup_import")) { Text("选择备份并预览") }
            TextButton(onClick = onRestore, enabled = !backupBusy,
                modifier = Modifier.fillMaxWidth().testTag("backup_restore")) { Text("预览恢复上次覆盖前的备份") }
            if (backupBusy) Text("正在处理备份…", color = Gold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThresholdCard(title: String, description: String, value: Int, enabled: Boolean,
    onValue: (Int) -> Unit, onEnabled: (Boolean) -> Unit, tag: String) {
    var editing by remember(value) { mutableStateOf(value.toString()) }
    val focus = LocalFocusManager.current
    val parsed = editing.toIntOrNull()
    val valid = parsed != null && parsed in 1..1440
    SkyCard(containerColor = Night.copy(alpha = .82f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(enabled, onCheckedChange = onEnabled,
                modifier = Modifier.testTag("setting_${tag}_enabled").semantics { contentDescription = "$title 开关" })
        }
        Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = editing, onValueChange = {
            if (it.length <= 4 && it.all(Char::isDigit)) editing = it
        }, label = { Text("提醒阈值（分钟）") }, singleLine = true,
            enabled = enabled, isError = editing.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(if (editing.isNotEmpty() && !valid) "请输入 1–1440 分钟" else "当前保存：$value 分钟") },
            modifier = Modifier.fillMaxWidth().testTag("setting_${tag}_minutes"))
        Button(onClick = { focus.clearFocus(); parsed?.let(onValue) }, enabled = enabled && valid && parsed != value,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("setting_${tag}_save")) {
            Text("保存这个阈值")
        }
    }
}
