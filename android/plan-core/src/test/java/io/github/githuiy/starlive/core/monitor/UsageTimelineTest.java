package io.github.githuiy.starlive.core.monitor;

import static io.github.githuiy.starlive.core.monitor.UsageEvent.Type.*;
import static org.junit.Assert.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class UsageTimelineTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long DAY = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli();
    private static final long WINDOW = DAY - 86_400_000;
    private static final long START = DAY + 600_000;
    private static final Set<String> SELECTED = new LinkedHashSet<>(Arrays.asList("a", "b"));

    @Test public void selectedSwitchContinuesWhileIndividualTotalsStaySeparate() {
        long switchAt = START + 35 * 60_000;
        UsageSnapshot snapshot = fold(switchAt + 25 * 60_000, null,
                activity(RESUME, START, "a", "Main"),
                activity(PAUSE, switchAt, "a", "Main"),
                activity(RESUME, switchAt, "b", "Main"));
        assertEquals(Long.valueOf(START), snapshot.sessionStartMillis);
        assertEquals(60 * 60_000, snapshot.sessionUsedMillis);
        assertEquals(60 * 60_000, snapshot.todayTotalMillis);
        assertEquals(Long.valueOf(35 * 60_000), snapshot.todayByApp.get("a"));
        assertEquals(Long.valueOf(25 * 60_000), snapshot.todayByApp.get("b"));
        assertEquals(Collections.singleton("b"), snapshot.activePackages);
    }

    @Test public void graceIncludesExactly120SecondsButNeverCountsGap() {
        for (long gap : new long[] {119_999, 120_000, 120_001}) {
            long pausedAt = START + 10_000;
            long resumedAt = pausedAt + gap;
            UsageSnapshot snapshot = fold(resumedAt + 20_000, pausedAt,
                    activity(RESUME, START, "a", "Main"),
                    activity(PAUSE, pausedAt, "a", "Main"),
                    activity(RESUME, pausedAt + 1, "unselected", "Main"),
                    activity(RESUME, resumedAt, "b", "Main"));
            assertEquals(Long.valueOf(gap <= 120_000 ? START : resumedAt),
                    snapshot.sessionStartMillis);
            assertEquals(gap <= 120_000 ? 30_000 : 20_000, snapshot.sessionUsedMillis);
            assertEquals(30_000, snapshot.todayTotalMillis);
            assertEquals(UsageEvidence.INACTIVE, snapshot.dueEvidence);
        }
    }

    @Test public void overlappingActivitiesAndPackagesUseUnions() {
        UsageSnapshot snapshot = fold(START + 50, START + 20,
                activity(RESUME, START, "a", "First"),
                activity(RESUME, START + 10, "a", "Second"),
                activity(RESUME, START + 15, "b", "Main"),
                activity(PAUSE, START + 20, "a", "First"),
                activity(PAUSE, START + 30, "a", "Second"),
                activity(PAUSE, START + 40, "b", "Main"));
        assertEquals(Long.valueOf(30), snapshot.todayByApp.get("a"));
        assertEquals(Long.valueOf(25), snapshot.todayByApp.get("b"));
        assertEquals(40, snapshot.todayTotalMillis);
        assertEquals(40, snapshot.sessionUsedMillis);
        assertEquals(UsageEvidence.ACTIVE, snapshot.dueEvidence);
        assertTrue(snapshot.activePackages.isEmpty());
    }

    @Test public void duplicateClosesAfterSwitchAndLockDoNotCorruptDayQuality() {
        UsageSnapshot snapshot = fold(START + 100, START + 60,
                activity(RESUME, START, "a", "Main"),
                activity(RESUME, START, "a", "Main"),
                activity(RESUME, START + 10, "unselected", "Main"),
                activity(PAUSE, START + 10, "a", "Main"),
                activity(PAUSE, START + 11, "a", "Main"),
                activity(STOP, START + 12, "a", "Main"),
                activity(RESUME, START + 20, "b", "Main"),
                system(KEYGUARD_SHOWN, START + 30),
                activity(PAUSE, START + 40, "b", "Main"),
                activity(STOP, START + 50, "b", "Main"),
                system(KEYGUARD_HIDDEN, START + 70),
                activity(RESUME, START + 70, "a", "Main"));
        assertTrue(snapshot.todayComplete);
        assertEquals(50, snapshot.sessionUsedMillis);
        assertEquals(UsageEvidence.INACTIVE, snapshot.dueEvidence);
        assertEquals(UsageEvidence.ACTIVE, snapshot.currentEvidence);
    }

    @Test public void historicalOrphanOnlyMarksTheDayItTouches() {
        UsageSnapshot snapshot = UsageTimeline.fold(Arrays.asList(
                activity(PAUSE, DAY - 5, "a", "Unknown"),
                system(SCREEN_NON_INTERACTIVE, DAY - 4),
                system(SCREEN_INTERACTIVE, DAY - 3),
                activity(RESUME, START, "a", "Main")), WINDOW, START + 60, UTC,
                SELECTED, DAY, DAY - 6);
        assertTrue(snapshot.todayComplete);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.dueEvidence);
        assertEquals(60, snapshot.todayTotalMillis);

        UsageSnapshot orphanToday = fold(START + 60, START + 30,
                activity(PAUSE, START + 50, "a", "Unknown"));
        assertFalse(orphanToday.todayComplete);
        assertEquals(0, orphanToday.todayTotalMillis);
        assertEquals(UsageEvidence.UNKNOWN, orphanToday.dueEvidence);
    }

    @Test public void stoppedWithoutPauseAndAmbiguousRepeatedResumeAreNotCounted() {
        UsageSnapshot stopped = fold(START + 100, START + 10,
                activity(RESUME, START, "a", "Main"),
                activity(STOP, START + 50, "a", "Main"));
        assertEquals(0, stopped.todayTotalMillis);
        assertFalse(stopped.todayComplete);
        assertEquals(UsageEvidence.UNKNOWN, stopped.dueEvidence);

        UsageSnapshot repeated = fold(START + 100, START + 10,
                activity(RESUME, START, "a", "Main"),
                activity(RESUME, START + 50, "a", "Main"));
        assertEquals(50, repeated.todayTotalMillis);
        assertFalse(repeated.todayComplete);
        assertNull(repeated.sessionStartMillis);
        assertEquals(UsageEvidence.UNKNOWN, repeated.dueEvidence);
    }

    @Test public void missingClassOnEndCannotLeavePossibleActivityRunning() {
        UsageSnapshot snapshot = fold(START + 80, START + 20,
                activity(RESUME, START - 20, "a", null),
                activity(PAUSE, START - 20, "a", null),
                activity(RESUME, START, "a", "Main"),
                activity(PAUSE, START + 50, "a", null));
        assertTrue(snapshot.activePackages.isEmpty());
        assertFalse(snapshot.todayComplete);
        assertEquals(0, snapshot.todayTotalMillis);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.dueEvidence);
    }

    @Test public void boundedHistoryKeepsTruncatedSessionIdUnknown() {
        UsageSnapshot snapshot = UsageTimeline.fold(Collections.singletonList(
                activity(RESUME, START, "a", "Main")), DAY, START + 60, UTC,
                SELECTED, DAY - 1, DAY);
        assertFalse(snapshot.todayComplete);
        assertNull(snapshot.sessionStartMillis);
        assertEquals(60, snapshot.sessionUsedMillis);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.dueEvidence);
    }

    @Test public void noEventsAreUnknownInsteadOfConfirmedIdle() {
        UsageSnapshot snapshot = UsageTimeline.fold(Collections.emptyList(), WINDOW, START,
                UTC, SELECTED, DAY, START - 1);
        assertFalse(snapshot.todayComplete);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.currentEvidence);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.dueEvidence);
    }

    @Test public void localMidnightCutsDailyTimeButPreservesSessionAcrossDst() {
        ZoneId zone = ZoneId.of("America/New_York");
        long dayStart = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli();
        long midnight = Instant.parse("2026-03-09T04:00:00Z").toEpochMilli();
        long started = dayStart - 30 * 60_000;
        List<UsageEvent> events = Arrays.asList(
                system(SCREEN_NON_INTERACTIVE, started - 2),
                system(SCREEN_INTERACTIVE, started - 1),
                activity(RESUME, started, "a", "Main"));
        UsageSnapshot before = UsageTimeline.fold(events, started - 5, midnight - 1,
                zone, SELECTED, started - 5, null);
        UsageSnapshot after = UsageTimeline.fold(events, started - 5, midnight + 30 * 60_000,
                zone, SELECTED, started - 5, null);
        assertEquals(LocalDate.of(2026, 3, 8), before.today);
        assertEquals(23 * 60 * 60_000 - 1, before.todayTotalMillis);
        assertEquals(LocalDate.of(2026, 3, 9), after.today);
        assertEquals(30 * 60_000, after.todayTotalMillis);
        assertEquals(24 * 60 * 60_000, after.sessionUsedMillis);
        assertEquals(before.sessionStartMillis, after.sessionStartMillis);
        assertTrue(after.todayComplete);
    }

    @Test public void changingScopeCutsSessionWithoutDiscardingDailyUsage() {
        UsageSnapshot snapshot = UsageTimeline.fold(Arrays.asList(
                activity(RESUME, START - 20, "a", "Main"),
                activity(PAUSE, START, "a", "Main"),
                activity(RESUME, START + 20, "b", "Main")),
                WINDOW, START + 40, UTC, SELECTED, START, null);
        assertEquals(40, snapshot.todayTotalMillis);
        assertEquals(20, snapshot.sessionUsedMillis);
        assertEquals(Long.valueOf(START + 20), snapshot.sessionStartMillis);
    }

    @Test public void liveScreenVetoDoesNotInventHistoricalCutoff() {
        UsageSnapshot snapshot = UsageTimeline.fold(Collections.singletonList(
                activity(RESUME, START, "a", "Main")), WINDOW, START + 60, UTC,
                SELECTED, DAY, START + 30, false);
        assertFalse(snapshot.todayComplete);
        assertEquals(0, snapshot.todayTotalMillis);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.dueEvidence);
        assertEquals(UsageEvidence.INACTIVE, snapshot.currentEvidence);
    }

    @Test public void liveInteractiveStateCannotProveWhenMissingUnlockHappened() {
        UsageSnapshot snapshot = UsageTimeline.fold(Arrays.asList(
                activity(RESUME, START, "a", "Main"),
                system(KEYGUARD_SHOWN, START + 10)), WINDOW, START + 60, UTC, SELECTED,
                DAY, START + 30, true);
        assertFalse(snapshot.todayComplete);
        assertEquals(10, snapshot.todayTotalMillis);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.currentEvidence);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.dueEvidence);
    }

    @Test public void shutdownEventIsNotAPreciseEndForAnUnpairedActivity() {
        UsageSnapshot snapshot = fold(START + 80, START + 30,
                activity(RESUME, START, "a", "Main"),
                system(DEVICE_SHUTDOWN, START + 20),
                system(DEVICE_STARTUP, START + 40),
                activity(RESUME, START + 50, "b", "Main"));
        assertEquals(Long.valueOf(0), snapshot.todayByApp.get("a"));
        assertEquals(Long.valueOf(30), snapshot.todayByApp.get("b"));
        assertFalse(snapshot.todayComplete);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.dueEvidence);
    }

    @Test public void backwardsEventOrderDoesNotAccumulateOrBecomeActive() {
        UsageSnapshot snapshot = fold(START + 100, START + 30,
                activity(RESUME, START + 20, "a", "Main"),
                activity(PAUSE, START + 10, "a", "Main"));
        assertEquals(0, snapshot.todayTotalMillis);
        assertFalse(snapshot.todayComplete);
        assertEquals(UsageEvidence.UNKNOWN, snapshot.currentEvidence);
    }

    private static UsageSnapshot fold(long now, Long due, UsageEvent... tail) {
        List<UsageEvent> events = new ArrayList<>();
        events.add(system(SCREEN_NON_INTERACTIVE, DAY - 2));
        events.add(system(SCREEN_INTERACTIVE, DAY - 1));
        events.addAll(Arrays.asList(tail));
        return UsageTimeline.fold(events, WINDOW, now, UTC, SELECTED, DAY, due);
    }

    private static UsageEvent activity(UsageEvent.Type type, long at, String name, String className) {
        return new UsageEvent(type, at, name, className);
    }

    private static UsageEvent system(UsageEvent.Type type, long at) {
        return activity(type, at, null, null);
    }
}
