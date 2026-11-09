package io.github.githuiy.starlive.core.monitor;

import io.github.githuiy.starlive.core.ReminderSettings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reconstructs resumed activity intervals, with screen and keyguard transitions as cutoffs. */
public final class UsageTimeline {
    private static final long GRACE_MILLIS = ReminderSettings.GRACE_SECONDS * 1_000L;

    private UsageTimeline() {}

    public static UsageSnapshot fold(List<UsageEvent> events, long windowStart, long now,
            ZoneId zone, Set<String> selected, long continuousNotBefore, Long dueWallMillis) {
        return foldWithLiveState(events, windowStart, now, zone, selected, continuousNotBefore,
                dueWallMillis, null);
    }

    /**
     * The adapter can veto an open interval using the current screen/keyguard state. A live
     * observation establishes no historical cutoff, so a contradicted open interval is omitted.
     */
    public static UsageSnapshot fold(List<UsageEvent> events, long windowStart, long now,
            ZoneId zone, Set<String> selected, long continuousNotBefore, Long dueWallMillis,
            boolean currentUseAllowed) {
        return foldWithLiveState(events, windowStart, now, zone, selected, continuousNotBefore,
                dueWallMillis, currentUseAllowed);
    }

    private static UsageSnapshot foldWithLiveState(List<UsageEvent> events, long windowStart,
            long now, ZoneId zone, Set<String> selected, long continuousNotBefore,
            Long dueWallMillis, Boolean currentUseAllowed) {
        if (windowStart < 0 || windowStart > now || continuousNotBefore < 0) {
            throw new IllegalArgumentException("Invalid usage observation window");
        }
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli();
        Scan scan = new Scan(windowStart, selected);
        long lastTime = windowStart;
        for (UsageEvent event : events) {
            long time = event.timeMillis;
            if (time < windowStart || time > now) continue;
            if (time < lastTime) {
                // Ordering can be ambiguous after a wall clock change. Do not sort it away.
                return new UsageSnapshot(now, dueWallMillis, today, emptyTotals(selected), 0,
                        false, null, 0, new LinkedHashSet<>(),
                        UsageEvidence.UNKNOWN, UsageEvidence.UNKNOWN);
            }
            scan.accept(event);
            lastTime = time;
        }
        scan.finish(now, currentUseAllowed);

        Map<String, Long> byApp = emptyTotals(selected);
        List<Span> union = new ArrayList<>();
        for (Map.Entry<String, List<Span>> entry : scan.byPackage.entrySet()) {
            List<Span> spans = merge(entry.getValue());
            byApp.put(entry.getKey(), sumAfter(spans, dayStart));
            union.addAll(spans);
        }
        union = merge(union);
        boolean complete = selected.isEmpty() || scan.firstBoundary != null
                && scan.firstBoundary <= dayStart && !scan.hasProblem(dayStart, now);
        Session session = session(union, scan, now, continuousNotBefore);
        UsageEvidence current = scan.evidenceAt(now, now, currentUseAllowed);
        UsageEvidence due = dueWallMillis == null ? UsageEvidence.UNKNOWN
                : scan.evidenceAt(dueWallMillis, now, currentUseAllowed);
        return new UsageSnapshot(now, dueWallMillis, today, byApp, sumAfter(union, dayStart), complete,
                session.start, session.used, scan.activePackages, current, due);
    }

