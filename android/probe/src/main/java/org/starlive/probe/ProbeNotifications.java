package org.starlive.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class ProbeNotifications {
    static final String MONITOR_CHANNEL = "probe_monitor";
    static final String ALERT_CHANNEL = "probe_test_alerts";
    static final int MONITOR_ID = 100;
    static final int ALERT_ID = 200;

    static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel monitor = new NotificationChannel(MONITOR_CHANNEL,
                "能力验证：监测运行", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("仅在你手动启动测试监测后显示，可随时停止。");
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL,
                "能力验证：测试提醒", NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription("30 秒测试阈值与测试通知；不是正式版提醒。");
        manager.createNotificationChannel(monitor);
        manager.createNotificationChannel(alerts);
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification monitor(Context context) {
        Intent stop = new Intent(context, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(context, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_star)
                .setContentTitle("StarLive 能力验证正在运行")
                .setContentText("每约 10 秒检查测试计时页；点击打开，可随时停止")
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止测试", stopIntent).build())
                .build();
    }

    static boolean alert(Context context, String message) {
        if (!ProbeState.notificationsAllowed(context)) return false;
        Intent later = new Intent(context, SnoozeReceiver.class);
        PendingIntent snooze = PendingIntent.getBroadcast(context, 3, later,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_star)
                .setContentTitle("StarLive 测试提醒（非正式规则）")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message
                        + "\n测试稍后为 30 秒；正式需求是 30 分钟。"))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .addAction(new Notification.Action.Builder(null,
                        "测试：30 秒后提醒", snooze).build())
                .build();
        try {
            context.getSystemService(NotificationManager.class).notify(ALERT_ID, notification);
            return true;
        } catch (SecurityException failure) {
            // Caller is the serial worker; keep final dispatch gate free of file I/O.
            ProbeState.prefs(context).edit().putString("last_error",
                    "通知权限已变化，测试提醒未发送").apply();
            return false;
        }
    }
}
