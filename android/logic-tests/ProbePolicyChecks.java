package org.starlive.probe;

/** Dependency-free checks; run with javac/java, without an Android device. */
public final class ProbePolicyChecks {
    private static final String TODAY = "2026-11-08";
    private static final String TOMORROW = "2026-11-09";
    private static int assertions;

    public static void main(String[] args) {
        thresholdBoundaryAndDailyDedupe();
        persistedStatePreventsRestartDuplicate();
        permissionAndMonitoringDoNotConsumeThreshold();
        snoozeTimingAndConsumption();
        dueSnoozeRequiresForegroundAndPermission();
        stopCancelsSnooze();
        snoozeReplacementAndMergedCauses();
        midnightAndDefensiveState();
        System.out.println("ProbePolicy checks passed: " + assertions);
    }

    private static void thresholdBoundaryAndDailyDedupe() {
        ProbePolicy p = new ProbePolicy();
        check(!poll(p, TODAY, 100_000L, 29_999L, true).shouldNotify,
                "Below 30 seconds must not notify");
        ProbePolicy.Decision reached = poll(p, TODAY, 100_001L, 30_000L, true);
        check(reached.shouldNotify && reached.thresholdTriggered
                && !reached.snoozeTriggered, "Exactly 30 seconds must notify");
        check(!poll(p, TODAY, 110_000L, 30_001L, true).shouldNotify,
                "Threshold must not repeat later in the same day");
        check(p.getNotificationCount() == 1L && p.getDedupedCount() == 1L,
                "Notification and duplicate counters must match decisions");
        check(poll(p, TOMORROW, 120_000L, 30_000L, true).shouldNotify,
                "A new day must permit a new threshold notification");
        check(p.getNotificationCount() == 2L, "Notification count survives a date change");
    }

    private static void persistedStatePreventsRestartDuplicate() {
        ProbePolicy p = new ProbePolicy();
        poll(p, TODAY, 100_000L, 30_000L, true);
        p.scheduleSnooze(110_000L);
        ProbePolicy restarted = new ProbePolicy();
        restarted.restore(p.getDayKey(), p.isThresholdSent(), p.getSnoozeDueMillis(),
                p.getNotificationCount(), p.getDedupedCount());
        check(!poll(restarted, TODAY, 120_000L, 60_000L, true).shouldNotify,
                "Restarting must not repeat the daily threshold");
        check(restarted.getSnoozeDueMillis() == 140_000L,
                "A restart must preserve the explicit snooze timer");
        ProbePolicy.Decision due = poll(restarted, TODAY, 140_000L, 60_000L, true);
        check(due.shouldNotify && due.snoozeTriggered && !due.thresholdTriggered,
                "A restored snooze must fire at its due time");
    }

    private static void permissionAndMonitoringDoNotConsumeThreshold() {
        ProbePolicy p = new ProbePolicy();
        check(!p.evaluate(TODAY, 1L, 30_000L, true, true, false).shouldNotify,
                "Missing permission must suppress the notification");
        check(!p.isThresholdSent(), "Missing permission must not consume the threshold");
        check(!p.evaluate(TODAY, 2L, 30_000L, true, false, true).shouldNotify,
                "Disabled monitoring must suppress the notification");
        check(!p.isThresholdSent(), "Disabled monitoring must not consume the threshold");
        check(p.evaluate(TODAY, 3L, 30_000L, false, true, true).thresholdTriggered,
                "The daily cumulative threshold does not require current foreground use");
    }

    private static void snoozeTimingAndConsumption() {
        ProbePolicy p = notifiedPolicy();
        p.scheduleSnooze(200_000L);
        check(!poll(p, TODAY, 229_999L, 30_000L, true).shouldNotify,
                "Snooze must not fire one millisecond early");
        ProbePolicy.Decision due = poll(p, TODAY, 230_000L, 30_000L, true);
        check(due.shouldNotify && due.snoozeTriggered,
                "Snooze must fire exactly at its due time when still in use");
        check(p.getSnoozeDueMillis() == 0L, "A fired snooze must be consumed");
        check(!poll(p, TODAY, 240_000L, 40_000L, true).shouldNotify,
                "A snooze must not automatically repeat");
    }