    private static Map<String, Long> emptyTotals(Set<String> selected) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String name : selected) totals.put(name, 0L);
        return totals;
    }

    private static List<Span> merge(List<Span> spans) {
        spans.sort(Comparator.comparingLong(span -> span.start));
        List<Span> merged = new ArrayList<>();
        for (Span span : spans) {
            int last = merged.size() - 1;
            if (last < 0 || span.start > merged.get(last).end) {
                merged.add(span);
            } else if (span.end > merged.get(last).end) {
                merged.set(last, new Span(merged.get(last).start, span.end));
            }
        }
        return merged;
    }

    private static long sumAfter(List<Span> spans, long start) {
        long total = 0;
        for (Span span : spans) total += Math.max(0, span.end - Math.max(span.start, start));
        return total;
    }

    private static Session session(List<Span> union, Scan scan, long now, long cut) {
        Long start = null;
        long end = -1;
        long used = 0;
        for (Span span : union) {
            if (span.end < cut || span.end == cut && span.start < cut) continue;
            long from = Math.max(span.start, cut);
            if (end < 0 || from - end > GRACE_MILLIS) {
                boolean hasKnownStart = end >= 0 || cut >= scan.windowStart
                        || scan.firstBoundary != null && from - scan.firstBoundary > GRACE_MILLIS;
                long evidenceStart = Math.max(cut, from - GRACE_MILLIS);
                start = hasKnownStart && (evidenceStart == from
                        || !scan.hasProblem(evidenceStart, from)) ? from : null;
                used = 0;
            }
            used += span.end - from;
            end = span.end;
        }
        if (end < 0 || now - end > GRACE_MILLIS) return new Session(null, 0);
        return new Session(start, used);
    }

    private static final class Scan {
        final long windowStart;
        final Set<String> selected;
        final Map<Activity, Long> open = new LinkedHashMap<>();
        final Set<Activity> seen = new LinkedHashSet<>();
        final Map<String, List<Span>> byPackage = new LinkedHashMap<>();
        final Set<String> activePackages = new LinkedHashSet<>();
        final List<Span> gaps = new ArrayList<>();
        final List<Span> blocks = new ArrayList<>();
        Long firstBoundary;
        long lastBoundary;
        Long blockedSince;
        Long shutdownSince;
        boolean liveBlockConflict;
        boolean screenOff;
        boolean locked;

        Scan(long windowStart, Set<String> selected) {
            this.windowStart = windowStart;
            this.lastBoundary = windowStart;
            this.selected = selected;
        }

        void accept(UsageEvent event) {
            long time = event.timeMillis;
            switch (event.type) {
                case SCREEN_NON_INTERACTIVE:
                    screenOff = true;
                    boundary(time, true);
                    break;
                case KEYGUARD_SHOWN:
                    locked = true;
                    boundary(time, true);
                    break;
                case DEVICE_SHUTDOWN:
                    if (shutdownSince == null) shutdownSince = time;
                    boundary(time, false);
                    break;
                case DEVICE_STARTUP:
                    if (shutdownSince != null) {
                        gaps.add(new Span(shutdownSince, time));
                        shutdownSince = null;
                    }
                    screenOff = false;
                    locked = false;
                    boundary(time, false);
                    seen.clear();
                    break;
                case SCREEN_INTERACTIVE:
                    screenOff = false;
                    break;
                case KEYGUARD_HIDDEN:
                    locked = false;
                    break;
                case RESUME:
                case PAUSE:
                case STOP:
                    lifecycle(event);
                    break;
            }
            if (screenBlocked()) {
                if (blockedSince == null) blockedSince = time;
            } else if (blockedSince != null) {
                blocks.add(new Span(blockedSince, time));
                blockedSince = null;
            }
        }

        private void boundary(long time, boolean exact) {
            for (Map.Entry<Activity, Long> entry : open.entrySet()) {
                if (exact) {
                    add(entry.getKey().packageName, entry.getValue(), time);
                } else {
                    // Android shutdown is the last persistence time, not a precise cutoff.
                    gaps.add(new Span(entry.getValue(), time));
                }
            }
            open.clear();
            if (firstBoundary == null) firstBoundary = time;
            lastBoundary = time;
        }

        private void lifecycle(UsageEvent event) {
            if (event.packageName == null || !selected.contains(event.packageName)) return;
            Activity activity = new Activity(event.packageName, event.className);
            long time = event.timeMillis;
            if (event.type == UsageEvent.Type.RESUME) {
                seen.add(activity);
                if (!blocked()) {
                    Long previous = open.put(activity, time);
                    if (previous != null && previous != time) {
                        // Public events do not identify separate instances of the same class.
                        gaps.add(new Span(previous, time));
                    }
                }
                return;
            }
            Long started = open.remove(activity);
            if (started != null) {
                if (event.type == UsageEvent.Type.PAUSE) {
                    add(event.packageName, started, time);
                } else {
                    // STOP does not establish the time when the activity stopped being resumed.
                    gaps.add(new Span(started, time));
                }
            } else if (!blocked()) {
                boolean discarded = discardAmbiguousOpen(activity, time);
                if (!discarded && !seen.contains(activity)) {
                    // Duplicate closes and closes after lock do not imply a missing start.
                    gaps.add(new Span(lastBoundary, time));
                }
            }
            seen.add(activity);
        }

        private boolean discardAmbiguousOpen(Activity ended, long time) {
            // Missing class identity cannot leave a possibly closed interval running.
            return open.entrySet().removeIf(entry -> {
                Activity active = entry.getKey();
                if (!active.packageName.equals(ended.packageName)
                        || active.className != null && ended.className != null) {
                    return false;
                }
                gaps.add(new Span(entry.getValue(), time));
                return true;
            });
        }

        private boolean blocked() {
            return screenBlocked() || shutdownSince != null;
        }

        private boolean screenBlocked() {
            return shutdownSince == null && (screenOff || locked);
        }

        private void add(String name, long start, long end) {
            byPackage.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new Span(start, end));
        }

        void finish(long now, Boolean currentUseAllowed) {
            liveBlockConflict = blockedSince != null && Boolean.TRUE.equals(currentUseAllowed);
            if (liveBlockConflict) gaps.add(new Span(blockedSince, now));
            if (shutdownSince != null) gaps.add(new Span(shutdownSince, now));
            for (Map.Entry<Activity, Long> entry : open.entrySet()) {
                String name = entry.getKey().packageName;
                if (!Boolean.FALSE.equals(currentUseAllowed)) {
                    add(name, entry.getValue(), now);
                    activePackages.add(name);
                } else {
                    gaps.add(new Span(entry.getValue(), now));
                }
            }
        }

        boolean hasProblem(long start, long end) {
            for (Span gap : gaps) {
                if (gap.start < end && gap.end > start) return true;
            }
            return false;
        }

        UsageEvidence evidenceAt(long time, long now, Boolean currentUseAllowed) {
            if (time < windowStart || time > now) return UsageEvidence.UNKNOWN;
            if (time == now) {
                if (Boolean.FALSE.equals(currentUseAllowed)) return UsageEvidence.INACTIVE;
                if (liveBlockConflict || shutdownSince != null) return UsageEvidence.UNKNOWN;
                if (blocked()) return UsageEvidence.INACTIVE;
                if (!activePackages.isEmpty()) return UsageEvidence.ACTIVE;
            }
            for (List<Span> spans : byPackage.values()) {
                for (Span span : spans) {
                    if (span.contains(time)) return UsageEvidence.ACTIVE;
                }
            }
            if (blockedSince != null && time >= blockedSince) {
                return liveBlockConflict && time > blockedSince
                        ? UsageEvidence.UNKNOWN : UsageEvidence.INACTIVE;
            }
            for (Span block : blocks) {
                if (block.contains(time)) return UsageEvidence.INACTIVE;
            }
            for (Span gap : gaps) {
                if (gap.contains(time)) return UsageEvidence.UNKNOWN;
            }
            return (selected.isEmpty() || firstBoundary != null && time >= firstBoundary)
                    ? UsageEvidence.INACTIVE : UsageEvidence.UNKNOWN;
        }
    }

    private static final class Span {
        final long start;
        final long end;

        Span(long start, long end) {
            this.start = start;
            this.end = end;
        }

        boolean contains(long time) {
            return start <= time && time < end;
        }
    }

    private static final class Session {
        final Long start;
        final long used;

        Session(Long start, long used) {
            this.start = start;
            this.used = used;
        }
    }

    private static final class Activity {
        final String packageName;
        final String className;

        Activity(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Activity)) return false;
            Activity activity = (Activity) other;
            return packageName.equals(activity.packageName) && Objects.equals(className, activity.className);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, className);
        }
    }
}
