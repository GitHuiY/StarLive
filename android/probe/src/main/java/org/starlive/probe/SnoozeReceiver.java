package org.starlive.probe;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.UUID;

public final class SnoozeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Context application = context.getApplicationContext();
        // Only an already-running foreground session accepts snooze. Do not keep a
        // broadcast pending behind a potentially slow query in the serial queue.
        ProbeWorker.post(() -> scheduleOnWorker(application));
    }

    static boolean scheduleOnWorker(Context context) {
        ProbeWorker.requireWorker();
        if (!MonitorService.running || !FixtureUsageReader.hasAccess(context)
                || !ProbeState.notificationsAllowed(context)) {
            ProbeState.error(context, "请先启用测试监测并确认权限，再设置测试稍后提醒");
            return false;
        }
        SharedPreferences state = ProbeState.prefs(context);
        ProbePolicy policy = MonitorService.loadPolicy(state);
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        long uptime = SystemClock.uptimeMillis();
        String requestId = UUID.randomUUID().toString();
        policy.scheduleSnooze(wall);
        MonitorService.savePolicy(state, policy);
        long dueElapsed = elapsed + ProbePolicy.SNOOZE_MILLIS;
        state.edit().putLong("snooze_due_elapsed", dueElapsed)
                .putString("snooze_request_id", requestId).apply();
        context.getSystemService(NotificationManager.class).cancel(ProbeNotifications.ALERT_ID);
        MonitorService.noteSnooze(requestId, wall, elapsed, uptime,
                policy.getSnoozeDueMillis(), dueElapsed);
        ProbeState.writeDiagnostics(context, "snooze_action");
        return true;
    }
}
