package io.github.githuiy.starlive.core.monitor;

import io.github.githuiy.starlive.core.ReminderSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One decision for a usage sample. The caller owns durable state and notification delivery. */
public final class ReminderPolicy {
    private static final long MINUTE_MILLIS = 60_000L;
    private static final long CLOCK_TOLERANCE_MILLIS = 2_000L;

    public enum Type { CONTINUOUS, PER_APP_DAILY, TOTAL_DAILY, SNOOZE }

    public enum SnoozeStatus {
        NONE, PENDING, FIRED, CANCELED_MONITORING, CANCELED_BOOT, CANCELED_SCOPE,
        CANCELED_CLOCK, DROPPED_INACTIVE, DROPPED_UNKNOWN, DROPPED_PERMISSION
    }

    public static final class Reason {
        public final Type type;
        public final String packageName;

        private Reason(Type type, String packageName) {
            this.type = type;
            this.packageName = packageName;
        }
    }

    public static final class PendingSnooze {
        public final String id;
        public final long scopeStartMillis;
        public final long bootCount;
        public final long scheduledWallMillis;
        public final long scheduledElapsedMillis;
        public final long dueWallMillis;
        public final long dueElapsedMillis;

        public PendingSnooze(String id, long scopeStartMillis, long bootCount,
                long scheduledWallMillis, long scheduledElapsedMillis) {
            if (id == null || id.isEmpty() || scopeStartMillis < 0 || bootCount < 0
                    || scheduledWallMillis < 0 || scheduledElapsedMillis < 0) {
                throw new IllegalArgumentException("Invalid snooze request");
            }
            this.id = id;
            this.scopeStartMillis = scopeStartMillis;
            this.bootCount = bootCount;
            this.scheduledWallMillis = scheduledWallMillis;
            this.scheduledElapsedMillis = scheduledElapsedMillis;
            long delay = ReminderSettings.SNOOZE_MINUTES * MINUTE_MILLIS;
            this.dueWallMillis = Math.addExact(scheduledWallMillis, delay);
            this.dueElapsedMillis = Math.addExact(scheduledElapsedMillis, delay);
        }

        public boolean wallClockConsistent(long wallMillis, long elapsedMillis) {
            if (wallMillis < scheduledWallMillis || elapsedMillis < scheduledElapsedMillis) {
                return false;
            }
            long wallDelta = wallMillis - scheduledWallMillis;
            long elapsedDelta = elapsedMillis - scheduledElapsedMillis;
            return Math.abs(wallDelta - elapsedDelta) <= CLOCK_TOLERANCE_MILLIS;
        }
    }

    public static final class Decision {
        public final List<Reason> reasons;
        public final Set<String> newKeys;
        public final PendingSnooze pending;
        public final SnoozeStatus snoozeStatus;
        public final Long snoozeDelayMillis;

        private Decision(List<Reason> reasons, Set<String> newKeys, PendingSnooze pending,
                SnoozeStatus snoozeStatus, Long snoozeDelayMillis) {
            this.reasons = Collections.unmodifiableList(reasons);
            this.newKeys = Collections.unmodifiableSet(newKeys);
            this.pending = pending;
            this.snoozeStatus = snoozeStatus;
            this.snoozeDelayMillis = snoozeDelayMillis;
        }
    }

    private ReminderPolicy() {}

    /**
     * Use elapsedRealtime sampled with the snapshot's wall clock. Persist new keys and the
     * updated pending request atomically with respect to later evaluations before dispatch.
     */
    public static Decision evaluate(UsageSnapshot snapshot, ReminderSettings settings,
            Set<String> sentKeys, PendingSnooze pending, long scopeStartMillis,
            long nowElapsedMillis, long bootCount, boolean monitoring,
            boolean notificationsAllowed, boolean wallClockConsistent) {
        List<Reason> reasons = new ArrayList<>();
        Set<String> newKeys = new LinkedHashSet<>();
        if (monitoring && notificationsAllowed && wallClockConsistent && snapshot != null
                && snapshot.currentEvidence == UsageEvidence.ACTIVE) {
            collectThresholds(snapshot, settings, sentKeys, scopeStartMillis, reasons, newKeys);
        }
        SnoozeStatus status = snoozeStatus(snapshot, pending, scopeStartMillis, nowElapsedMillis,
                bootCount, monitoring, notificationsAllowed, wallClockConsistent);
        if (status == SnoozeStatus.FIRED) reasons.add(new Reason(Type.SNOOZE, null));
        Long delay = pending != null && isDueOutcome(status)
                ? nowElapsedMillis - pending.dueElapsedMillis : null;
        return new Decision(reasons, newKeys, status == SnoozeStatus.PENDING ? pending : null,
                status, delay);
    }

