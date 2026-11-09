package org.starlive.probe;

import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Service-only markers. No Activity or receiver writes this file. */
final class ServiceDiagnostics {
    final String runId = UUID.randomUUID().toString();
    final long token;
    private final Context context;
    private final JSONObject state = new JSONObject();
    private final ArrayDeque<JSONObject> ticks = new ArrayDeque<>();
    JSONObject tick;
    private long lastWriteDuration;
    private long lastWriteFinishedElapsed;
    private long lastWriteStartedElapsed;
    private int writeCount;
    private final long initialDecisions;
    private final long initialPosted;
    private final long initialSnoozePosted;

    ServiceDiagnostics(Context context, long token) {
        ProbeWorker.requireWorker();
        this.context = context.getApplicationContext();
        this.token = token;
        initialDecisions = ProbeState.prefs(context).getLong("policy_notification_count", 0);
        initialPosted = ProbeState.prefs(context).getLong("posted_notification_count", 0);
        initialSnoozePosted = ProbeState.prefs(context).getLong("snooze_submission_count", 0);
        put(state, "schema_version", ProbeState.SCHEMA_VERSION);
        put(state, "probe_version", ProbeState.PROBE_VERSION);
        put(state, "source", "service_only");
        put(state, "run_id", runId);
        put(state, "process_id", Process.myPid());
        put(state, "worker_thread", Thread.currentThread().getName());
        put(state, "created", clocks());
        put(state, "tick_enter_count", 0);
        put(state, "tick_end_count", 0);
        put(state, "status_is_snapshot", true);
    }

    static JSONObject clocks() {
        JSONObject clock = new JSONObject();
        put(clock, "wall", System.currentTimeMillis());
        put(clock, "elapsed", SystemClock.elapsedRealtime());
        put(clock, "uptime", SystemClock.uptimeMillis());
        return clock;
    }

    void beginTick(long number, long expectedUptime, long expectedElapsed,
            long actualUptime, long actualElapsed) {
        ProbeWorker.requireWorker();
        tick = new JSONObject();
        put(tick, "number", number);
        put(tick, "entered", clocks());
        put(tick, "expected_callback_uptime", expectedUptime);
        put(tick, "expected_callback_elapsed", expectedElapsed);
        put(tick, "actual_callback_uptime", actualUptime);
        put(tick, "actual_callback_elapsed", actualElapsed);
        ProbeTiming.Timing timing = ProbeTiming.compare(expectedUptime, expectedElapsed,
                actualUptime, actualElapsed);
        put(tick, "uptime_jitter_ms", timing.uptimeJitter);
        put(tick, "elapsed_jitter_ms", timing.elapsedJitter);
        put(tick, "extra_sleep_gap_ms", timing.extraSleepGap);
        ticks.addLast(tick);
        while (ticks.size() > 32) ticks.removeFirst();
        put(state, "tick_enter_count", number);
        marker("tick_enter");
        markTick("power_check_start");
        PowerManager power = context.getSystemService(PowerManager.class);
        JSONObject energy = new JSONObject();
        put(energy, "interactive", power.isInteractive());
        put(energy, "power_save", power.isPowerSaveMode());
        put(energy, "device_idle", power.isDeviceIdleMode());
        put(state, "power", energy);
        put(tick, "power", energy);
        markTick("power_check_end");
    }

    void markTick(String name) {
        put(tick, name, clocks());
        marker(name);
    }

    void completed(long count) {
        put(state, "tick_end_count", count);
        put(state, "last_completed_tick", clocks());
        markTick("tick_end");
    }

    void next(long uptime, long elapsed) {
        put(state, "expected_next_uptime", uptime);
        put(state, "expected_next_elapsed", elapsed);
        marker("waiting_for_next_tick");
    }

    void snoozeScheduled(String requestId, long wall, long elapsed, long uptime,
            long dueWall, long dueElapsed) {
        JSONObject snooze = new JSONObject();
        put(snooze, "request_id", requestId);
        JSONObject scheduled = new JSONObject();
        put(scheduled, "wall", wall);
        put(scheduled, "elapsed", elapsed);
        put(scheduled, "uptime", uptime);
        put(snooze, "scheduled_at", scheduled);
        put(snooze, "scheduled_at_wall", wall);
        put(snooze, "scheduled_at_elapsed", elapsed);
        put(snooze, "planned_due_wall", dueWall);
        put(snooze, "planned_due_elapsed", dueElapsed);
        put(snooze, "outcome", "pending");
        put(state, "last_snooze", snooze);
        marker("snooze_scheduled");
    }

    void snoozeDecision(long plannedWall, long plannedElapsed, boolean willNotify) {
        JSONObject snooze = state.optJSONObject("last_snooze");
        if (snooze == null) snooze = new JSONObject();
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        put(snooze, "planned_due_wall", plannedWall);
        put(snooze, "planned_due_elapsed", plannedElapsed);
        put(snooze, "decision_at", clocks());
        put(snooze, "decision_lateness_wall_ms", wall - plannedWall);
        put(snooze, "decision_lateness_elapsed_ms", plannedElapsed == 0
                ? JSONObject.NULL : elapsed - plannedElapsed);
        put(snooze, "outcome", willNotify ? "notify_decided" : "consumed_without_notification");
        put(state, "last_snooze", snooze);
        put(tick, "snooze_decision", new JSONObjectCopy(snooze).value);
    }

