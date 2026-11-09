package org.starlive.probe;

import android.Manifest;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONObject;

/** Only non-sensitive probe diagnostics are saved, never unrelated packages or raw events. */
final class ProbeState {
    static final int SCHEMA_VERSION = 2;
    static final String PROBE_VERSION = "0.2-probe";
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("probe_state", Context.MODE_PRIVATE);
    }

    static boolean notificationPermissionGranted(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean channelEnabled(Context context, String channelId) {
        NotificationChannel channel = context.getSystemService(NotificationManager.class)
                .getNotificationChannel(channelId);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static boolean notificationsAllowed(Context context) {
        return notificationPermissionGranted(context)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled()
                && channelEnabled(context, ProbeNotifications.ALERT_CHANNEL)
                && channelEnabled(context, ProbeNotifications.MONITOR_CHANNEL);
    }

    static String dayKey() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    static void recordUsage(Context context, FixtureUsageReader.Snapshot snapshot) {
        ProbeWorker.requireWorker();
        SharedPreferences.Editor edit = prefs(context).edit()
                .putBoolean("usage_permission", FixtureUsageReader.hasAccess(context))
                .putBoolean("notifications_allowed", notificationsAllowed(context))
                .putBoolean("usage_available", snapshot.available)
                .putBoolean("fixture_foreground", snapshot.foreground)
                .putBoolean("fixture_complete", snapshot.complete)
                .putInt("fixture_event_count", snapshot.fixtureEventCount)
                .putString("usage_note", snapshot.note)
                .putLong("last_usage_observed_at", System.currentTimeMillis());
        if (snapshot.available) edit.putLong("fixture_today_millis", snapshot.todayMillis);
        edit.apply();
    }

    static void error(Context context, String message) {
        if (!ProbeWorker.isWorkerThread()) {
            Context application = context.getApplicationContext();
            ProbeWorker.post(() -> error(application, message));
            return;
        }
        prefs(context).edit().putString("last_error", message).apply();
        writeDiagnostics(context, "error");
    }

    static void writeDiagnostics(Context context, String source) {
        ProbeWorker.requireWorker();
        SharedPreferences state = prefs(context);
        AtomicFile target = new AtomicFile(new File(context.getFilesDir(), "probe-state.json"));
        FileOutputStream output = null;
        try {
            JSONObject json = new JSONObject();
            json.put("schema_version", SCHEMA_VERSION);
            json.put("probe_version", PROBE_VERSION);
            json.put("source", source);
            json.put("service_run_id", state.getString("service_run_id", ""));
            json.put("worker_thread", Thread.currentThread().getName());
            json.put("worker_last_unhandled_error", ProbeWorker.lastUnhandledError);
            json.put("worker_last_unhandled_error_elapsed", ProbeWorker.lastUnhandledErrorElapsed);
            json.put("purpose", "local capability probe; controlled fixture only");
            json.put("status_is_snapshot", true);
            json.put("generated_at", System.currentTimeMillis());
            json.put("system_timezone", ZoneId.systemDefault().getId());
            json.put("day_key", dayKey());
            json.put("usage_permission", FixtureUsageReader.hasAccess(context));
            json.put("notifications_allowed", notificationsAllowed(context));
            json.put("notification_permission_granted", notificationPermissionGranted(context));
            json.put("alert_channel_enabled", channelEnabled(context, ProbeNotifications.ALERT_CHANNEL));
            json.put("monitor_channel_enabled", channelEnabled(context, ProbeNotifications.MONITOR_CHANNEL));
            json.put("service_running", MonitorService.running);
            json.put("service_started_at", state.getLong("service_started_at", 0));
            json.put("last_heartbeat_at", state.getLong("last_heartbeat_at", 0));
            json.put("last_heartbeat_elapsed", state.getLong("last_heartbeat_elapsed", 0));
            json.put("heartbeat_age_millis_at_snapshot", state.getLong("last_heartbeat_elapsed", 0) == 0
                    ? JSONObject.NULL : Math.max(0, SystemClock.elapsedRealtime()
                            - state.getLong("last_heartbeat_elapsed", 0)));
            json.put("loop_count", state.getLong("loop_count", 0));
            boolean available = state.getBoolean("usage_available", false)
                    && FixtureUsageReader.hasAccess(context);
            json.put("usage_available", available);
            json.put("fixture_today_millis", available
                    ? state.getLong("fixture_today_millis", 0) : JSONObject.NULL);
            json.put("fixture_foreground", available && state.getBoolean("fixture_foreground", false));
            json.put("fixture_event_count", state.getInt("fixture_event_count", 0));
            json.put("fixture_complete", available && state.getBoolean("fixture_complete", false));
            json.put("last_usage_observed_at", state.getLong("last_usage_observed_at", 0));
            json.put("usage_snapshot_age_millis_at_snapshot", state.getLong("last_usage_observed_at", 0) == 0
                    ? JSONObject.NULL : Math.max(0, System.currentTimeMillis()
                            - state.getLong("last_usage_observed_at", 0)));
            json.put("test_threshold_millis", ProbePolicy.THRESHOLD_MILLIS);
            json.put("test_snooze_millis", ProbePolicy.SNOOZE_MILLIS);
            json.put("threshold_sent", state.getBoolean("policy_threshold_sent", false));
            json.put("snooze_due_at", state.getLong("policy_snooze_due", 0));
            json.put("notification_count", state.getLong("policy_notification_count", 0));
            json.put("posted_notification_count", state.getLong("posted_notification_count", 0));
            json.put("snooze_submission_count", state.getLong("snooze_submission_count", 0));
            json.put("deduped_count", state.getLong("policy_deduped_count", 0));
            json.put("manual_notification_count", state.getLong("manual_notification_count", 0));
            json.put("last_error", state.getString("last_error", ""));
            json.put("service_diagnostic_last_write_ms", state.getLong("service_diagnostic_last_write_ms", 0));
            json.put("service_diagnostic_last_write_finished_elapsed",
                    state.getLong("service_diagnostic_last_write_finished_elapsed", 0));
            json.put("previous_ui_diagnostic_write_ms", state.getLong("ui_diagnostic_last_write_ms", 0));
            json.put("ui_query_started_elapsed", state.getLong("ui_query_started_elapsed", 0));
            json.put("ui_query_finished_elapsed", state.getLong("ui_query_finished_elapsed", 0));
            long writeStarted = SystemClock.elapsedRealtime();
            output = target.startWrite();
            output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
            state.edit().putLong("ui_diagnostic_last_write_ms",
                    SystemClock.elapsedRealtime() - writeStarted).apply();
        } catch (Exception failure) {
            if (output != null) target.failWrite(output);
            prefs(context).edit().putString("last_error",
                    "诊断写入失败：" + failure.getClass().getSimpleName()).apply();
        }
    }
}
