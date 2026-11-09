package io.github.githuiy.starlive.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class WeekPlan {
    public final List<List<PlanItem>> days;

    public WeekPlan(List<List<PlanItem>> days) {
        if (days == null || days.size() != 7) throw new IllegalArgumentException("一周必须包含七天");
        List<List<PlanItem>> copy = new ArrayList<>();
        for (List<PlanItem> day : days) {
            if (day == null) throw new IllegalArgumentException("日期事项不能为空");
            List<PlanItem> items = new ArrayList<>();
            for (PlanItem item : day) items.add(Objects.requireNonNull(item, "item"));
            copy.add(Collections.unmodifiableList(items));
        }
        this.days = Collections.unmodifiableList(copy);
    }

    public static WeekPlan empty() {
        List<List<PlanItem>> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) days.add(new ArrayList<>());
        return new WeekPlan(days);
    }

    public List<List<PlanItem>> mutableDays() {
        List<List<PlanItem>> copy = new ArrayList<>();
        for (List<PlanItem> day : days) copy.add(new ArrayList<>(day));
        return copy;
    }
}
