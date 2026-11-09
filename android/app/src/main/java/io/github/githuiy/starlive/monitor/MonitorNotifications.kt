package io.github.githuiy.starlive.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.github.githuiy.starlive.MainActivity
import io.github.githuiy.starlive.core.monitor.ReminderPolicy

internal class MonitorNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager?.createNotificationChannels(listOf(
            NotificationChannel(STATUS_CHANNEL, "屏幕留白监测状态", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(REMINDER_CHANNEL, "屏幕留白提醒", NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    fun allowed(): Boolean = manager != null && manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && listOf(STATUS_CHANNEL, REMINDER_CHANNEL).all { id ->
            val channel = manager.getNotificationChannel(id)
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }

    fun status(starting: Boolean): Notification {
        val stop = PendingIntent.getBroadcast(context, 1,
            Intent(context, MonitorActionReceiver::class.java).setAction(MonitorActionReceiver.STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return base(STATUS_CHANNEL).setContentTitle(if (starting) "StarLive 屏幕留白监测" else "StarLive 正在关闭监测")
            .setContentText("点击查看屏幕留白 · 可随时停止")
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "停止监测", stop).build()).build()
    }

    fun prepare(reasons: List<ReminderPolicy.Reason>, alertId: String?): Notification {
        val text = reasons.map { reason ->
            when (reason.type) {
                ReminderPolicy.Type.CONTINUOUS -> "连续使用已达到提醒时间"
                ReminderPolicy.Type.PER_APP_DAILY -> "有应用已达到今日提醒时间"
                ReminderPolicy.Type.TOTAL_DAILY -> "所选应用已达到今日合计提醒时间"
                ReminderPolicy.Type.SNOOZE -> "稍后提醒时间已到"
            }
        }.distinct().joinToString(" · ")
        val builder = base(REMINDER_CHANNEL).setContentTitle("留一点时间给自己")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (alertId != null) {
            val action = Intent(context, MonitorActionReceiver::class.java)
                .setAction(MonitorActionReceiver.SNOOZE)
                .setData(Uri.Builder().scheme("starlive-monitor").authority("snooze").appendPath(alertId).build())
            val pending = PendingIntent.getBroadcast(context, 2, action, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "30 分钟后提醒", pending).build())
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission") // The worker checks permissions before committing the submission.
    fun submit(notification: Notification): Boolean {
        return try {
            manager?.notify(REMINDER_ID, notification) ?: return false
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancelReminder() { manager?.cancel(REMINDER_ID) }

    private fun base(channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(android.R.drawable.star_on).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MonitorController.OPEN_SCREEN_EXTRA, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    companion object {
        const val STATUS_ID = 2701
        private const val REMINDER_ID = 2702
        private const val STATUS_CHANNEL = "screen_use_status"
        private const val REMINDER_CHANNEL = "screen_use_reminders"
    }
}
