package io.github.githuiy.starlive.core;

import java.time.LocalDate;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlanningHorizonTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private static final String LAST_WEEK = "2027-02-08";
    private static final int SUNDAY = 6;

    @Test public void sundayCutoffIsInclusiveAcrossTheYearBoundary() {
        LocalDate firstMonday = LocalDate.of(2026, 11, 9);
        LocalDate lastDate = LocalDate.of(2027, 2, 14);
        assertEquals(firstMonday, PlanningHorizon.firstMonday(TODAY));
        assertEquals(LocalDate.parse(LAST_WEEK), PlanningHorizon.lastMonday(TODAY));
        assertEquals(lastDate, PlanningHorizon.lastDate(TODAY));
        assertTrue(PlanningHorizon.includes(TODAY, firstMonday));
        assertTrue(PlanningHorizon.includes(TODAY, lastDate));
        assertFalse(PlanningHorizon.includes(TODAY, firstMonday.minusDays(1)));
        assertFalse(PlanningHorizon.includes(TODAY, lastDate.plusDays(1)));
        assertFalse(PlanningHorizon.includes(TODAY, LocalDate.of(TODAY.getYear() + 2, 1, 1)));
    }

    @Test public void saturdayCutoffExcludesThePartialFinalWeek() {
        LocalDate today = TODAY.minusDays(1);
        assertEquals(LocalDate.of(2027, 2, 7), PlanningHorizon.lastDate(today));
        assertEquals(LocalDate.of(2027, 2, 1), PlanningHorizon.lastMonday(today));
        assertFalse(PlanningHorizon.includes(today, LocalDate.parse(LAST_WEEK)));
    }

    @Test public void monthEndClampsBeforeRoundingDownToSunday() {
        LocalDate today = LocalDate.of(2027, 11, 30);
        assertEquals(LocalDate.of(2028, 2, 27), PlanningHorizon.lastDate(today));
        assertEquals(LocalDate.of(2028, 2, 21), PlanningHorizon.lastMonday(today));
    }

    @Test public void sparksSavesDistantDraftsInExistingStorageWithoutChangingPlannerRules() {
        PlanEngine engine = new PlanEngine(TODAY);
        AppState original = engine.addSparksDraftItem(AppState.empty(), "2026-11-09", 0,
                "整理衣柜", "家务", "");
        AppState saved = engine.addSparksDraftItem(original, LAST_WEEK, SUNDAY,
                "散步", "运动", "在公园里走一圈");
        assertSame(saved, engine.addSparksDraftItem(saved, LAST_WEEK, SUNDAY,
                " 散步 ", "喜欢", "不覆盖原来的正文"));
        assertTrue(saved.weeks.isEmpty());
        assertFalse(original.drafts.containsKey(LAST_WEEK));

        AppState restored = BackupCodec.decode(BackupCodec.encode(saved)).data;
        PlanItem item = PlanEngine.draft(restored, LAST_WEEK).days.get(SUNDAY).get(0);
        assertEquals("散步", item.title);
        assertEquals("在公园里走一圈", item.body);
        assertThrows(IllegalArgumentException.class, () -> engine.addDraftItem(
                restored, LAST_WEEK, SUNDAY, "读书", "阅读"));
        assertThrows(IllegalArgumentException.class, () -> engine.confirmDraft(restored, LAST_WEEK));

        PlanEngine later = new PlanEngine(LocalDate.of(2027, 2, 6));
        AppState confirmed = later.confirmDraft(restored, LAST_WEEK);
        assertEquals(item.id, PlanEngine.confirmed(confirmed, LAST_WEEK).days.get(SUNDAY).get(0).id);
    }

    @Test public void sparksRejectsHistoricalWeeksAndDatesAfterTheHorizon() {
        PlanEngine engine = new PlanEngine(TODAY);
        AppState empty = AppState.empty();
        assertThrows(IllegalArgumentException.class, () -> engine.addSparksDraftItem(
                empty, "2026-11-02", 6, "已过去的周", "喜欢", ""));
        assertThrows(IllegalArgumentException.class, () -> engine.addSparksDraftItem(
                empty, "2027-02-15", 0, "超过最后一周", "喜欢", ""));
        assertThrows(IllegalArgumentException.class, () -> engine.addSparksDraftItem(
                empty, "2026-11-14", 0, "误用周起始日", "喜欢", ""));
        assertThrows(IllegalArgumentException.class, () -> engine.addSparksDraftItem(
                empty, "2026-11-09", 7, "无效的星期编号", "喜欢", ""));
        assertTrue(empty.drafts.isEmpty());
    }
}