    private static void collectThresholds(UsageSnapshot snapshot, ReminderSettings settings,
            Set<String> sentKeys, long scopeStartMillis, List<Reason> reasons,
            Set<String> newKeys) {
        if (settings.continuousEnabled && snapshot.sessionStartMillis != null
                && snapshot.sessionUsedMillis >= settings.continuousMinutes * MINUTE_MILLIS) {
            String key = "continuous:" + scopeStartMillis + ":" + snapshot.sessionStartMillis;
            addReason(key, Type.CONTINUOUS, null, sentKeys, reasons, newKeys);
        }
        if (settings.perAppDailyEnabled) {
            for (String name : snapshot.activePackages) {
                Long used = snapshot.todayByApp.get(name);
                if (used != null && used >= settings.perAppDailyMinutes * MINUTE_MILLIS) {
                    String key = "perAppDaily:" + snapshot.today + ":" + name;
                    addReason(key, Type.PER_APP_DAILY, name, sentKeys, reasons, newKeys);
                }
            }
        }
        if (settings.totalDailyEnabled
                && snapshot.todayTotalMillis >= settings.totalDailyMinutes * MINUTE_MILLIS) {
            String key = "totalDaily:" + snapshot.today;
            addReason(key, Type.TOTAL_DAILY, null, sentKeys, reasons, newKeys);
        }
    }

    private static void addReason(String key, Type type, String packageName, Set<String> sentKeys,
            List<Reason> reasons, Set<String> newKeys) {
        if (!sentKeys.contains(key)) {
            newKeys.add(key);
            reasons.add(new Reason(type, packageName));
        }
    }

    private static SnoozeStatus snoozeStatus(UsageSnapshot snapshot, PendingSnooze pending,
            long scopeStartMillis, long nowElapsedMillis, long bootCount, boolean monitoring,
            boolean notificationsAllowed, boolean wallClockConsistent) {
        if (pending == null) return SnoozeStatus.NONE;
        if (!monitoring) return SnoozeStatus.CANCELED_MONITORING;
        if (bootCount != pending.bootCount || nowElapsedMillis < pending.scheduledElapsedMillis) {
            return SnoozeStatus.CANCELED_BOOT;
        }
        if (scopeStartMillis != pending.scopeStartMillis) return SnoozeStatus.CANCELED_SCOPE;
        if (!wallClockConsistent || snapshot != null
                && !pending.wallClockConsistent(snapshot.observedAtMillis, nowElapsedMillis)) {
            return SnoozeStatus.CANCELED_CLOCK;
        }
        if (nowElapsedMillis < pending.dueElapsedMillis) return SnoozeStatus.PENDING;
        if (snapshot != null && snapshot.observedAtMillis < pending.dueWallMillis) {
            // A tiny difference between clock samples can put the due wall point in the future.
            return SnoozeStatus.PENDING;
        }
        if (!notificationsAllowed) return SnoozeStatus.DROPPED_PERMISSION;
        if (snapshot == null || !Objects.equals(snapshot.dueWallMillis, pending.dueWallMillis)) {
            return SnoozeStatus.DROPPED_UNKNOWN;
        }
        if (snapshot.dueEvidence == UsageEvidence.INACTIVE
                || snapshot.currentEvidence == UsageEvidence.INACTIVE) {
            return SnoozeStatus.DROPPED_INACTIVE;
        }
        if (snapshot.dueEvidence != UsageEvidence.ACTIVE
                || snapshot.currentEvidence != UsageEvidence.ACTIVE) {
            return SnoozeStatus.DROPPED_UNKNOWN;
        }
        return SnoozeStatus.FIRED;
    }

    private static boolean isDueOutcome(SnoozeStatus status) {
        switch (status) {
            case FIRED:
            case DROPPED_INACTIVE:
            case DROPPED_UNKNOWN:
            case DROPPED_PERMISSION:
                return true;
            default:
                return false;
        }
    }
}
