package io.github.githuiy.starlive.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Complete weeks Sparks can schedule, starting with the current week. */
public final class PlanningHorizon {
    private PlanningHorizon() {}

    public static LocalDate firstMonday(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static LocalDate lastDate(LocalDate today) {
        return today.plusMonths(3).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }

    public static LocalDate lastMonday(LocalDate today) {
        return firstMonday(lastDate(today));
    }

    public static boolean includes(LocalDate today, LocalDate date) {
        return !date.isBefore(firstMonday(today)) && !date.isAfter(lastDate(today));
    }
}
