package io.github.githuiy.starlive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.githuiy.starlive.core.PlanItem
import io.github.githuiy.starlive.core.WeekPlan
import io.github.githuiy.starlive.ui.StarLiveTheme
import io.github.githuiy.starlive.ui.WeeklyRecordsPanel
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
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WeeklyRecordsPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val monday = LocalDate.of(2026, 11, 9)
    private val overview = mutableStateOf(true)
    private val expanded = mutableStateOf(false)
    private val selectedMonday = mutableStateOf(monday)

    @Test fun weeklyEnergyIncludesEmptyWeeksAndSelectsHistory() {
        setContent()

        panel().assertDoesNotExist()
        edge().assertIsDisplayed().performClick()
        panel().assertIsDisplayed()

        val emptyMonday = monday.minusWeeks(1)
        val oldestMonday = monday.minusWeeks(2)
        assertEnergy(monday, "50%")
        assertEnergy(emptyMonday, "0%")
        assertEnergy(oldestMonday, "100%")
        compose.onNodeWithText("本周").assertIsDisplayed()
        compose.onNodeWithText("上周").assertIsDisplayed()
        compose.onNodeWithText("10.26 — 11.1").assertIsDisplayed()
        compose.onNodeWithText("上上周").assertDoesNotExist()
        row(monday.plusWeeks(1)).assertDoesNotExist()
        assertTrue(row(monday).fetchSemanticsNode().boundsInRoot.top <
            row(emptyMonday).fetchSemanticsNode().boundsInRoot.top)
        assertTrue(row(emptyMonday).fetchSemanticsNode().boundsInRoot.top <
            row(oldestMonday).fetchSemanticsNode().boundsInRoot.top)

        row(oldestMonday).performClick()
        compose.runOnIdle { assertEquals(oldestMonday, selectedMonday.value) }
    }

    @Test fun enteringDayFocusCollapsesThePanelAndKeepsItCollapsedOnReturn() {
        setContent()
        edge().performClick()
        panel().assertIsDisplayed()

        compose.runOnIdle {
            overview.value = false
            expanded.value = false
        }
        panel().assertDoesNotExist()
        edge().assertDoesNotExist()

        compose.runOnIdle { overview.value = true }
        panel().assertDoesNotExist()
        edge().assertIsDisplayed().performClick()
        panel().assertIsDisplayed()
    }

    @Test fun aNewStoreShowsTheThreeMostRecentWeeksAtZeroPercent() {
        setContent(emptyMap())
        edge().performClick()

        assertEnergy(monday, "0%")
        assertEnergy(monday.minusWeeks(1), "0%")
        assertEnergy(monday.minusWeeks(2), "0%")
        row(monday.minusWeeks(3)).assertDoesNotExist()
    }

    @Test fun horizontalSwipesCloseAndReopenThePanel() {
        setContent()

        edge().performTouchInput {
            swipeLeft(startX = center.x, endX = center.x - width * 4f)
        }
        panel().assertIsDisplayed()
        val grip = node("weekly_records_grip").assertIsDisplayed()
        grip.performTouchInput { swipeRight(startX = center.x, endX = center.x + width * 4f) }
        panel().assertDoesNotExist()
        edge().assertIsDisplayed()
    }

    @Test fun tappingTheBlankSkyCollapsesTheExpandedPanel() {
        setContent()
        edge().performClick()
        panel().assertIsDisplayed()

        node("weekly_records_backdrop").performTouchInput {
            click(Offset(width * .15f, height * .25f))
        }

        panel().assertDoesNotExist()
        node("weekly_records_grip").assertDoesNotExist()
        edge().assertIsDisplayed()
    }

    private fun setContent(weeks: Map<String, WeekPlan> = sampleWeeks()) {
        compose.setContent {
            StarLiveTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    WeeklyRecordsPanel(weeks, currentMonday = monday, selectedMonday = selectedMonday.value,
                        overview = overview.value, expanded = expanded.value, reduceMotion = true,
                        onExpandedChange = { expanded.value = it }, onWeekSelected = { selectedMonday.value = it },
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun sampleWeeks(): Map<String, WeekPlan> {
        val walk = PlanItem("0c89b15b-fab9-4fa9-9f98-380c36a28bd0", "沿河走一圈", "散步", true)
        val read = PlanItem("d9066f0a-5de1-4b92-92be-d1b07b009369", "读一章书", "阅读", false)
        val practice = PlanItem("eb7c3b94-ad2c-4ba4-b3a3-e5103b42d046", "练琴", "音乐", true)
        val future = PlanItem("00ed33e4-11b0-4b5c-a573-2ff711c88632", "看展览", "展览", false)
        return mapOf(
            monday.toString() to week(walk, read),
            monday.minusWeeks(2).toString() to week(practice),
            monday.plusWeeks(1).toString() to week(future),
        )
    }

    private fun week(vararg plans: PlanItem) =
        WeekPlan(List(7) { day -> if (day == 0) plans.toList() else emptyList() })

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun panel() = node("weekly_records_panel")

    private fun edge() = node("weekly_records_edge")

    private fun row(date: LocalDate) = node("weekly_energy_$date")

    private fun assertEnergy(date: LocalDate, percentage: String) {
        val tag = hasTestTag("weekly_energy_$date")
        compose.onNode(hasText(percentage) and (tag or hasAnyAncestor(tag)), useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
