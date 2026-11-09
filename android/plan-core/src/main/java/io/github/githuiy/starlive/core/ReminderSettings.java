package io.github.githuiy.starlive.core;

/** Stored preferences only. This module never starts monitoring or sends notifications. */
public final class ReminderSettings {
    public static final int SNOOZE_MINUTES = 30;
    public static final int GRACE_SECONDS = 120;
    public final int continuousMinutes;
    public final int perAppDailyMinutes;
    public final int totalDailyMinutes;
    public final boolean continuousEnabled;
    public final boolean perAppDailyEnabled;
    public final boolean totalDailyEnabled;
    public final int snoozeMinutes = SNOOZE_MINUTES;
    public final int graceSeconds = GRACE_SECONDS;

    public ReminderSettings(int continuousMinutes, int perAppDailyMinutes,
            int totalDailyMinutes, boolean continuousEnabled,
            boolean perAppDailyEnabled, boolean totalDailyEnabled) {
        if (continuousMinutes < 1 || continuousMinutes > 1440
                || perAppDailyMinutes < 1 || perAppDailyMinutes > 1440
                || totalDailyMinutes < 1 || totalDailyMinutes > 1440) {
            throw new IllegalArgumentException("提醒分钟数必须在 1 至 1440 之间");
        }
        this.continuousMinutes = continuousMinutes;
        this.perAppDailyMinutes = perAppDailyMinutes;
        this.totalDailyMinutes = totalDailyMinutes;
        this.continuousEnabled = continuousEnabled;
        this.perAppDailyEnabled = perAppDailyEnabled;
        this.totalDailyEnabled = totalDailyEnabled;
    }

    public static ReminderSettings defaults() {
        return new ReminderSettings(60, 60, 180, true, true, true);
    }
}
