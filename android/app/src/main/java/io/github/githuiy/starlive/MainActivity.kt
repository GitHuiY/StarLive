package io.github.githuiy.starlive

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.data.BackupPreview
import io.github.githuiy.starlive.data.PlanRepository
import io.github.githuiy.starlive.monitor.MonitorController
import io.github.githuiy.starlive.ui.MonitorPanel
import io.github.githuiy.starlive.ui.Night
import io.github.githuiy.starlive.ui.StarLiveApp
import io.github.githuiy.starlive.ui.StarLiveTheme
import java.time.LocalDate
import java.time.ZoneId

class PlanViewModel(application: Application) : AndroidViewModel(application) {
    val repository = PlanRepository(application)
    var data by mutableStateOf<AppState?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    var backupBusy by mutableStateOf(false)
    var backupPreview by mutableStateOf<BackupPreview?>(null)
    var restoring by mutableStateOf(false)
    private val subscription = repository.observe {
        data = it.data; loading = it.loading; error = it.error
    }

    fun previewRestore() {
        backupBusy = true
        repository.prepareRestore { result ->
            backupBusy = false
            result.onSuccess { backupPreview = it; restoring = true }
                .onFailure { notice = it.message ?: "无法读取上次备份" }
        }
    }

    fun confirmBackup() {
        val preview = backupPreview ?: return
        backupBusy = true
        val completed: (Result<Unit>) -> Unit = { result ->
            backupBusy = false
            result.onSuccess { backupPreview = null; notice = "备份已恢复，计划与设置已更新。" }
                .onFailure { backupPreview = null; notice = it.message ?: "备份恢复失败，原有数据已保留。" }
        }
        if (restoring) repository.confirmRestore(preview.token, completed)
        else repository.confirmImport(preview.token, completed)
    }

    override fun onCleared() {
        subscription.close()
        repository.close()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: PlanViewModel
    private lateinit var monitor: MonitorController
    private var openMonitorRequest by mutableIntStateOf(0)
    private var today by mutableStateOf(LocalDate.now())
    private var timezone by mutableStateOf(ZoneId.systemDefault().id)
    private var reduceMotion by mutableStateOf(false)
    private var receiverRegistered = false
    private val calendarReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshCalendar() }
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        monitor.refresh()
    }
    private val exportDiagnostics = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) monitor.exportDiagnostics(uri) { success ->
            model.notice = if (success) "监测诊断已导出。" else "无法导出监测诊断。"
        }
    }
    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            model.backupBusy = true
            model.repository.exportTo(uri) { result ->
                model.backupBusy = false
                model.notice = result.fold({ "本地备份已导出。" }, { it.message ?: "备份导出失败" })
            }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            model.backupBusy = true
            model.repository.prepareImport(uri) { result ->
                model.backupBusy = false
                result.onSuccess { model.backupPreview = it; model.restoring = false }
                    .onFailure { model.notice = it.message ?: "备份无法读取，原有数据已保留。" }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Night.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Night.toArgb()))
        model = ViewModelProvider(this)[PlanViewModel::class.java]
        monitor = MonitorController.get(this)
        if (savedInstanceState == null) consumeMonitorIntent(intent)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(calendarReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(calendarReceiver, filter)
        receiverRegistered = true
        setContent {
            StarLiveTheme {
                StarLiveApp(data = model.data, loading = model.loading, error = model.error,
                    today = today, timezone = timezone, reduceMotion = reduceMotion,
                    repository = model.repository, notice = model.notice,
                    onNoticeConsumed = { model.notice = null }, backupBusy = model.backupBusy,
                    backupPreview = model.backupPreview, restoring = model.restoring,
                    onCancelPreview = { model.backupPreview = null }, onConfirmPreview = model::confirmBackup,
                    onExport = { exportDocument.launch("StarLive-${LocalDate.now()}.json") },
                    onImport = { importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onRestore = model::previewRestore,
                    onCalendarTick = ::refreshCalendar, openMonitorRequest = openMonitorRequest,
                    monitoringContent = {
                        MonitorPanel(monitor, today,
                            onUsageAccess = { openSystemSettings(monitor.usageAccessIntent()) },
                            onNotificationPermission = ::allowNotifications,
                            onNotificationSettings = { openSystemSettings(monitor.notificationSettingsIntent()) },
                            onExportDiagnostics = { exportDiagnostics.launch("starlive-monitor-diagnostics.json") })
                    })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCalendar()
        monitor.refresh()
        reduceMotion = try {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: RuntimeException) { false }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeMonitorIntent(intent)
    }

    private fun consumeMonitorIntent(intent: Intent) {
        if (intent.getBooleanExtra(MonitorController.OPEN_SCREEN_EXTRA, false)) {
            intent.removeExtra(MonitorController.OPEN_SCREEN_EXTRA)
            openMonitorRequest++
        }
    }

    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openSystemSettings(monitor.notificationSettingsIntent())
    }

    private fun openSystemSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            model.notice = "系统设置入口不可用。请在设置中查找 StarLive 的权限。"
        } catch (_: SecurityException) {
            model.notice = "系统限制了此设置入口。请在设置中查找 StarLive 的权限。"
        }
    }

    private fun refreshCalendar() {
        today = LocalDate.now(ZoneId.systemDefault())
        timezone = ZoneId.systemDefault().id
        if (::model.isInitialized) model.repository.refreshCalendar()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(calendarReceiver)
        super.onDestroy()
    }
}
