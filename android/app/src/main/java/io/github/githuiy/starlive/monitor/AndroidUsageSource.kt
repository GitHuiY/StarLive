package io.github.githuiy.starlive.monitor

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents.Event
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import io.github.githuiy.starlive.core.monitor.UsageEvent
import java.text.Collator
import kotlin.math.abs

internal data class MonitorClock(val wall: Long, val elapsed: Long, val uptime: Long) {
    fun wallConsistentWith(earlier: MonitorClock): Boolean =
        elapsed >= earlier.elapsed && abs((wall - earlier.wall) - (elapsed - earlier.elapsed)) <= 2_000

    companion object {
        fun now() = MonitorClock(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            SystemClock.uptimeMillis())
    }
}

internal data class MonitorPower(
    val interactive: Boolean, val locked: Boolean, val powerSave: Boolean, val idle: Boolean
) {
    val allowsUse: Boolean get() = interactive && !locked
}

internal class AndroidUsageSource(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val usage = context.getSystemService(UsageStatsManager::class.java)

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) {
            false
        }
    }

    fun power() = MonitorPower(power?.isInteractive == true, keyguard?.isKeyguardLocked != false,
        power?.isPowerSaveMode == true, power?.isDeviceIdleMode == true)

    fun bootCount(): Long = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
    } catch (_: SecurityException) {
        -1L
    }

    fun launcherApps(): List<MonitorApp> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val labels = Collator.getInstance()
        @Suppress("DEPRECATION")
        return manager.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                val id = info.activityInfo.packageName
                MonitorApp(id, info.activityInfo.applicationInfo.loadLabel(manager).toString().ifBlank { id })
            }.sortedWith(compareBy(labels) { it.label })
    }

    /** An empty, available system stream is separate from a denied, capped or unavailable read. */
    fun read(startWall: Long, endWall: Long): List<UsageEvent>? {
        val users = context.getSystemService(UserManager::class.java)
        if (usage == null || users?.isUserUnlocked != true || startWall >= endWall) return null
        val events = if (Build.VERSION.SDK_INT >= 35) {
            val query = UsageEventsQuery.Builder(startWall, endWall)
                .setEventTypes(Event.ACTIVITY_RESUMED, Event.ACTIVITY_PAUSED, Event.ACTIVITY_STOPPED,
                    Event.SCREEN_INTERACTIVE, Event.SCREEN_NON_INTERACTIVE,
                    Event.KEYGUARD_SHOWN, Event.KEYGUARD_HIDDEN,
                    Event.DEVICE_SHUTDOWN, Event.DEVICE_STARTUP).build()
            usage.queryEvents(query)
        } else {
            usage.queryEvents(startWall, endWall)
        } ?: return null
        val item = Event()
        val result = ArrayList<UsageEvent>()
        while (events.hasNextEvent() && events.getNextEvent(item)) {
            val type = EVENT_TYPES[item.eventType] ?: continue
            if (result.size >= 100_000) return null
            result += UsageEvent(type, item.timeStamp, item.packageName, item.className)
        }
        return result
    }

    private companion object {
        @Suppress("DEPRECATION") // The legacy foreground/background names apply through API 28.
        val EVENT_TYPES = buildMap {
            if (Build.VERSION.SDK_INT >= 29) {
                put(Event.ACTIVITY_RESUMED, UsageEvent.Type.RESUME)
                put(Event.ACTIVITY_PAUSED, UsageEvent.Type.PAUSE)
                put(Event.ACTIVITY_STOPPED, UsageEvent.Type.STOP)
                put(Event.DEVICE_SHUTDOWN, UsageEvent.Type.DEVICE_SHUTDOWN)
                put(Event.DEVICE_STARTUP, UsageEvent.Type.DEVICE_STARTUP)
            } else {
                put(Event.MOVE_TO_FOREGROUND, UsageEvent.Type.RESUME)
                put(Event.MOVE_TO_BACKGROUND, UsageEvent.Type.PAUSE)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                put(Event.SCREEN_INTERACTIVE, UsageEvent.Type.SCREEN_INTERACTIVE)
                put(Event.SCREEN_NON_INTERACTIVE, UsageEvent.Type.SCREEN_NON_INTERACTIVE)
                put(Event.KEYGUARD_SHOWN, UsageEvent.Type.KEYGUARD_SHOWN)
                put(Event.KEYGUARD_HIDDEN, UsageEvent.Type.KEYGUARD_HIDDEN)
            }
        }
    }
}
