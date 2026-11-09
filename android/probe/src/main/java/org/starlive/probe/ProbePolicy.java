package org.starlive.probe;

/**
 * Short, deterministic notification policy for the capability probe only.
 *
 * <p>The production thresholds (60 / 60 / 180 minutes) are deliberately not
 * implemented here. This probe observes only the dedicated usage fixture.
 * State can be saved in the probe's own private preferences after each call.
 */
public final class ProbePolicy {
    public static final long THRESHOLD_MILLIS = 30_000L;
    public static final long SNOOZE_MILLIS = 30_000L;

    private String dayKey = "";
    private boolean thresholdSent;
    private long snoozeDueMillis;
    private long notificationCount;
    private long dedupedCount;

    /** Restores the policy's persisted state without generating a notification. */
    public void restore(String savedDayKey, boolean savedThresholdSent,
            long savedSnoozeDueMillis, long savedNotificationCount,
            long savedDedupedCount) {
        dayKey = normalizeDay(savedDayKey);
        thresholdSent = savedThresholdSent;
        snoozeDueMillis = Math.max(0L, savedSnoozeDueMillis);
        notificationCount = Math.max(0L, savedNotificationCount);
        dedupedCount = Math.max(0L, savedDedupedCount);
    }

    /**
     * Evaluates one poll. {@code permission} must reflect both usable usage data
     * and permission to show the notification; unavailable data is not zero use.
     *
     * <p>The daily threshold is consumed only when a notification decision is
     * generated. A due snooze is always consumed at its due time, even when the
     * fixture is no longer foreground or permission is no longer available.
     * Turning monitoring off cancels it immediately. A date change resets only
     * the daily threshold; an explicitly requested snooze keeps its due time.
     */
    public Decision evaluate(String currentDayKey, long nowMillis,
            long fixtureTodayMillis, boolean fixtureForeground,
            boolean monitoring, boolean permission) {
        String normalizedDay = normalizeDay(currentDayKey);
        if (!normalizedDay.equals(dayKey)) {
            dayKey = normalizedDay;
            thresholdSent = false;
        }

        if (!monitoring) {
            cancelSnooze();
            return Decision.NONE;
        }

        boolean snoozeIsDue = snoozeDueMillis > 0L && nowMillis >= snoozeDueMillis;
        if (snoozeIsDue) {
            snoozeDueMillis = 0L;
        }

        if (!permission) {
            return Decision.NONE;
        }

        boolean aboveThreshold = fixtureTodayMillis >= THRESHOLD_MILLIS;
        boolean thresholdTriggered = aboveThreshold && !thresholdSent;
        boolean snoozeTriggered = snoozeIsDue && fixtureForeground;

        if (aboveThreshold && thresholdSent) {
            dedupedCount = increment(dedupedCount);
        }
        if (thresholdTriggered && snoozeTriggered) {
            // Two reasons are represented by one notification decision.
            dedupedCount = increment(dedupedCount);
        }

        if (!thresholdTriggered && !snoozeTriggered) {
            return Decision.NONE;
        }
        if (thresholdTriggered) {
            thresholdSent = true;
        }
        notificationCount = increment(notificationCount);
        return new Decision(true, thresholdTriggered, snoozeTriggered);
    }

    /** Replaces any existing pending snooze instead of adding another timer. */
    public void scheduleSnooze(long nowMillis) {
        long safeNow = Math.max(0L, nowMillis);
        snoozeDueMillis = safeNow > Long.MAX_VALUE - SNOOZE_MILLIS
                ? Long.MAX_VALUE : safeNow + SNOOZE_MILLIS;
    }

    public void cancelSnooze() {
        snoozeDueMillis = 0L;
    }

    public String getDayKey() {
        return dayKey;
    }

    public boolean isThresholdSent() {
        return thresholdSent;
    }

    public long getSnoozeDueMillis() {
        return snoozeDueMillis;
    }

    /** Number of policy notification decisions, not confirmed OS deliveries. */
    public long getNotificationCount() {
        return notificationCount;
    }

    /** Number of already-notified threshold causes or merged extra causes. */
    public long getDedupedCount() {
        return dedupedCount;
    }

    private static String normalizeDay(String value) {
        return value == null ? "" : value;
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    public static final class Decision {
        private static final Decision NONE = new Decision(false, false, false);

        public final boolean shouldNotify;
        public final boolean thresholdTriggered;
        public final boolean snoozeTriggered;

        private Decision(boolean shouldNotify, boolean thresholdTriggered,
                boolean snoozeTriggered) {
            this.shouldNotify = shouldNotify;
            this.thresholdTriggered = thresholdTriggered;
            this.snoozeTriggered = snoozeTriggered;
        }
    }
}
