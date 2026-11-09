package org.starlive.probe;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/** User-started foreground lifecycle; every query and state operation uses ProbeWorker. */
public final class MonitorService extends Service {
    static final String ACTION_STOP = "org.starlive.probe.STOP";
    static final String EXTRA_START_REQUEST = "org.starlive.probe.START_REQUEST";
    static final long POLL_MILLIS = 10_000L;
    private static final RunGate GATE = new RunGate();
    private static final RunGate START_GATE = new RunGate();
    static volatile boolean running;
    private static volatile long currentToken;
    private static volatile MonitorService activeService;
    private long token;
    private long tickCount;
    private long expectedUptime;
    private long expectedElapsed;
    private boolean finished;
    private ServiceDiagnostics diagnostics;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isCurrent(token)) return;
            long actualUptime = SystemClock.uptimeMillis();
            long actualElapsed = SystemClock.elapsedRealtime();
            tick(actualUptime, actualElapsed);
            if (isCurrent(token)) scheduleNext();
        }
    };

    static boolean isCurrent(long token) {
        return running && currentToken == token && GATE.isCurrent(token);
    }

    static long newStartRequest() { return START_GATE.start(); }
    static boolean isPendingStart(long request) { return START_GATE.isCurrent(request); }
    static boolean dispatchStart(long request, Runnable action) {
        return START_GATE.runIfCurrent(request, action);
    }

    static ProbePolicy loadPolicy(SharedPreferences state) {
        ProbeWorker.requireWorker();
        ProbePolicy policy = new ProbePolicy();
        policy.restore(state.getString("policy_day_key", ""),
                state.getBoolean("policy_threshold_sent", false),
                state.getLong("policy_snooze_due", 0),
                state.getLong("policy_notification_count", 0),
                state.getLong("policy_deduped_count", 0));
        return policy;
    }

    static void savePolicy(SharedPreferences state, ProbePolicy policy) {
        ProbeWorker.requireWorker();
        state.edit().putString("policy_day_key", policy.getDayKey())
                .putBoolean("policy_threshold_sent", policy.isThresholdSent())
                .putLong("policy_snooze_due", policy.getSnoozeDueMillis())
                .putLong("policy_notification_count", policy.getNotificationCount())
                .putLong("policy_deduped_count", policy.getDedupedCount()).apply();
    }

    /** Invalidates in-flight work immediately; cancellation cleanup is serial worker work. */
    static void requestStop(Context context, String reason) {
        MonitorService service = activeService;
        START_GATE.cancel();
        running = false;
        // This only waits if the final system notification call has already begun.
        GATE.cancel();
        ProbeWorker.post(() -> {
            if (service != null) service.finishSession(reason);
            else {
                SharedPreferences state = ProbeState.prefs(context);
                ProbePolicy policy = loadPolicy(state);
                policy.cancelSnooze();
                savePolicy(state, policy);
                state.edit().putBoolean("service_running", false)
                        .putLong("snooze_due_elapsed", 0).apply();
                ProbeState.writeDiagnostics(context, "stop_without_active_service");
            }
        });
        context.stopService(new Intent(context, MonitorService.class));
    }

    static void noteSnooze(String requestId, long wall, long elapsed, long uptime,
            long dueWall, long dueElapsed) {
        ProbeWorker.requireWorker();
        MonitorService service = activeService;
        if (service != null && isCurrent(service.token) && service.diagnostics != null) {
            service.diagnostics.snoozeScheduled(requestId, wall, elapsed, uptime, dueWall, dueElapsed);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop(this, "notification_stop");
            return START_NOT_STICKY;
        }
        long startRequest = intent == null ? 0 : intent.getLongExtra(EXTRA_START_REQUEST, 0);
        if (!isPendingStart(startRequest)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running && activeService == this) return START_NOT_STICKY;
        ProbeNotifications.createChannels(this);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ProbeNotifications.MONITOR_ID, ProbeNotifications.monitor(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ProbeNotifications.MONITOR_ID, ProbeNotifications.monitor(this));
            }
        } catch (RuntimeException failure) {
            ProbeState.error(this, "前台服务启动失败：" + failure.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        token = GATE.start();
        currentToken = token;
        activeService = this;
        running = true;
        final long requestedToken = token;
        ProbeWorker.post(() -> beginSession(requestedToken));
        return START_NOT_STICKY;
    }

    private void beginSession(long requestedToken) {
        ProbeWorker.requireWorker();
        if (!isCurrent(requestedToken)) return;
        finished = false;
        tickCount = 0;
        diagnostics = new ServiceDiagnostics(this, token);
        SharedPreferences state = ProbeState.prefs(this);
        ProbePolicy policy = loadPolicy(state);
        policy.cancelSnooze();
        savePolicy(state, policy);
        state.edit().putBoolean("service_running", true)
                .putLong("service_started_at", System.currentTimeMillis())
                .putString("service_run_id", diagnostics.runId)
                .putLong("snooze_due_elapsed", 0)
                .putString("last_error", "").apply();
        diagnostics.marker("service_started");
        expectedUptime = SystemClock.uptimeMillis();
        expectedElapsed = SystemClock.elapsedRealtime();
        diagnostics.next(expectedUptime, expectedElapsed);
        ProbeWorker.handler().postAtTime(poll, expectedUptime);
    }

    private void scheduleNext() {
        ProbeWorker.requireWorker();
        expectedUptime = SystemClock.uptimeMillis() + POLL_MILLIS;
        expectedElapsed = SystemClock.elapsedRealtime() + POLL_MILLIS;
        diagnostics.next(expectedUptime, expectedElapsed);
        if (isCurrent(token)) ProbeWorker.handler().postAtTime(poll, expectedUptime);
    }

    private void tick(long actualUptime, long actualElapsed) {
        ProbeWorker.requireWorker();
        SharedPreferences state = ProbeState.prefs(this);
        try {
            diagnostics.beginTick(++tickCount, expectedUptime, expectedElapsed,
                    actualUptime, actualElapsed);
            diagnostics.markTick("permission_check_start");
            boolean usagePermission = FixtureUsageReader.hasAccess(this);
            boolean notificationPermission = ProbeState.notificationsAllowed(this);
            boolean permission = usagePermission && notificationPermission;
            state.edit().putBoolean("usage_permission", usagePermission)
                    .putBoolean("notifications_allowed", notificationPermission).apply();
            if (!usagePermission) state.edit().putBoolean("usage_available", false).apply();
            diagnostics.markTick("permission_check_end");
            if (!isCurrent(token)) {
                diagnostics.markTick("cancelled_before_query");
                return;
            }
            if (!permission) {
                ProbeState.error(this, "使用情况或通知权限不可用；测试监测已停止");
                requestStop(this, "permission_unavailable");
                return;
            }
            diagnostics.markTick("query_start");
            long queryStart = SystemClock.elapsedRealtime();
            FixtureUsageReader.Snapshot snapshot = FixtureUsageReader.read(this,
                    System.currentTimeMillis());
            long queryEnd = SystemClock.elapsedRealtime();
            ServiceDiagnostics.put(diagnostics.tick, "query_duration_ms", queryEnd - queryStart);
            diagnostics.markTick("query_end");
            if (!isCurrent(token)) {
                diagnostics.markTick("cancelled_after_query");
                return;
            }
            ProbeState.recordUsage(this, snapshot);
            ServiceDiagnostics.put(diagnostics.tick, "fixture_today_millis",
                    snapshot.available ? snapshot.todayMillis : org.json.JSONObject.NULL);
            ServiceDiagnostics.put(diagnostics.tick, "fixture_foreground", snapshot.foreground);
            if (!snapshot.available) {
                ProbeState.error(this, snapshot.note);
                completeTick(state);
                return;
            }
            ProbePolicy policy = loadPolicy(state);
            long plannedSnoozeWall = policy.getSnoozeDueMillis();
            long plannedSnoozeElapsed = state.getLong("snooze_due_elapsed", 0);
            long decisionWall = System.currentTimeMillis();
            ProbePolicy.Decision decision = policy.evaluate(ProbeState.dayKey(), decisionWall,
                    snapshot.todayMillis, snapshot.foreground, true, true);
            ServiceDiagnostics.put(diagnostics.tick, "policy_decision", ServiceDiagnostics.clocks());
            if (plannedSnoozeWall != 0 && decisionWall >= plannedSnoozeWall) {
                diagnostics.snoozeDecision(plannedSnoozeWall, plannedSnoozeElapsed,
                        decision.snoozeTriggered);
            }
            diagnostics.markTick(decision.shouldNotify ? "notify_ready" : "policy_no_notification");
            boolean committed = GATE.runIfCurrent(token, () -> {
                if (!running || currentToken != token) return;
                if (decision.shouldNotify) {
                    String reason = decision.thresholdTriggered && decision.snoozeTriggered
                            ? "测试计时页累计已达 30 秒，且测试稍后提醒到期（合并一条）。"
                            : decision.snoozeTriggered ? "测试稍后提醒到期；测试计时页仍在前台。"
                            : "测试计时页今日累计已达 30 秒。";
                    ServiceDiagnostics.put(diagnostics.tick, "notify_call_started", ServiceDiagnostics.clocks());
                    long notifyStarted = SystemClock.elapsedRealtime();
                    boolean posted = ProbeNotifications.alert(this, reason);
                    ServiceDiagnostics.put(diagnostics.tick, "notify_call_duration_ms",
                            SystemClock.elapsedRealtime() - notifyStarted);
                    diagnostics.submitted(decision.snoozeTriggered, posted,
                            plannedSnoozeWall, plannedSnoozeElapsed);
                    if (posted) state.edit().putLong("posted_notification_count",
                            state.getLong("posted_notification_count", 0) + 1).apply();
                    if (posted && decision.snoozeTriggered) {
                        state.edit().putLong("snooze_submission_count",
                                state.getLong("snooze_submission_count", 0) + 1).apply();
                    }
                }
                savePolicy(state, policy);
                if (policy.getSnoozeDueMillis() == 0) {
                    state.edit().putLong("snooze_due_elapsed", 0).apply();
                }
            });
            if (!committed || !isCurrent(token)) {
                diagnostics.markTick("cancelled_before_commit_or_after_dispatch");
                return;
            }
            completeTick(state);
        } catch (RuntimeException failure) {
            ServiceDiagnostics.put(diagnostics.tick, "error", failure.getClass().getSimpleName());
            diagnostics.markTick("tick_error");
            ProbeState.error(this, "轮询失败：" + failure.getClass().getSimpleName());
            requestStop(this, "tick_error");
        }
    }

    private void completeTick(SharedPreferences state) {
        state.edit().putLong("last_heartbeat_at", System.currentTimeMillis())
                .putLong("last_heartbeat_elapsed", SystemClock.elapsedRealtime())
                .putLong("loop_count", state.getLong("loop_count", 0) + 1).apply();
        diagnostics.completed(tickCount);
        ProbeState.writeDiagnostics(this, "service_tick_completed");
    }

    private void finishSession(String reason) {
        ProbeWorker.requireWorker();
        if (finished) return;
        finished = true;
        ProbeWorker.handler().removeCallbacks(poll);
        if (currentToken != token) return;
        SharedPreferences state = ProbeState.prefs(this);
        ProbePolicy policy = loadPolicy(state);
        policy.cancelSnooze();
        savePolicy(state, policy);
        state.edit().putBoolean("service_running", false)
                .putLong("snooze_due_elapsed", 0).apply();
        if (diagnostics != null) diagnostics.stopped(reason);
        ProbeState.writeDiagnostics(this, "service_stopped");
    }

    @Override public void onDestroy() {
        if (activeService == this) {
            running = false;
            GATE.cancel();
            activeService = null;
        }
        ProbeWorker.post(() -> finishSession("service_destroyed"));
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
