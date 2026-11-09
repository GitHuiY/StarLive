package io.github.githuiy.starlive.core.monitor;

import static io.github.githuiy.starlive.core.monitor.ReminderPolicy.SnoozeStatus.*;
import static io.github.githuiy.starlive.core.monitor.ReminderPolicy.Type.*;
import static io.github.githuiy.starlive.core.monitor.UsageEvent.Type.PAUSE;
import static io.github.githuiy.starlive.core.monitor.UsageEvent.Type.RESUME;
import static io.github.githuiy.starlive.core.monitor.UsageEvent.Type.SCREEN_INTERACTIVE;
import static io.github.githuiy.starlive.core.monitor.UsageEvent.Type.SCREEN_NON_INTERACTIVE;
import static org.junit.Assert.*;

import io.github.githuiy.starlive.core.ReminderSettings;
import io.github.githuiy.starlive.core.monitor.ReminderPolicy.Decision;
import io.github.githuiy.starlive.core.monitor.ReminderPolicy.PendingSnooze;
import io.github.githuiy.starlive.core.monitor.ReminderPolicy.Type;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ReminderPolicyTest {
    private static final long MINUTE = 60_000;
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long MIDNIGHT = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli();
    private static final long WINDOW = MIDNIGHT - 24 * 60 * MINUTE;
    private static final long SCOPE = MIDNIGHT - 10 * MINUTE;
    private static final long START = MIDNIGHT + 10 * MINUTE;
    private static final long BOOT = 7;
    private static final Set<String> PACKAGES = new LinkedHashSet<>(Arrays.asList("a", "b"));
    private static final ReminderSettings ONE_MINUTE =
            new ReminderSettings(1, 1, 1, true, true, true);
    private static final ReminderSettings OFF =
            new ReminderSettings(60, 60, 180, false, false, false);

    @Test public void atThresholdMergesAllReasonsAndPersistentKeysPreventRepeatedPoll() {
        UsageEvent use = activity(RESUME, START, "a");
        Decision before = decide(sample(START + MINUTE - 1, null, use),
                ONE_MINUTE, Collections.emptySet());
        assertTrue(before.reasons.isEmpty());

        Decision at = decide(sample(START + MINUTE, null, use),
                ONE_MINUTE, Collections.emptySet());
        assertEquals(Arrays.asList(CONTINUOUS, PER_APP_DAILY, TOTAL_DAILY), types(at));
        assertEquals("a", at.reasons.get(1).packageName);
        assertEquals(3, at.newKeys.size());

        Decision after = decide(sample(START + MINUTE + 1, null, use), ONE_MINUTE, at.newKeys);
        assertTrue(after.reasons.isEmpty());
        assertTrue(after.newKeys.isEmpty());
    }

    @Test public void productionThresholdsRemain60_60_180Minutes() {
        UsageEvent use = activity(RESUME, START, "a");
        Decision first = decide(sample(START + 60 * MINUTE, null, use),
                ReminderSettings.defaults(), Collections.emptySet());
        assertEquals(Arrays.asList(CONTINUOUS, PER_APP_DAILY), types(first));
        Decision totalBefore = decide(sample(START + 180 * MINUTE - 1, null, use),
                ReminderSettings.defaults(), first.newKeys);
        assertTrue(totalBefore.reasons.isEmpty());
        Decision total = decide(sample(START + 180 * MINUTE, null, use),
                ReminderSettings.defaults(), first.newKeys);
        assertEquals(Collections.singletonList(TOTAL_DAILY), types(total));
    }

    @Test public void nextLocalDayAllowsDailyReasonsButSameSessionStaysDeduped() {
        UsageEvent use = activity(RESUME, MIDNIGHT - 2 * MINUTE, "a");
        Decision first = decide(sample(MIDNIGHT - MINUTE, null, use),
                ONE_MINUTE, Collections.emptySet());
        Decision next = decide(sample(MIDNIGHT + MINUTE, null, use), ONE_MINUTE, first.newKeys);
        assertEquals(Arrays.asList(PER_APP_DAILY, TOTAL_DAILY), types(next));
    }

    @Test public void noHistoricalPerAppCatchUpAndLoweredThresholdWaitsForActiveUse() {
        ReminderSettings perAppOnly = new ReminderSettings(1, 1, 1, false, true, false);
        UsageEvent startA = activity(RESUME, START, "a");
        UsageEvent leaveA = activity(PAUSE, START + 2 * MINUTE, "a");
        UsageEvent startB = activity(RESUME, START + 2 * MINUTE + 10, "b");
        UsageSnapshot idle = sample(START + 2 * MINUTE + 5, null, startA, leaveA);
        UsageSnapshot usingB = sample(START + 2 * MINUTE + 20, null, startA, leaveA, startB);
        assertTrue(decide(idle, perAppOnly, Collections.emptySet()).reasons.isEmpty());
        assertTrue(decide(usingB, perAppOnly, Collections.emptySet()).reasons.isEmpty());

        UsageSnapshot returns = sample(START + 2 * MINUTE + 30, null, startA, leaveA, startB,
                activity(RESUME, START + 2 * MINUTE + 29, "a"));
        Decision active = decide(returns, perAppOnly, Collections.emptySet());
        assertEquals(Collections.singletonList(PER_APP_DAILY), types(active));
        assertEquals("a", active.reasons.get(0).packageName);
    }

    @Test public void changingTimezoneAndReturningPreservesPreviouslySentDateKeys() {
        long now = MIDNIGHT + MINUTE;
        List<UsageEvent> events = Arrays.asList(
                new UsageEvent(SCREEN_NON_INTERACTIVE, WINDOW + 1, null, null),
                new UsageEvent(SCREEN_INTERACTIVE, WINDOW + 2, null, null),
                activity(RESUME, MIDNIGHT - MINUTE, "a"));
        UsageSnapshot utc = UsageTimeline.fold(events, WINDOW, now, UTC, PACKAGES, SCOPE, null);
        Decision first = decide(utc, ONE_MINUTE, Collections.emptySet());
        UsageSnapshot west = UsageTimeline.fold(events, WINDOW, now,
                ZoneId.of("America/Los_Angeles"), PACKAGES, SCOPE, null);
        Decision shift = decide(west, ONE_MINUTE, first.newKeys);
        assertEquals(Arrays.asList(PER_APP_DAILY, TOTAL_DAILY), types(shift));
        Set<String> persisted = new LinkedHashSet<>(first.newKeys);
        persisted.addAll(shift.newKeys);
        assertTrue(decide(utc, ONE_MINUTE, persisted).reasons.isEmpty());
    }

    @Test public void noThresholdIsConsumedWhenMonitoringOrPermissionIsOff() {
        UsageSnapshot use = sample(START + MINUTE, null, activity(RESUME, START, "a"));
        for (boolean monitoring : new boolean[] {false, true}) {
            Decision decision = ReminderPolicy.evaluate(use, ONE_MINUTE, Collections.emptySet(),
                    null, SCOPE, MINUTE, BOOT, monitoring, !monitoring, true);
            assertTrue(decision.reasons.isEmpty());
            assertTrue(decision.newKeys.isEmpty());
        }
    }

    @Test public void unknownContinuousStartCannotTriggerContinuousButKnownDailyLowerBoundCan() {
        UsageSnapshot snapshot = UsageTimeline.fold(
                Collections.singletonList(activity(RESUME, START, "a")), MIDNIGHT,
                START + MINUTE, UTC, PACKAGES, SCOPE, null);
        assertFalse(snapshot.todayComplete);
        assertNull(snapshot.sessionStartMillis);
        assertEquals(Arrays.asList(PER_APP_DAILY, TOTAL_DAILY),
                types(decide(snapshot, ONE_MINUTE, Collections.emptySet())));
    }

    @Test public void snoozeUses30MinutesAndMergesAtDueWithNewThresholdReasons() {
        PendingSnooze pending = request();
        assertEquals(30 * MINUTE, pending.dueElapsedMillis - pending.scheduledElapsedMillis);
        Decision before = snooze(sample(pending.dueWallMillis - 1, null,
                activity(RESUME, START, "a")), pending, pending.dueElapsedMillis - 1);
        assertEquals(PENDING, before.snoozeStatus);
        assertSame(pending, before.pending);
        assertTrue(before.reasons.isEmpty());

        UsageSnapshot use = sample(pending.dueWallMillis, pending.dueWallMillis,
                activity(RESUME, START, "a"));
        Decision at = ReminderPolicy.evaluate(use, ONE_MINUTE, Collections.emptySet(), pending,
                SCOPE, pending.dueElapsedMillis, BOOT, true, true, true);
        assertEquals(Arrays.asList(CONTINUOUS, PER_APP_DAILY, TOTAL_DAILY, SNOOZE), types(at));
        assertEquals(FIRED, at.snoozeStatus);
        assertEquals(Long.valueOf(0), at.snoozeDelayMillis);
        assertNull(at.pending);
        assertTrue(decide(use, ONE_MINUTE, at.newKeys).reasons.isEmpty());
    }

    @Test public void delayedSnoozeNeedsBothHistoricalAndCurrentEvidence() {
        PendingSnooze pending = request();
        long due = pending.dueWallMillis;
        long actual = due + 5_000;
        long elapsed = pending.dueElapsedMillis + 5_000;
        UsageEvent resume = activity(RESUME, START, "a");
        Decision both = snooze(sample(actual, due, resume), pending, elapsed);
        assertEquals(FIRED, both.snoozeStatus);
        assertEquals(Long.valueOf(5_000), both.snoozeDelayMillis);

        Decision onlyNow = snooze(sample(actual, due, resume, activity(PAUSE, due - 1, "a"),
                activity(RESUME, due + 1, "a")), pending, elapsed);
        Decision onlyDue = snooze(sample(actual, due, resume, activity(PAUSE, due + 1, "a")),
                pending, elapsed);
        for (Decision missed : Arrays.asList(onlyNow, onlyDue)) {
            assertEquals(DROPPED_INACTIVE, missed.snoozeStatus);
            assertTrue(missed.reasons.isEmpty());
            assertNull(missed.pending);
        }
    }

    @Test public void activeNowCannotFillAnUnknownSnoozeDeadlineOrReuseAnotherDeadline() {
        PendingSnooze pending = request();
        long due = pending.dueWallMillis;
        UsageSnapshot truncated = UsageTimeline.fold(
                Collections.singletonList(activity(RESUME, due + 10, "a")), due + 1, due + 20,
                UTC, PACKAGES, SCOPE, due);
        Decision missing = snooze(truncated, pending, pending.dueElapsedMillis + 20);
        assertEquals(DROPPED_UNKNOWN, missing.snoozeStatus);
        assertNull(missing.pending);

        UsageSnapshot mismatched = sample(due + 20, due + 1, activity(RESUME, START, "a"));
        assertEquals(DROPPED_UNKNOWN,
                snooze(mismatched, pending, pending.dueElapsedMillis + 20).snoozeStatus);
    }

    @Test public void smallClockSamplingDifferenceDoesNotConsumeDeadlineBeforeObservation() {
        PendingSnooze pending = request();
        long due = pending.dueWallMillis;
        UsageEvent resume = activity(RESUME, START, "a");
        Decision almost = snooze(sample(due - 1, due, resume), pending, pending.dueElapsedMillis);
        assertEquals(PENDING, almost.snoozeStatus);
        assertSame(pending, almost.pending);

        Decision observed = snooze(sample(due, due, resume), pending, pending.dueElapsedMillis + 1);
        assertEquals(FIRED, observed.snoozeStatus);
        assertEquals(Long.valueOf(1), observed.snoozeDelayMillis);
    }

    @Test public void stopBootScopeAndClockChangesCancelPendingSnooze() {
        PendingSnooze pending = request();
        assertEquals(CANCELED_MONITORING,
                status(pending, SCOPE, BOOT, false, true, true));
        assertEquals(CANCELED_BOOT,
                status(pending, SCOPE, BOOT + 1, true, true, true));
        assertEquals(CANCELED_SCOPE,
                status(pending, SCOPE + 1, BOOT, true, true, true));
        assertEquals(CANCELED_CLOCK,
                status(pending, SCOPE, BOOT, true, true, false));
        assertEquals(DROPPED_PERMISSION,
                status(pending, SCOPE, BOOT, true, false, true));
        assertEquals(DROPPED_UNKNOWN,
                status(pending, SCOPE, BOOT, true, true, true));
        UsageSnapshot drifted = sample(pending.dueWallMillis + 3_000, pending.dueWallMillis,
                activity(RESUME, START, "a"));
        assertEquals(CANCELED_CLOCK, snooze(drifted, pending, pending.dueElapsedMillis).snoozeStatus);
    }

    @Test public void replacingSnoozeUsesOnlyTheNewRequestDeadline() {
        PendingSnooze original = request();
        PendingSnooze replacement = new PendingSnooze("replacement", SCOPE, BOOT,
                original.scheduledWallMillis + MINUTE, original.scheduledElapsedMillis + MINUTE);
        Decision atOriginal = snooze(sample(original.dueWallMillis, null,
                activity(RESUME, START, "a")), replacement, original.dueElapsedMillis);
        assertEquals(PENDING, atOriginal.snoozeStatus);
        assertEquals("replacement", atOriginal.pending.id);
        Decision atReplacement = snooze(sample(replacement.dueWallMillis, replacement.dueWallMillis,
                activity(RESUME, START, "a")), replacement, replacement.dueElapsedMillis);
        assertEquals(FIRED, atReplacement.snoozeStatus);
        assertNull(atReplacement.pending);
    }

    private static UsageEvent activity(UsageEvent.Type type, long at, String name) {
        return new UsageEvent(type, at, name, "Main");
    }

    private static UsageSnapshot sample(long now, Long due, UsageEvent... tail) {
        List<UsageEvent> events = new ArrayList<>();
        events.add(new UsageEvent(SCREEN_NON_INTERACTIVE, WINDOW + 1, null, null));
        events.add(new UsageEvent(SCREEN_INTERACTIVE, WINDOW + 2, null, null));
        events.addAll(Arrays.asList(tail));
        return UsageTimeline.fold(events, WINDOW, now, UTC, PACKAGES, SCOPE, due);
    }

    private static PendingSnooze request() {
        return new PendingSnooze("request", SCOPE, BOOT, START + MINUTE, 10 * MINUTE);
    }

    private static Decision decide(UsageSnapshot sample, ReminderSettings settings, Set<String> keys) {
        return ReminderPolicy.evaluate(sample, settings, keys, null,
                SCOPE, MINUTE, BOOT, true, true, true);
    }

    private static Decision snooze(UsageSnapshot sample, PendingSnooze pending, long elapsed) {
        return ReminderPolicy.evaluate(sample, OFF, Collections.emptySet(), pending,
                SCOPE, elapsed, BOOT, true, true, true);
    }

    private static ReminderPolicy.SnoozeStatus status(PendingSnooze pending, long scope, long boot,
            boolean monitoring, boolean permission, boolean clock) {
        return ReminderPolicy.evaluate(null, OFF, Collections.emptySet(), pending,
                scope, pending.dueElapsedMillis, boot, monitoring, permission, clock).snoozeStatus;
    }

    private static List<Type> types(Decision decision) {
        List<Type> result = new ArrayList<>();
        for (ReminderPolicy.Reason reason : decision.reasons) result.add(reason.type);
        return result;
    }
}
