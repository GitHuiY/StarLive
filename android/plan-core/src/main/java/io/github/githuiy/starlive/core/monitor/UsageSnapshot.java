package io.github.githuiy.starlive.core.monitor;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Observed usage only. A partial day exposes lower bounds, not invented time. */
public final class UsageSnapshot {
    public final long observedAtMillis;
    public final Long dueWallMillis;
    public final LocalDate today;
    public final Map<String, Long> todayByApp;
    public final long todayTotalMillis;
    public final boolean todayComplete;
    public final Long sessionStartMillis;
    public final long sessionUsedMillis;
    public final Set<String> activePackages;
    public final UsageEvidence currentEvidence;
    public final UsageEvidence dueEvidence;

    UsageSnapshot(long observedAtMillis, Long dueWallMillis, LocalDate today,
            Map<String, Long> todayByApp, long todayTotalMillis,
            boolean todayComplete, Long sessionStartMillis, long sessionUsedMillis,
            Set<String> activePackages, UsageEvidence currentEvidence, UsageEvidence dueEvidence) {
        this.observedAtMillis = observedAtMillis;
        this.dueWallMillis = dueWallMillis;
        this.today = today;
        this.todayByApp = Collections.unmodifiableMap(new LinkedHashMap<>(todayByApp));
        this.todayTotalMillis = todayTotalMillis;
        this.todayComplete = todayComplete;
        this.sessionStartMillis = sessionStartMillis;
        this.sessionUsedMillis = sessionUsedMillis;
        this.activePackages = Collections.unmodifiableSet(new LinkedHashSet<>(activePackages));
        this.currentEvidence = currentEvidence;
        this.dueEvidence = dueEvidence;
    }
}
