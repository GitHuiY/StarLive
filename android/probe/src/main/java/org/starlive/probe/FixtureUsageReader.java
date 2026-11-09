package org.starlive.probe;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import java.time.Instant;
import java.time.ZoneId;

/** Keeps all system events in memory and returns data only for our controlled test app. */
final class FixtureUsageReader {
    static final String FIXTURE_PACKAGE = "org.starlive.usagefixture";

    static final class Snapshot {
        final boolean available;
        final long todayMillis;
        final boolean foreground;
        final int fixtureEventCount;
        final boolean complete;
        final String note;

        Snapshot(boolean available, long todayMillis, boolean foreground, int eventCount,
                boolean complete, String note) {
            this.available = available;
            this.todayMillis = todayMillis;
            this.foreground = foreground;
            this.fixtureEventCount = eventCount;
            this.complete = complete;
            this.note = note;
        }
    }

    static boolean hasAccess(Context context) {
        AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        try {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(), context.getPackageName())
                    : manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static Snapshot read(Context context, long nowMillis) {
        if (!hasAccess(context)) {
            return new Snapshot(false, 0, false, 0, false, "未授权或授权已撤销；数据不可用");
        }
        ZoneId zone = ZoneId.systemDefault();
        long dayStart = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli();
        // A bounded look-back can seed a session crossing midnight. Never persist raw events.
        long queryStart = Math.max(0, dayStart - 86_400_000L);
        try {
            UsageStatsManager manager = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = manager.queryEvents(queryStart, nowMillis);
            if (events == null) {
                return new Snapshot(false, 0, false, 0, false,
                        "系统未提供事件；请确认手机已解锁");
            }
            UsageEvents.Event event = new UsageEvents.Event();
            long activeStart = -1;
            long total = 0;
            int count = 0;
            boolean complete = true;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                long time = event.getTimeStamp();
                int kind = event.getEventType();
                boolean fixture = FIXTURE_PACKAGE.equals(event.getPackageName());
                if (fixture && (kind == UsageEvents.Event.ACTIVITY_RESUMED
                        || kind == UsageEvents.Event.ACTIVITY_PAUSED
                        || kind == UsageEvents.Event.ACTIVITY_STOPPED)) {
                    count++;
                }
                if (kind == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (fixture) {
                        if (activeStart < 0) activeStart = time;
                    } else if (activeStart >= 0) {
                        total += clippedDuration(activeStart, time, dayStart, nowMillis);
                        activeStart = -1;
                    }
                } else if (fixture && kind == UsageEvents.Event.ACTIVITY_PAUSED) {
                    if (activeStart >= 0) {
                        total += clippedDuration(activeStart, time, dayStart, nowMillis);
                        activeStart = -1;
                    } else if (time >= dayStart) {
                        // A pause without a known resume is not invented into a duration.
                        complete = false;
                    }
                } else if (fixture && kind == UsageEvents.Event.ACTIVITY_STOPPED
                        && activeStart >= 0) {
                    // STOPPED without PAUSED bounds the known interval but signals a gap.
                    total += clippedDuration(activeStart, time, dayStart, nowMillis);
                    activeStart = -1;
                    complete = false;
                } else if (kind == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                        || kind == UsageEvents.Event.DEVICE_SHUTDOWN) {
                    if (activeStart >= 0) {
                        total += clippedDuration(activeStart, time, dayStart, nowMillis);
                        activeStart = -1;
                    }
                }
            }
            boolean foreground = activeStart >= 0;
            if (foreground) total += clippedDuration(activeStart, nowMillis, dayStart, nowMillis);
            return new Snapshot(true, total, foreground, count, complete,
                    complete ? "真实前台事件配对；仅用于单窗口受控验证"
                            : "发现缺失配对，时长可能不完整；未补造使用时间");
        } catch (RuntimeException failure) {
            return new Snapshot(false, 0, false, 0, false,
                    "读取失败：" + failure.getClass().getSimpleName());
        }
    }

    private static long clippedDuration(long start, long end, long dayStart, long now) {
        return Math.max(0, Math.min(end, now) - Math.max(start, dayStart));
    }
}
