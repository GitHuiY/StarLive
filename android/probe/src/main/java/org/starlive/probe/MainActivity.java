package org.starlive.probe;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.ZoneId;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView stats;
    private final Runnable redraw = new Runnable() {
        @Override public void run() {
            showStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ProbeNotifications.createChannels(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(16);
        column.setPadding(inset, inset, inset, inset);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            column.setPadding(inset + insets.getSystemWindowInsetLeft(),
                    inset + insets.getSystemWindowInsetTop(),
                    inset + insets.getSystemWindowInsetRight(),
                    inset + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = text("StarLive 能力验证", 23);
        column.addView(title);
        column.addView(text("0.2-probe · 非正式版 · 仅本机测试\n真实统计仅展示独立测试计时页，不展示你的其他应用记录。", 14));
        status = text("", 15);
        status.setContentDescription("probe.status");
        column.addView(status);
        addRow(column,
                button("1 使用情况授权", "probe.open.usage", view -> openUsageAccess()),
                button("2 允许通知", "probe.request.notifications", view -> requestNotifications()));
        addRow(column,
                button("3 启动测试监测", "probe.start.monitor", view -> startMonitor()),
                button("停止测试监测", "probe.stop.monitor", view -> {
                    MonitorService.requestStop(this, "ui_stop");
                    showStatus();
                }));
        addRow(column,
                button("4 打开测试计时页", "probe.open.fixture", view -> openFixture()),
                button("刷新真实统计", "probe.refresh", view -> refreshUsage()));
        addRow(column,
                button("发送测试通知", "probe.test.notification", view -> ProbeWorker.post(() -> {
                    if (ProbeNotifications.alert(this, "这是你手动触发的通知可达性测试。")) {
                        SharedPreferences prefs = ProbeState.prefs(this);
                        prefs.edit().putLong("manual_notification_count",
                                prefs.getLong("manual_notification_count", 0) + 1).apply();
                        ProbeState.writeDiagnostics(this, "manual_notification");
                        toast("已提交测试通知；请检查通知栏和点击返回");
                    } else {
                        toast("通知未发送，请先允许通知");
                    }
                    runOnUiThread(this::showStatus);
                })),
                button("测试稍后 30 秒", "probe.test.snooze", view ->
                        ProbeWorker.post(() -> {
                            if (SnoozeReceiver.scheduleOnWorker(this)) toast("测试稍后已设置：30 秒");
                            else toast("请先启动监测并确认权限");
                            runOnUiThread(this::showStatus);
                        })));
        stats = text("", 15);
        stats.setContentDescription("probe.fixture.stats");
        column.addView(stats);
        column.addView(text("测试规则：独立计时页今日累计 30 秒触发一次；稍后为 30 秒，仅到期仍在使用该测试页时提醒。\n\n正式需求仍为：连续 60 分钟、单应用每日 60 分钟、所选应用合计每日 180 分钟，稍后 30 分钟。此包未验收完整产品规则。\n\n监测约每 10 秒在专用工作线程查询；系统可能延迟。没有保持唤醒、重启自启或绕过省电限制。", 13));
        scroll.addView(column);
        setContentView(scroll);
    }

    private TextView text(String value, int sp) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(0xff243142);
        text.setPadding(0, dp(5), 0, dp(5));
        return text;
    }

    private Button button(String label, String description, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setContentDescription(description);
        button.setOnClickListener(action);
        return button;
    }

    private void addRow(LinearLayout column, Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.addView(first, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(second, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        column.addView(row);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void openUsageAccess() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException failure) {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (ActivityNotFoundException absent) {
                ProbeState.error(this, "系统未提供使用情况设置入口，请在系统设置中手动查找");
                toast("请在系统设置中搜索使用情况访问权限");
            }
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
        } else if (!ProbeState.notificationsAllowed(this)) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            toast("通知权限已允许");
        }
    }

    private void startMonitor() {
        long request = MonitorService.newStartRequest();
        ProbeWorker.post(() -> {
            if (!MonitorService.isPendingStart(request)) return;
            if (!FixtureUsageReader.hasAccess(this)) {
                toast("请先在系统页面主动授予使用情况访问权限");
                return;
            }
            if (!ProbeState.notificationsAllowed(this)) {
                toast("请先允许通知，测试监测才能提示你");
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                MonitorService.dispatchStart(request, () -> {
                    try {
                        startForegroundService(new Intent(this, MonitorService.class)
                                .putExtra(MonitorService.EXTRA_START_REQUEST, request));
                    } catch (RuntimeException failure) {
                        ProbeState.error(this, "启动请求失败：" + failure.getClass().getSimpleName());
                        toast("监测启动失败，请查看诊断状态");
                    }
                });
                handler.postDelayed(this::showStatus, 300);
            });
        });
    }

    private void openFixture() {
        try {
            startActivity(new Intent().setClassName(FixtureUsageReader.FIXTURE_PACKAGE,
                    FixtureUsageReader.FIXTURE_PACKAGE + ".FixtureActivity"));
        } catch (ActivityNotFoundException failure) {
            ProbeState.error(this, "尚未安装独立测试计时页 APK");
            toast("请先安装 usagefixture 测试 APK");
        }
    }

    private void refreshUsage() {
        ProbeWorker.post(() -> {
            long started = SystemClock.elapsedRealtime();
            ProbeState.prefs(this).edit().putLong("ui_query_started_elapsed", started).apply();
            ProbeState.writeDiagnostics(this, "activity_query_start");
            FixtureUsageReader.Snapshot snapshot = FixtureUsageReader.read(this,
                    System.currentTimeMillis());
            ProbeState.recordUsage(this, snapshot);
            ProbeState.prefs(this).edit().putLong("ui_query_finished_elapsed",
                    SystemClock.elapsedRealtime()).apply();
            ProbeState.writeDiagnostics(this, "activity_refresh");
            runOnUiThread(() -> {
                if (!isDestroyed()) showStatus();
            });
        });
    }

    private void showStatus() {
        SharedPreferences prefs = ProbeState.prefs(this);
        // Show the worker's permission snapshot without Binder polling on the UI thread.
        boolean access = prefs.getBoolean("usage_permission", false);
        boolean notifications = prefs.getBoolean("notifications_allowed", false);
        long heartbeat = prefs.getLong("last_heartbeat_elapsed", 0);
        String heartbeatText = heartbeat == 0 ? "尚无"
                : Math.max(0, SystemClock.elapsedRealtime() - heartbeat) / 1000 + " 秒前";
        status.setText("使用情况权限：" + (access ? "已授权" : "未授权 / 已撤销")
                + "\n通知权限及渠道：" + (notifications ? "已允许" : "未允许 / 已关闭")
                + "\n测试监测：" + (MonitorService.running ? "运行中" : "未运行")
                + "  · 心跳 " + heartbeatText
                + "\n轮询次数：" + prefs.getLong("loop_count", 0)
                + "  · 系统时区：" + ZoneId.systemDefault().getId());
        boolean available = access && prefs.getBoolean("usage_available", false);
        long used = prefs.getLong("fixture_today_millis", 0);
        String usage = available ? (used / 1000) + " 秒" : "不可用（不显示为零）";
        long due = prefs.getLong("policy_snooze_due", 0);
        long observedAt = prefs.getLong("last_usage_observed_at", 0);
        String age = observedAt == 0 ? "尚无快照"
                : Math.max(0, System.currentTimeMillis() - observedAt) / 1000 + " 秒前读取";
        String snooze = due == 0 ? "无" : Math.max(0, due - System.currentTimeMillis()) / 1000 + " 秒后";
        stats.setText("独立测试计时页 · 今日前台时长：" + usage
                + "\n上次读取时在前台：" + (available ? (prefs.getBoolean("fixture_foreground", false) ? "是" : "否") : "未知")
                + "  · " + age
                + "\n" + prefs.getString("usage_note", "尚未读取")
                + "\n阈值决策 / 已提交通知：" + prefs.getLong("policy_notification_count", 0)
                + " / " + prefs.getLong("posted_notification_count", 0)
                + "  · 去重次数：" + prefs.getLong("policy_deduped_count", 0)
                + "\n测试稍后：" + snooze
                + "\n最近问题：" + (prefs.getString("last_error", "").isEmpty()
                        ? "无" : prefs.getString("last_error", "")));
    }

    @Override public void onResume() {
        super.onResume();
        refreshUsage();
        handler.post(redraw);
    }

    @Override public void onPause() {
        handler.removeCallbacks(redraw);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 11) refreshUsage();
    }
}
