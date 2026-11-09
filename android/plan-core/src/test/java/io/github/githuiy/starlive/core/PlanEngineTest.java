package io.github.githuiy.starlive.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlanEngineTest {
    private final PlanEngine engine = new PlanEngine(LocalDate.of(2026, 11, 8));
    private static final String WEEK = "2026-11-02";

    private AppState oneConfirmed() {
        return engine.confirmDraft(engine.addDraftItem(AppState.empty(), WEEK, 0,
                "整理衣物", "家务"), WEEK);
    }

    private String firstId(AppState state) { return state.weeks.get(WEEK).days.get(0).get(0).id; }

    @Test public void draftAndDeleteNeverPolluteConfirmedUntilExplicitConfirmation() {
        AppState original = oneConfirmed();
        String id = firstId(original);
        AppState changed = engine.updateDraftItem(original, WEEK, 0, id, "和朋友聊天", "交流思考");
        assertEquals("整理衣物", changed.weeks.get(WEEK).days.get(0).get(0).title);
        assertEquals("和朋友聊天", changed.drafts.get(WEEK).days.get(0).get(0).title);
        AppState deleted = engine.deleteDraftItem(changed, WEEK, 0, id);
        assertEquals(1, deleted.weeks.get(WEEK).days.get(0).size());
        assertTrue(engine.confirmDraft(deleted, WEEK).weeks.get(WEEK).days.get(0).isEmpty());
        assertEquals("整理衣物", original.weeks.get(WEEK).days.get(0).get(0).title);
    }

    @Test public void oldDraftCannotUndoLaterCompletionOnHome() {
        AppState state = oneConfirmed();
        String id = firstId(state);
        state = engine.updateDraftItem(state, WEEK, 0, id, "整理衣物", "喜欢");
        assertFalse(state.drafts.get(WEEK).days.get(0).get(0).done);
        state = engine.toggleCompletion(state, WEEK, 0, id);
        assertFalse(state.drafts.get(WEEK).days.get(0).get(0).done);
        state = engine.confirmDraft(state, WEEK);
        assertTrue(state.weeks.get(WEEK).days.get(0).get(0).done);
        assertEquals("喜欢", state.weeks.get(WEEK).days.get(0).get(0).category);
    }

    @Test public void oldCompletedDraftCannotRedoAHomeCancellation() {
        AppState state = oneConfirmed();
        String id = firstId(state);
        state = engine.toggleCompletion(state, WEEK, 0, id);
        state = engine.confirmDraft(state, WEEK);
        assertTrue(state.drafts.get(WEEK).days.get(0).get(0).done);
        state = engine.toggleCompletion(state, WEEK, 0, id);
        assertFalse(engine.confirmDraft(state, WEEK).weeks.get(WEEK).days.get(0).get(0).done);
    }

    @Test public void renamingResetsCompletionButWhitespaceAndCategoryDoNot() {
        AppState state = oneConfirmed();
        String id = firstId(state);
        state = engine.toggleCompletion(state, WEEK, 0, id);
        state = engine.updateDraftItem(state, WEEK, 0, id, "  整理衣物  ", "休息");
        state = engine.confirmDraft(state, WEEK);
        assertTrue(state.weeks.get(WEEK).days.get(0).get(0).done);
        assertEquals("整理衣物", state.weeks.get(WEEK).days.get(0).get(0).title);
        state = engine.updateDraftItem(state, WEEK, 0, id, "整理书架", "家务");
        assertTrue(state.weeks.get(WEEK).days.get(0).get(0).done);
        assertFalse(engine.confirmDraft(state, WEEK).weeks.get(WEEK).days.get(0).get(0).done);
    }

    @Test public void movingAcrossDaysKeepsIdentityButResetsDoneAfterConfirmation() {
        AppState state = oneConfirmed();
        String id = firstId(state);
        state = engine.toggleCompletion(state, WEEK, 0, id);
        state = engine.moveDraftItem(state, WEEK, 0, id, 6, 0);
        assertTrue(state.weeks.get(WEEK).days.get(0).get(0).done);
        state = engine.confirmDraft(state, WEEK);
        assertTrue(state.weeks.get(WEEK).days.get(0).isEmpty());
        PlanItem moved = state.weeks.get(WEEK).days.get(6).get(0);
        assertEquals(id, moved.id);
        assertFalse(moved.done);
    }

    @Test public void reorderingSameDayKeepsLatestCompletionAndStableIds() {
        AppState state = oneConfirmed();
        String id = firstId(state);
        state = engine.addDraftItem(state, WEEK, 0, "读书", "阅读");
        state = engine.confirmDraft(state, WEEK);
        state = engine.toggleCompletion(state, WEEK, 0, id);
        state = engine.moveDraftItem(state, WEEK, 0, id, 0, 1);
        state = engine.confirmDraft(state, WEEK);
        assertEquals("读书", state.weeks.get(WEEK).days.get(0).get(0).title);
        assertEquals(id, state.weeks.get(WEEK).days.get(0).get(1).id);
        assertTrue(state.weeks.get(WEEK).days.get(0).get(1).done);
    }

    @Test public void duplicateIdeasAreDedupedPerDayAndBlankRowsAreFiltered() {
        AppState state = engine.addDraftItem(AppState.empty(), WEEK, 0, "散步", "运动");
        state = engine.addDraftItem(state, WEEK, 0, " 散步 ", "探索");
        state = engine.addDraftItem(state, WEEK, 1, "散步", "运动");
        state = engine.addDraftItem(state, WEEK, 0, " ", "喜欢");
        assertTrue(state.weeks.isEmpty());
        assertEquals(2, state.drafts.get(WEEK).days.get(0).size());
        state = engine.confirmDraft(state, WEEK);
        assertEquals(1, state.weeks.get(WEEK).days.get(0).size());
        assertEquals(1, state.weeks.get(WEEK).days.get(1).size());
        assertNotEquals(state.weeks.get(WEEK).days.get(0).get(0).id,
                state.weeks.get(WEEK).days.get(1).get(0).id);
    }

    @Test public void yearBoundaryAndNewWeekRetainHistoryWithoutRollover() {
        PlanEngine december = new PlanEngine(LocalDate.of(2026, 12, 31));
        AppState state = december.addDraftItem(AppState.empty(), "2026-12-28", 6, "休息", "休息");
        state = december.confirmDraft(state, "2026-12-28");
        state = december.addDraftItem(state, "2027-01-04", 0, "读书", "阅读");
        assertEquals("2026-12-28", PlanEngine.weekKey(LocalDate.of(2027, 1, 3)));
        assertEquals("2027-01-04", PlanEngine.weekKey(LocalDate.of(2027, 1, 4)));
        PlanEngine january = new PlanEngine(LocalDate.of(2027, 1, 4));
        state = january.confirmDraft(state, "2027-01-04");
        assertEquals("休息", state.weeks.get("2026-12-28").days.get(6).get(0).title);
        assertEquals(1, state.weeks.get("2027-01-04").days.get(0).size());
        final AppState historical = state;
        assertThrows(IllegalArgumentException.class, () -> january.addDraftItem(
                historical, "2026-12-28", 0, "不应改历史", "喜欢"));
    }

    @Test public void snapshotsAndTheirCollectionsAreImmutable() {
        AppState state = oneConfirmed();
        assertThrows(UnsupportedOperationException.class,
                () -> state.weeks.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> state.weeks.get(WEEK).days.get(0).clear());
        List<List<PlanItem>> copied = state.weeks.get(WEEK).mutableDays();
        copied.get(0).clear();
        assertEquals(1, state.weeks.get(WEEK).days.get(0).size());
    }
}