    void submitted(boolean snoozeTriggered, boolean success, long plannedWall, long plannedElapsed) {
        JSONObject submission = clocks();
        put(submission, "submitted", success);
        put(tick, "notification_submission", submission);
        if (snoozeTriggered) {
            JSONObject snooze = state.optJSONObject("last_snooze");
            if (snooze == null) snooze = new JSONObject();
            put(snooze, "submission_at", submission);
            put(snooze, "submission_lateness_wall_ms", System.currentTimeMillis() - plannedWall);
            put(snooze, "submission_lateness_elapsed_ms", plannedElapsed == 0
                    ? JSONObject.NULL : SystemClock.elapsedRealtime() - plannedElapsed);
            put(snooze, "outcome", success ? "submitted_to_system" : "submission_failed");
            put(state, "last_snooze", snooze);
        }
    }

    void stopped(String reason) {
        put(state, "stop_reason", reason);
        put(state, "stopped", clocks());
        JSONObject snooze = state.optJSONObject("last_snooze");
        if (snooze != null && ("pending".equals(snooze.optString("outcome"))
                || "notify_decided".equals(snooze.optString("outcome")))) {
            put(snooze, "outcome", "cancelled_by_stop");
        }
        marker("stopped");
    }

    void marker(String phase) {
        ProbeWorker.requireWorker();
        put(state, "phase", phase);
        put(state, "phase_at", clocks());
        put(state, "service_running", MonitorService.running
                && MonitorService.isCurrent(token));
        put(state, "worker_last_unhandled_error", ProbeWorker.lastUnhandledError);
        put(state, "worker_last_unhandled_error_elapsed", ProbeWorker.lastUnhandledErrorElapsed);
        android.content.SharedPreferences prefs = ProbeState.prefs(context);
        put(state, "usage_available", prefs.getBoolean("usage_available", false));
        put(state, "fixture_today_millis", prefs.getBoolean("usage_available", false)
                ? prefs.getLong("fixture_today_millis", 0) : JSONObject.NULL);
        put(state, "fixture_foreground", prefs.getBoolean("usage_available", false)
                && prefs.getBoolean("fixture_foreground", false));
        put(state, "fixture_complete", prefs.getBoolean("usage_available", false)
                && prefs.getBoolean("fixture_complete", false));
        put(state, "last_usage_observed_at", prefs.getLong("last_usage_observed_at", 0));
        long decisions = prefs.getLong("policy_notification_count", 0);
        long posted = prefs.getLong("posted_notification_count", 0);
        long snoozed = prefs.getLong("snooze_submission_count", 0);
        put(state, "counts_scope", "cumulative app state; run_ fields are deltas since this run");
        put(state, "notification_count", decisions);
        put(state, "posted_notification_count", posted);
        put(state, "snooze_submission_count", snoozed);
        put(state, "run_notification_count", Math.max(0, decisions - initialDecisions));
        put(state, "run_posted_notification_count", Math.max(0, posted - initialPosted));
        put(state, "run_snooze_submission_count", Math.max(0, snoozed - initialSnoozePosted));
        put(state, "snooze_due_at", prefs.getLong("policy_snooze_due", 0));
        put(state, "ui_query_started_elapsed", prefs.getLong("ui_query_started_elapsed", 0));
        put(state, "ui_query_finished_elapsed", prefs.getLong("ui_query_finished_elapsed", 0));
        persist();
    }

    private void persist() {
        JSONArray recent = new JSONArray();
        for (JSONObject entry : ticks) recent.put(entry);
        put(state, "recent_ticks", recent);
        put(state, "generated_at", clocks());
        // A write cannot include its own finished duration. The next marker exposes it.
        put(state, "previous_write_duration_elapsed_ms", lastWriteDuration);
        put(state, "previous_write_finished_elapsed", lastWriteFinishedElapsed);
        put(state, "write_count", ++writeCount);
        lastWriteStartedElapsed = SystemClock.elapsedRealtime();
        put(state, "current_write_started_elapsed", lastWriteStartedElapsed);
        AtomicFile target = new AtomicFile(new File(context.getFilesDir(), "service-state.json"));
        FileOutputStream output = null;
        try {
            output = target.startWrite();
            output.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
            lastWriteFinishedElapsed = SystemClock.elapsedRealtime();
            lastWriteDuration = lastWriteFinishedElapsed - lastWriteStartedElapsed;
            ProbeState.prefs(context).edit()
                    .putLong("service_diagnostic_last_write_ms", lastWriteDuration)
                    .putLong("service_diagnostic_last_write_finished_elapsed", lastWriteFinishedElapsed)
                    .apply();
        } catch (Exception failure) {
            if (output != null) target.failWrite(output);
            ProbeState.prefs(context).edit().putString("last_error",
                    "服务诊断写入失败：" + failure.getClass().getSimpleName()).apply();
        }
    }

    static void put(JSONObject object, String key, Object value) {
        if (object == null) return;
        try { object.put(key, value); }
        catch (JSONException failure) { throw new IllegalArgumentException(key, failure); }
    }

    private static final class JSONObjectCopy {
        final JSONObject value;
        JSONObjectCopy(JSONObject source) {
            try { value = new JSONObject(source.toString()); }
            catch (JSONException failure) { throw new IllegalArgumentException(failure); }
        }
    }
}
