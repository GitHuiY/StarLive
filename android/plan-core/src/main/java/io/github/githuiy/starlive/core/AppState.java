package io.github.githuiy.starlive.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Personal plans only; demo and system usage records are deliberately absent. */
public final class AppState {
    public final Map<String, WeekPlan> weeks;
    public final Map<String, WeekPlan> drafts;
    public final ReminderSettings settings;

    public AppState(Map<String, WeekPlan> weeks, Map<String, WeekPlan> drafts,
            ReminderSettings settings) {
        this.weeks = immutable(weeks);
        this.drafts = immutable(drafts);
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    private static Map<String, WeekPlan> immutable(Map<String, WeekPlan> source) {
        Map<String, WeekPlan> copy = new LinkedHashMap<>();
        for (Map.Entry<String, WeekPlan> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "week key"),
                    Objects.requireNonNull(entry.getValue(), "week"));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static AppState empty() {
        return new AppState(Collections.emptyMap(), Collections.emptyMap(), ReminderSettings.defaults());
    }
}
