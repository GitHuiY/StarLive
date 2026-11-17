package io.github.githuiy.starlive.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DirectPlanTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final String THIS_WEEK = "2026-11-16";
    private static final String LAST_WEEK = "2027-02-08";
    private final PlanEngine engine = new PlanEngine(TODAY);

    @Test public void savedPlansAppearImmediatelyAndCanBeEditedOrDeletedAtTheHorizon() {
        AppState original = AppState.empty();
        AppState saved = engine.addPlan(original, LAST_WEEK, 6, "  散步  ", "运动", "公园");
        PlanItem plan = saved.weeks.get(LAST_WEEK).days.get(6).get(0);
        assertTrue(saved.drafts.isEmpty());
        assertTrue(original.weeks.isEmpty());
        assertEquals("散步", plan.title);
        assertSame(saved, engine.addPlan(saved, LAST_WEEK, 6, "散步", "喜欢", ""));

        AppState edited = engine.updatePlan(saved, LAST_WEEK, 6, plan.id, "晨跑", "运动", "沿河");
        AppState restored = BackupCodec.decode(BackupCodec.encode(edited)).data;
        PlanItem restoredPlan = restored.weeks.get(LAST_WEEK).days.get(6).get(0);
        assertEquals(plan.id, restoredPlan.id);
        assertEquals("晨跑", restoredPlan.title);
        assertEquals("沿河", restoredPlan.body);
        assertTrue(engine.deletePlan(restored, LAST_WEEK, 6, plan.id).weeks.get(LAST_WEEK).days.get(6).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> engine.addPlan(original, "2026-11-09", 0, "历史", "喜欢", ""));
        assertThrows(IllegalArgumentException.class,
                () -> engine.addPlan(original, "2027-02-15", 0, "越界", "喜欢", ""));
        assertThrows(IllegalArgumentException.class,
                () -> engine.addPlan(original, THIS_WEEK, 0, "  ", "喜欢", ""));
    }

    @Test public void directEditsAndMovesPreserveCompletionOnlyForSameTitleAndDay() {
        AppState saved = engine.addPlan(AppState.empty(), THIS_WEEK, 0, "晨跑", "运动", "");
        String id = saved.weeks.get(THIS_WEEK).days.get(0).get(0).id;
        AppState completed = engine.toggleCompletion(saved, THIS_WEEK, 0, id);
        AppState edited = engine.updatePlan(completed, THIS_WEEK, 0, id, " 晨跑 ", "喜欢", "换个路线");
        assertTrue(edited.weeks.get(THIS_WEEK).days.get(0).get(0).done);
        assertFalse(engine.updatePlan(edited, THIS_WEEK, 0, id, "散步", "运动", "")
                .weeks.get(THIS_WEEK).days.get(0).get(0).done);
        assertTrue(engine.movePlan(edited, THIS_WEEK, 0, id, 0, 0)
                .weeks.get(THIS_WEEK).days.get(0).get(0).done);
        PlanItem moved = engine.movePlan(edited, THIS_WEEK, 0, id, 1, 0)
                .weeks.get(THIS_WEEK).days.get(1).get(0);
        assertEquals(id, moved.id);
        assertFalse(moved.done);
    }

    @Test public void oldDraftsMergeEditsAndAdditionsWithoutApplyingUnconfirmedDeletions() {
        PlanItem completed = item(1, "晨跑", true, "原正文");
        PlanItem renamed = item(2, "旧标题", true, "");
        PlanItem absent = item(3, "保留原计划", false, "");
        PlanItem moved = item(4, "换一天", true, "");
        PlanItem didNotComplete = item(5, "完成被撤销", false, "");
        PlanItem sameTitle = item(6, "晨跑", true, "");
        PlanItem distant = item(7, "远期的旧草稿", false, "保留正文");
        AppState legacy = new AppState(
                Map.of(THIS_WEEK, week(List.of(completed, renamed, absent, moved, didNotComplete), List.of())),
                Map.of(THIS_WEEK, week(List.of(item(2, "改名", true, ""),
                        item(1, " 晨跑 ", false, "新正文"), item(5, "完成被撤销", true, ""),
                        sameTitle, item(8, "  ", false, "")), List.of(moved)),
                        "2027-04-05", week(List.of(distant), List.of())),
                ReminderSettings.defaults());

        AppState normal = PlanEngine.mergeLegacyDrafts(legacy);
        assertTrue(normal.drafts.isEmpty());
        assertSame(normal, PlanEngine.mergeLegacyDrafts(normal));
        assertFalse(legacy.drafts.isEmpty());
        List<PlanItem> plans = normal.weeks.get(THIS_WEEK).days.get(0);
        assertEquals(List.of(renamed.id, completed.id, didNotComplete.id, sameTitle.id, absent.id),
                plans.stream().map(plan -> plan.id).toList());
        assertEquals("改名", plans.get(0).title);
        assertFalse(plans.get(0).done);
        assertEquals("新正文", plans.get(1).body);
        assertTrue(plans.get(1).done);
        assertFalse(plans.get(2).done);
        assertFalse(plans.get(3).done);
        assertSame(absent, plans.get(4));
        PlanItem movedOnce = normal.weeks.get(THIS_WEEK).days.get(1).get(0);
        assertEquals(moved.id, movedOnce.id);
        assertFalse(movedOnce.done);
        assertEquals("保留正文", normal.weeks.get("2027-04-05").days.get(0).get(0).body);
        assertEquals(7, BackupCodec.decode(BackupCodec.encode(normal)).summary.itemCount);
    }

    private static PlanItem item(int number, String title, boolean done, String body) {
        return new PlanItem("0c89b15b-fab9-4fa9-9f98-380c36a28bd" + number, title, "运动", done, body);
    }

    private static WeekPlan week(List<PlanItem> firstDay, List<PlanItem> secondDay) {
        return new WeekPlan(List.of(firstDay, secondDay, List.of(), List.of(), List.of(), List.of(), List.of()));
    }
}