    private static void dueSnoozeRequiresForegroundAndPermission() {
        ProbePolicy away = notifiedPolicy();
        away.scheduleSnooze(200_000L);
        check(!poll(away, TODAY, 230_000L, 30_000L, false).shouldNotify,
                "A due snooze must not notify when the fixture is no longer foreground");
        check(away.getSnoozeDueMillis() == 0L, "An inactive due snooze is consumed");
        check(!poll(away, TODAY, 240_000L, 40_000L, true).shouldNotify,
                "Returning later must not revive an expired snooze");

        ProbePolicy revoked = notifiedPolicy();
        revoked.scheduleSnooze(200_000L);
        check(!revoked.evaluate(TODAY, 230_000L, 30_000L, true, true, false).shouldNotify,
                "A due snooze must not notify after permission is revoked");
        check(revoked.getSnoozeDueMillis() == 0L,
                "A due snooze is consumed even while permission is unavailable");
        check(!poll(revoked, TODAY, 240_000L, 40_000L, true).shouldNotify,
                "Regranting permission must not revive the expired snooze");
    }

    private static void stopCancelsSnooze() {
        ProbePolicy p = notifiedPolicy();
        p.scheduleSnooze(200_000L);
        check(!p.evaluate(TODAY, 210_000L, 30_000L, true, false, true).shouldNotify,
                "Stopping monitoring must not notify");
        check(p.getSnoozeDueMillis() == 0L, "Stopping monitoring cancels a future snooze");
        check(!poll(p, TODAY, 240_000L, 30_000L, true).shouldNotify,
                "Restarting monitoring must not revive the cancelled snooze");
        p.scheduleSnooze(250_000L);
        p.cancelSnooze();
        check(p.getSnoozeDueMillis() == 0L, "Explicit cancellation removes the timer");
    }

    private static void snoozeReplacementAndMergedCauses() {
        ProbePolicy p = new ProbePolicy();
        p.scheduleSnooze(100_000L);
        p.scheduleSnooze(110_000L);
        check(p.getSnoozeDueMillis() == 140_000L,
                "Repeated snooze actions replace one timer rather than creating duplicates");
        check(!poll(p, TODAY, 130_000L, 20_000L, true).shouldNotify,
                "The replaced timer must not fire at its old due time");
        ProbePolicy.Decision merged = poll(p, TODAY, 140_000L, 30_000L, true);
        check(merged.shouldNotify && merged.thresholdTriggered && merged.snoozeTriggered,
                "Simultaneous threshold and snooze conditions retain both reasons");
        check(p.getNotificationCount() == 1L && p.getDedupedCount() == 1L,
                "Simultaneous reasons produce one notification decision");
    }

    private static void midnightAndDefensiveState() {
        ProbePolicy p = notifiedPolicy();
        p.scheduleSnooze(200_000L);
        check(!poll(p, TOMORROW, 220_000L, 0L, true).shouldNotify,
                "Date rollover alone must not trigger a threshold");
        check(!p.isThresholdSent() && p.getSnoozeDueMillis() == 230_000L,
                "Rollover resets the daily threshold while preserving an explicit snooze");
        check(poll(p, TOMORROW, 230_000L, 10_000L, true).snoozeTriggered,
                "An explicit snooze may cross the local day boundary");

        ProbePolicy defensive = new ProbePolicy();
        defensive.restore(null, false, -1L, -2L, -3L);
        check(defensive.getDayKey().equals("") && defensive.getSnoozeDueMillis() == 0L
                && defensive.getNotificationCount() == 0L && defensive.getDedupedCount() == 0L,
                "Invalid restored counters and due times are clamped");
        check(!defensive.evaluate(null, 0L, -100L, true, true, true).shouldNotify,
                "Negative usage values must not trigger notifications");
        defensive.scheduleSnooze(Long.MAX_VALUE - 1L);
        check(defensive.getSnoozeDueMillis() == Long.MAX_VALUE,
                "Snooze scheduling must not wrap its timestamp");
        defensive.restore(TODAY, true, 0L, Long.MAX_VALUE, Long.MAX_VALUE);
        check(!poll(defensive, TODAY, 1L, 30_000L, true).shouldNotify
                && defensive.getDedupedCount() == Long.MAX_VALUE,
                "Diagnostic counters saturate instead of overflowing");
    }

    private static ProbePolicy notifiedPolicy() {
        ProbePolicy p = new ProbePolicy();
        poll(p, TODAY, 100_000L, 30_000L, true);
        return p;
    }

    private static ProbePolicy.Decision poll(ProbePolicy p, String day,
            long nowMillis, long usageMillis, boolean foreground) {
        return p.evaluate(day, nowMillis, usageMillis, foreground, true, true);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
