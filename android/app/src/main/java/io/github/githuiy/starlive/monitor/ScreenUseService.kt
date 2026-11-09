package io.github.githuiy.starlive.monitor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ScreenUseService : Service() {
    private val controller get() = MonitorController.get(this)
    @Volatile internal var foreground = false
        private set
    @Volatile private var activeTicket = 0L
    @Volatile private var activeStartId = 0
    private var receiverRegistered = false
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.systemChanged(this@ScreenUseService, intent.action == Intent.ACTION_TIME_CHANGED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = intent?.getLongExtra(START_TICKET, 0) ?: 0
        val accepted = controller.claimStart(ticket)
        if (!accepted && (ticket == 0L || foreground)) {
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            // A cancelled request that already reached the system still owes a promotion.
            val status = MonitorNotifications(this).status(accepted)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(MonitorNotifications.STATUS_ID, status,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(MonitorNotifications.STATUS_ID, status)
            }
            if (!accepted) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            activeTicket = ticket
            activeStartId = startId
            foreground = true
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, RECEIVER_NOT_EXPORTED)
                else registerReceiver(changes, filter)
                receiverRegistered = true
            }
            controller.attach(this, ticket)
        } catch (_: RuntimeException) {
            if (accepted) controller.promotionFailed(ticket)
            if (activeTicket == ticket) finish(ticket) else stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    internal fun finish(ticket: Long = activeTicket) {
        if (activeTicket != ticket) return
        foreground = false
        if (stopSelfResult(activeStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        foreground = false
        if (receiverRegistered) unregisterReceiver(changes)
        controller.detached(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal companion object { const val START_TICKET = "starlive.monitor.start_ticket" }
}

class MonitorActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != STOP && action != SNOOZE) return
        val pending = goAsync()
        val controller = MonitorController.get(context)
        if (action == STOP) controller.requestStop(pending::finish)
        else controller.requestSnooze(intent.data?.lastPathSegment, pending::finish)
    }

    internal companion object {
        const val STOP = "io.github.githuiy.starlive.monitor.STOP"
        const val SNOOZE = "io.github.githuiy.starlive.monitor.SNOOZE"
    }
}

class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        MonitorController.get(context).restoreAfterBoot(pending::finish)
    }
}
