package io.github.githuiy.starlive.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure, immutable plan operations. Construct with today's system-zone date. */
public final class PlanEngine {
    private final String thisWeek;
    private final String nextWeek;

    public PlanEngine(LocalDate today) {
        thisWeek = weekKey(today);
        nextWeek = weekKey(today.plusWeeks(1));
    }

    public static String weekKey(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }

    public static WeekPlan confirmed(AppState state, String week) {
        WeekPlan found = state.weeks.get(week);
        return found == null ? WeekPlan.empty() : found;
    }

    public static WeekPlan draft(AppState state, String week) {
        WeekPlan found = state.drafts.get(week);
        return found == null ? confirmed(state, week) : found;
    }

    public AppState addDraftItem(AppState state, String week, int day,
            String title, String category) {
        return addDraftItem(state, week, day, title, category, "");
    }

    public AppState addDraftItem(AppState state, String week, int day,
            String title, String category, String body) {
        editable(week, day);
        checkText(title, category, body);
        List<List<PlanItem>> days = draft(state, week).mutableDays();
        String trimmed = title.trim();
        if (!trimmed.isEmpty()) {
            for (PlanItem item : days.get(day)) {
                if (item.title.trim().equals(trimmed)) return state;
            }
        }
        days.get(day).add(new PlanItem(UUID.randomUUID().toString(), title, category, false, body));
        return withDraft(state, week, new WeekPlan(days));
    }

    public AppState updateDraftItem(AppState state, String week, int day, String id,
            String title, String category) {
        return updateDraftItem(state, week, day, id, title, category, null);
    }

    /** A null body preserves the saved text for callers that only edit the title and category. */
    public AppState updateDraftItem(AppState state, String week, int day, String id,
            String title, String category, String body) {
        editable(week, day);
        List<List<PlanItem>> days = draft(state, week).mutableDays();
        int index = indexOf(days.get(day), id);
        PlanItem old = days.get(day).get(index);
        String updatedBody = body == null ? old.body : body;
        checkText(title, category, updatedBody);
        days.get(day).set(index, new PlanItem(id, title, category,
                old.title.trim().equals(title.trim()) && old.done, updatedBody));
        return withDraft(state, week, new WeekPlan(days));
    }

    public AppState deleteDraftItem(AppState state, String week, int day, String id) {
        editable(week, day);
        List<List<PlanItem>> days = draft(state, week).mutableDays();
        days.get(day).remove(indexOf(days.get(day), id));
        return withDraft(state, week, new WeekPlan(days));
    }

    /** toIndex is the final index after removal, also for same-day reordering. */
    public AppState moveDraftItem(AppState state, String week, int fromDay,
            String id, int toDay, int toIndex) {
        editable(week, fromDay);
        checkDay(toDay);
        List<List<PlanItem>> days = draft(state, week).mutableDays();
        PlanItem old = days.get(fromDay).remove(indexOf(days.get(fromDay), id));
        List<PlanItem> target = days.get(toDay);
        if (toIndex < 0 || toIndex > target.size()) throw new IllegalArgumentException("排序位置无效");
        target.add(toIndex, new PlanItem(old.id, old.title, old.category,
                fromDay == toDay && old.done, old.body));
        return withDraft(state, week, new WeekPlan(days));
    }

    public AppState toggleCompletion(AppState state, String week, int day, String id) {
        editable(week, day);
        List<List<PlanItem>> days = confirmed(state, week).mutableDays();
        int index = indexOf(days.get(day), id);
        PlanItem item = days.get(day).get(index);
        days.get(day).set(index, new PlanItem(item.id, item.title, item.category, !item.done, item.body));
        Map<String, WeekPlan> weeks = new LinkedHashMap<>(state.weeks);
        weeks.put(week, new WeekPlan(days));
        // An open draft intentionally stays untouched; confirmation merges latest done.
        return new AppState(weeks, state.drafts, state.settings);
    }

    public AppState confirmDraft(AppState state, String week) {
        editable(week, 0);
        WeekPlan previous = confirmed(state, week);
        List<List<PlanItem>> confirmed = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            List<PlanItem> items = new ArrayList<>();
            for (PlanItem item : draft(state, week).days.get(day)) {
                String title = item.title.trim();
                if (title.isEmpty()) continue;
                boolean done = false;
                for (PlanItem old : previous.days.get(day)) {
                    if (old.id.equals(item.id) && old.title.trim().equals(title)) {
                        done = old.done;
                        break;
                    }
                }
                items.add(new PlanItem(item.id, title, item.category, done, item.body));
            }
            confirmed.add(items);
        }
        WeekPlan committed = new WeekPlan(confirmed);
        Map<String, WeekPlan> weeks = new LinkedHashMap<>(state.weeks);
        Map<String, WeekPlan> drafts = new LinkedHashMap<>(state.drafts);
        weeks.put(week, committed);
        drafts.put(week, committed);
        return new AppState(weeks, drafts, state.settings);
    }

    public AppState updateSettings(AppState state, ReminderSettings settings) {
        return new AppState(state.weeks, state.drafts, settings);
    }

    private AppState withDraft(AppState state, String week, WeekPlan plan) {
        Map<String, WeekPlan> drafts = new LinkedHashMap<>(state.drafts);
        drafts.put(week, plan);
        return new AppState(state.weeks, drafts, state.settings);
    }

    private void editable(String week, int day) {
        checkDay(day);
        if (!thisWeek.equals(week) && !nextWeek.equals(week)) {
            throw new IllegalArgumentException("首版仅可编辑本周和下周，历史记录保持原样");
        }
    }

    private static int indexOf(List<PlanItem> day, String id) {
        for (int i = 0; i < day.size(); i++) if (day.get(i).id.equals(id)) return i;
        throw new IllegalArgumentException("事项不存在或日期已变化，请刷新后重试");
    }

    private static void checkDay(int day) {
        if (day < 0 || day > 6) throw new IllegalArgumentException("星期索引必须为 0 至 6");
    }

    private static void checkText(String title, String category, String body) {
        if (title == null || title.length() > BackupCodec.MAX_TITLE_LENGTH
                || category == null || category.trim().isEmpty()
                || category.length() > BackupCodec.MAX_CATEGORY_LENGTH) {
            throw new IllegalArgumentException("事项标题或分类无效");
        }
        if (body == null || body.length() > BackupCodec.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("事项正文无效或过长");
        }
    }
}
