package io.github.githuiy.starlive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertValueEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.core.PlanItem
import io.github.githuiy.starlive.core.WeekPlan
import io.github.githuiy.starlive.ui.IdeasScreen
import io.github.githuiy.starlive.ui.LifeIdeas
import io.github.githuiy.starlive.ui.StarLiveTheme
import io.github.githuiy.starlive.ui.mondayOf
import io.github.githuiy.starlive.ui.sparksWeekSlideAt
import io.github.githuiy.starlive.ui.sparksDateLabel
import io.github.githuiy.starlive.ui.sparksWeekDateRange
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h568dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SparksDatePickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dropdownChangesTheWeekAcrossTheNewYearAndReturnsToOverview() {
        renderSparks(LocalDate.of(2026, 12, 31), Modifier.fillMaxSize())

        node("sparks_week_picker").assertIsDisplayed()
        compose.runOnUiThread {
            val activity = compose.activity
            val viewport = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            assertEquals(320f, viewport.width / density, .5f)
            assertEquals(568f, viewport.height / density, .5f)
        }
        val starBounds = (0..6).map { node("sparks_star_$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        val pickerBounds = node("sparks_week_picker").fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            val viewportWidth = compose.activity.window.decorView.width
            assertEquals(viewportWidth / 2f, pickerBounds.center.x, 1f)
            assertTrue(pickerBounds.width < viewportWidth)
        }
        assertTrue(pickerBounds.top >= starBounds.maxOf { it.bottom })
        node("sparks_star_3").assertContentDescriptionContains("2026年12月31日，周四，今天")
        node("sparks_star_4").assertContentDescriptionContains("2027年1月1日，周五")
        node("sparks_star_4").performClick()
        node("sparks_selected_date").assertContentDescriptionContains("2027年1月1日，周五")
        val focusedSky = bounds("sparks_focused_sky")
        val date = node("sparks_plan_date").assertIsDisplayed().assertTextEquals("+1 1 月 1 日")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(date.left >= focusedSky.left && date.right <= focusedSky.center.x)
        assertTrue(date.top >= focusedSky.top && date.bottom <= focusedSky.center.y)
        compose.onNodeWithText("Fri", useUnmergedTree = true).assertIsDisplayed()
        (0..6).forEach { node("sparks_star_$it").assertDoesNotExist() }
        node("sparks_week_picker").assertDoesNotExist()
        node("sparks_show_overview").assertDoesNotExist()
        tapBlankSky()

        weekLabel("sparks_week_picker", "12.28 — +1 1.3  ·  本周").assertIsDisplayed()
        node("sparks_week_picker").assertIsDisplayed().performClick()
        val current = week("2026-12-28").assertIsSelected()
        val sameWeek = current.fetchSemanticsNode().boundsInRoot
        weekLabel("sparks_week_2026-12-28", "12.28 — +1 1.3  ·  本周").assertIsDisplayed()
        val next = week("2027-01-04")
        val followingWeek = next.fetchSemanticsNode().boundsInRoot
        assertEquals(sameWeek.height, followingWeek.height, 1f)
        weekLabel("sparks_week_2027-01-04", "+1 1.4 — +1 1.10  ·  下周").assertIsDisplayed()
        next.performClick()
        node("sparks_week_menu").assertDoesNotExist()
        node("sparks_selected_date").assertDoesNotExist()
        node("sparks_star_4").assertContentDescriptionContains("2027年1月8日，周五").performClick()
        node("sparks_selected_date").assertContentDescriptionContains("2027年1月8日，周五")
        val custom = node("idea_custom").assertIsDisplayed()
        val customBounds = custom.fetchSemanticsNode().boundsInRoot
        node("idea_card_${LifeIdeas.lastIndex}").assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        assertEquals(customBounds, custom.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        tapBlankSky()
        node("sparks_star_0").assertContentDescriptionContains("2027年1月4日，周一")
        node("sparks_star_6").assertContentDescriptionContains("2027年1月10日，周日")

        node("sparks_star_0").performClick()
        tapBlankSky()
        node("sparks_star_0").assertIsDisplayed()
    }

    @Test fun dropdownStopsAtTheLastCompleteWeekWithinThreeMonths() {
        val today = LocalDate.of(2026, 12, 31)
        renderSparks(today, Modifier.fillMaxSize())

        node("sparks_week_picker").performClick()
        node("sparks_week_2026-12-21").assertDoesNotExist()
        node("sparks_week_2027-03-29").assertDoesNotExist()
        assertEquals(bounds("sparks_week_menu").top, bounds("sparks_week_2026-12-28").top, 1f)
        week("2027-03-22").performClick()
        node("sparks_week_picker").performClick()
        node("sparks_week_2027-03-22").assertIsSelected().assertIsDisplayed()
        assertEquals(bounds("sparks_week_menu").bottom, bounds("sparks_week_2027-03-22").bottom, 1f)
        node("sparks_week_2027-03-22").performClick()
        (0..6).forEach { node("sparks_star_$it").assertIsEnabled() }
        node("sparks_star_6").assertContentDescriptionContains("2027年3月28日，周日").performClick()
        node("sparks_selected_date").assertContentDescriptionContains("2027年3月28日，周日")
        assertEquals("12.31", sparksDateLabel(today, today))
        assertEquals("+1 1.1", sparksDateLabel(today.plusDays(1), today))
        val decemberMonday = LocalDate.of(2026, 12, 28)
        assertEquals("12.28 — +1 1.3", sparksWeekDateRange(decemberMonday, today))
        assertEquals("2026.12.28 — 1.3", sparksWeekDateRange(decemberMonday, today.plusDays(1)))
        assertEquals("+1 3.22 — +1 3.28", sparksWeekDateRange(LocalDate.of(2027, 3, 22), today))
    }

    @Test fun animatedWeekSelectionSettlesOnTheChosenDatesInBothDirections() {
        renderSparks(LocalDate.of(2026, 11, 14), Modifier.fillMaxSize(), reduceMotion = false)

        val monday = node("sparks_star_0").assertContentDescriptionContains("2026年11月9日，周一")
        val picker = node("sparks_week_picker").assertIsEnabled()
        picker.performClick()
        week("2027-01-04").performClick()
        monday.assertIsEnabled().assertContentDescriptionContains("2027年1月4日，周一").performClick()
        node("sparks_selected_date").assertContentDescriptionContains("2027年1月4日，周一")
        tapBlankSky()
        picker.assertIsEnabled().performClick()
        week("2026-11-09").performClick()
        monday.assertIsEnabled().assertContentDescriptionContains("2026年11月9日，周一")
    }

    @Test fun returningFromConfiguredPlansDoesNotRevealRecommendationsDuringExit() {
        renderSparks(LocalDate.of(2026, 11, 14), Modifier.fillMaxSize(), reduceMotion = false)
        node("sparks_star_2").performClick()
        node("sparks_selected_date").performClick()
        node("sparks_configured_plans").assertIsDisplayed()
        val sky = node("sparks_sky_background").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            sky.performClick()
            // The outgoing plan overlay must not switch to recommendation content as it fades.
            repeat(14) {
                compose.mainClock.advanceTimeByFrame()
                node("idea_categories").assertDoesNotExist()
                node("idea_custom").assertDoesNotExist()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        node("sparks_star_2").assertIsDisplayed()
        node("sparks_configured_plans").assertDoesNotExist()
    }

    @Test fun forwardAndBackwardWeekSlidesPresentIncomingWeeksOnOppositeSides() {
        val firstWeek = LocalDate.of(2026, 11, 9)
        val nextWeek = firstWeek.plusWeeks(1)
        val forward = sparksWeekSlideAt(firstWeek, .38f, direction = 1)
        val backward = sparksWeekSlideAt(nextWeek, -.38f, direction = -1)
        assertEquals(firstWeek, forward.monday)
        assertEquals(nextWeek, forward.nextMonday)
        assertEquals(nextWeek, backward.monday)
        assertEquals(firstWeek, backward.nextMonday)
        assertEquals(.38f, forward.fraction * forward.direction, .001f)
        assertEquals(-.38f, backward.fraction * backward.direction, .001f)
    }

    @Test fun overviewSwipesMoveBetweenWeeksAndStopAtTheFourthPreviousWeek() {
        val today = LocalDate.of(2026, 11, 18)
        val currentMonday = mondayOf(today)
        renderSparks(today, Modifier.fillMaxSize(), reduceMotion = false)

        assertOverviewMonday(currentMonday)
        swipeOverview(earlier = true)
        assertOverviewMonday(currentMonday.minusWeeks(1))
        swipeOverview(earlier = false)
        assertOverviewMonday(currentMonday)
        swipeOverview(earlier = false)
        assertOverviewMonday(currentMonday.plusWeeks(1))
        swipeOverview(earlier = true)
        repeat(4) { index ->
            swipeOverview(earlier = true)
            assertOverviewMonday(currentMonday.minusWeeks(index + 1L))
        }
        swipeOverview(earlier = true)
        val earliest = currentMonday.minusWeeks(4)
        assertOverviewMonday(earliest)

        node("sparks_week_picker").assertIsDisplayed().performClick()
        weekLabel("sparks_week_picker", sparksWeekDateRange(earliest, today) + "  ·  历史").assertIsDisplayed()
        node("sparks_week_$earliest").assertDoesNotExist()
        node("sparks_week_${currentMonday.minusWeeks(1)}").assertDoesNotExist()
        week(currentMonday.toString()).performClick()
        assertOverviewMonday(currentMonday)
    }

    @Test fun historyOpensReadOnlyWithStatusesWhileEditableWeeksKeepTheirExistingView() {
        val today = LocalDate.of(2026, 11, 18)
        val currentMonday = mondayOf(today)
        val done = PlanItem("history-completed", "写完上周读书笔记", "阅读", true)
        val undone = PlanItem("history-open", "上周尚未整理的照片", "喜欢", false)
        val current = PlanItem("current-open", "这一周的安排", "阅读", false)
        val future = PlanItem("future-open", "下一周的安排", "运动", false)
        val past = WeekPlan(List(7) { if (it == 0) listOf(done, undone) else emptyList() })
        val active = WeekPlan(List(7) { if (it == 0) listOf(current) else emptyList() })
        val next = WeekPlan(List(7) { if (it == 0) listOf(future) else emptyList() })
        renderSparks(today, Modifier.fillMaxSize(), weeks = mapOf(currentMonday.minusWeeks(1) to past,
            currentMonday to active, currentMonday.plusWeeks(1) to next))

        swipeOverview(earlier = true)
        node("sparks_star_0").performClick()
        node("sparks_configured_plans").assertIsDisplayed()
        node("sparks_selected_date").assertHasNoClickAction()
        node("sparks_focus_hint").assertDoesNotExist()
        node("idea_custom").assertDoesNotExist()
        node("idea_categories").assertDoesNotExist()
        node("open_planner").assertDoesNotExist()
        node("sparks_plan_${done.id}").assertIsDisplayed().assertHasNoClickAction()
        node("sparks_plan_status_${done.id}").assertTextEquals("已完成")
        node("sparks_plan_${undone.id}").assertIsDisplayed().assertHasNoClickAction()
        node("sparks_plan_status_${undone.id}").assertTextEquals("未完成")

        tapBlankSky()
        node("sparks_star_1").performClick()
        node("sparks_configured_empty").assertIsDisplayed()
        tapBlankSky()
        swipeOverview(earlier = false)
        node("sparks_star_0").performClick()
        node("idea_custom").assertIsDisplayed()
        node("sparks_selected_date").performClick()
        node("sparks_plan_${current.id}").assertIsDisplayed().assertHasClickAction()
        node("sparks_plan_status_${current.id}").assertDoesNotExist()

        tapBlankSky()
        swipeOverview(earlier = false)
        node("sparks_star_0").performClick()
        node("sparks_selected_date").performClick()
        node("sparks_plan_${future.id}").assertIsDisplayed().assertHasClickAction()
        node("sparks_plan_status_${future.id}").assertDoesNotExist()
    }

    @Test fun completedCompanionsInSparksDoNotInterceptTheSkyReturn() {
        val today = LocalDate.of(2026, 11, 18)
        val completed = PlanItem("spark-completed", "读完一章", "阅读", true)
        val plans = WeekPlan(List(7) { if (it == 0) listOf(completed) else emptyList() })
        renderSparks(today, Modifier.fillMaxSize(), weeks = mapOf(mondayOf(today) to plans))

        node("sparks_star_0").performClick()
        val companion = node("companion_${completed.id}").assertIsDisplayed().assertHasNoClickAction()
        companion.assertContentDescriptionContains(completed.title).assertValueEquals("已完成")
        val position = companion.fetchSemanticsNode().boundsInRoot.center
        val sky = node("sparks_sky_background")
        val origin = sky.fetchSemanticsNode().boundsInRoot.topLeft
        sky.performTouchInput { click(position - origin) }
        node("sparks_star_0").assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xxxhdpi")
    @Test fun landscapeKeepsTheWeekChoicesAndPlansReachableInShortContent() {
        renderSparks(LocalDate.of(2026, 11, 14),
            Modifier.size(width = 680.dp, height = 320.dp).testTag("sparks_short_content"))
        node("sparks_short_content").assertWidthIsEqualTo(680.dp).assertHeightIsEqualTo(320.dp)

        node("sparks_week_picker").assertIsDisplayed().performClick()
        node("sparks_week_2026-11-09").assertIsDisplayed().assertIsSelected()
        week("2026-11-16").performClick()
        node("sparks_star_2").assertIsDisplayed().performClick()
        node("sparks_selected_date").assertContentDescriptionContains("2026年11月18日，周三")

        val custom = node("idea_custom").assertIsDisplayed()
        val recommendation = node("idea_card_0").performScrollTo().assertIsDisplayed()
        val category = node("idea_category_全部").assertIsDisplayed().assertIsSelected()
        assertTrue(category.fetchSemanticsNode().boundsInRoot.left >=
            recommendation.fetchSemanticsNode().boundsInRoot.right)
        assertTrue(custom.fetchSemanticsNode().boundsInRoot.left >=
            recommendation.fetchSemanticsNode().boundsInRoot.right)
        node("idea_category_交流思考").assertDoesNotExist()
        node("idea_category_思考").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText("和人或 AI 交流最近的思考").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("给房间一个小小整理").assertDoesNotExist()
        custom.assertIsDisplayed()

        tapBlankSky()
        node("sparks_week_picker").assertIsDisplayed()
        node("sparks_star_2").assertIsDisplayed()
        node("idea_custom").assertDoesNotExist()
    }

    @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xxxhdpi")
    @Test fun shortLandscapeKeepsConfiguredDateAndEmptyStateBelowTheWeekPicker() {
        renderSparks(LocalDate.of(2026, 11, 14),
            Modifier.size(width = 680.dp, height = 224.dp))
        node("sparks_star_5").performClick()
        node("sparks_selected_date").performClick()
        val picker = bounds("sparks_week_picker")
        val date = bounds("sparks_configured_date")
        val empty = bounds("sparks_configured_empty")
        assertTrue(picker.bottom <= date.top)
        assertTrue(date.bottom <= empty.top)
        node("sparks_selected_date").performClick()
        node("idea_custom").assertIsDisplayed()
    }

    private fun renderSparks(today: LocalDate, modifier: Modifier, reduceMotion: Boolean = true,
        weeks: Map<LocalDate, WeekPlan> = emptyMap()) {
        val empty = WeekPlan.empty()
        var monday by mutableStateOf(mondayOf(today))
        var selectedDay by mutableStateOf<Int?>(null)
        val selectWeek: (LocalDate) -> Unit = { date ->
            monday = date
            selectedDay = null
        }
        compose.setContent {
            StarLiveTheme {
                IdeasScreen(today, monday, selectWeek,
                    selectedDay, { selectedDay = it }, weeks[monday] ?: empty,
                    onAdd = { _, _, _, _, _ -> }, reduceMotion = reduceMotion, modifier = modifier,
                    onOverview = { selectedDay = null }, confirmedWeek = { weeks[it] ?: empty },
                    onOpenPlanner = { _, _ -> }, onUpdate = { _, _, _, _, _, _ -> })
            }
        }
    }

    private fun week(monday: String) = node("sparks_week_$monday").performScrollTo().assertIsDisplayed()

    private fun bounds(tag: String) = node(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private fun weekLabel(tag: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun tapBlankSky() {
        node("sparks_sky_background").assertIsDisplayed()
            .performTouchInput { click(Offset(width * .08f, height * .1f)) }
    }

    private fun swipeOverview(earlier: Boolean) {
        node("sparks_overview").assertIsDisplayed().performTouchInput {
            val left = Offset(width * .18f, height * .28f)
            val right = Offset(width * .82f, left.y)
            if (earlier) swipe(right, left) else swipe(left, right)
        }
    }

    private fun assertOverviewMonday(monday: LocalDate) {
        node("sparks_star_0").assertContentDescriptionContains("${monday.year}年${monday.monthValue}月" +
            "${monday.dayOfMonth}日，周一")
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
}
