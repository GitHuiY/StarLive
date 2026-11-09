package io.github.githuiy.starlive

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import io.github.githuiy.starlive.core.PlanItem
import io.github.githuiy.starlive.core.ReminderSettings
import io.github.githuiy.starlive.core.WeekPlan
import io.github.githuiy.starlive.ui.HomeScreen
import io.github.githuiy.starlive.ui.SpaceScreen
import io.github.githuiy.starlive.ui.StarBackdrop
import io.github.githuiy.starlive.ui.StarLiveTheme
import io.github.githuiy.starlive.ui.IdeasScreen
import io.github.githuiy.starlive.ui.mondayOf
import io.github.githuiy.starlive.ui.TaskEditorDialog
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
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
class LargeTextUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        var rendered: Bitmap? = null
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            rendered = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(Canvas(it))
            }
        }
        val bitmap = checkNotNull(rendered)
        val folder = File(System.getProperty("starlive.ui.artifacts") ?: "build/reports/starlive-ui")
        check(folder.mkdirs() || folder.isDirectory)
        File(folder, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun largeTextTaskRemainsScrollableAndCanBeCompleted() {
        var done by mutableStateOf(false)
        var toggles = 0
        val title = "给自己留一点时间，读完想读的一章，再去窗边看看风景"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                StarLiveTheme {
                    StarBackdrop(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            HomeScreen(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9),
                                WeekPlan((0..6).map { day -> if (day == 0) listOf(PlanItem("large-text", title, "阅读", done)) else emptyList() }),
                                true, 0, {}, 0, {}, false, {}, false, false, {}, true,
                                onToggle = { done = !done; toggles++ }, onPlanner = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("完成：$title").performScrollTo().assertIsDisplayed()
        screenshot("05-large-text-day.png")
        compose.onNodeWithContentDescription("完成：$title").performClick()
        compose.onNodeWithContentDescription("取消完成：$title").assertIsOn()
        assertEquals(1, toggles)
    }

    @Test fun largeTextThresholdRejectsInvalidValueWithoutChangingSettings() {
        var settings by mutableStateOf(ReminderSettings.defaults())
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                StarLiveTheme {
                    StarBackdrop(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            SpaceScreen(settings, "Asia/Shanghai", { change -> settings = change(settings) }, false, {}, {}, {})
                        }
                    }
                }
            }
        }
        val field = compose.onNodeWithTag("setting_continuous_minutes", useUnmergedTree = true)
        field.performScrollTo().performTextClearance()
        field.performTextInput("0")
        compose.onNodeWithTag("setting_continuous_save", useUnmergedTree = true)
            .performScrollTo().assertIsNotEnabled()
        assertEquals(60, settings.continuousMinutes)
        screenshot("06-large-text-threshold.png")
    }

    @Test fun fieldIntentsMergeEvenWhileUiStillRendersTheOldSnapshot() {
        val queued = mutableListOf<(ReminderSettings) -> ReminderSettings>()
        compose.setContent {
            StarLiveTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    SpaceScreen(ReminderSettings.defaults(), "Asia/Shanghai", { queued += it }, false, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("setting_continuous_enabled", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("setting_per_app_enabled", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(2, queued.size)
        val merged = queued.fold(ReminderSettings.defaults()) { current, change -> change(current) }
        org.junit.Assert.assertFalse(merged.continuousEnabled)
        org.junit.Assert.assertFalse(merged.perAppDailyEnabled)
        org.junit.Assert.assertTrue(merged.totalDailyEnabled)
        assertEquals(60, merged.continuousMinutes)
    }

    // Host workaround only: https://github.com/robolectric/robolectric/issues/8460
    // The remaining layout/screenshot cases keep the 360dp phone viewport.
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi")
    @Test fun customIdeaCapturesItsOriginalDateWhenTheWeekChanges() {
        var date by mutableStateOf(LocalDate.of(2026, 11, 15))
        var addedWeek: String? = null
        var addedDay = -1
        compose.setContent {
            StarLiveTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    IdeasScreen(date, mondayOf(date), 0, {}, 6, {}, WeekPlan.empty(),
                        onAdd = { week, day, _, _ -> addedWeek = week; addedDay = day }, onPlanner = {})
                }
            }
        }
        compose.onNodeWithTag("idea_custom", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("把这份想法留在原来的星期日"))
        }
        compose.runOnIdle { date = LocalDate.of(2026, 11, 16) }
        compose.onNodeWithText("保存到草稿").performClick()
        assertEquals("2026-11-09", addedWeek)
        assertEquals(6, addedDay)
    }

    // Robolectric #8460 affects editing dialogs above 320dp; no product workaround.
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi")
    @Test fun editorCanModifyALongTitleAndKeepAnImportedCategory() {
        val original = "读".repeat(150)
        val replacement = original + "完"
        var savedTitle: String? = null
        var savedCategory: String? = null
        compose.setContent {
            StarLiveTheme {
                TaskEditorDialog(original, "导入的自定义类别", true, {},
                    onSave = { title, category -> savedTitle = title; savedCategory = category })
            }
        }
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString(replacement))
        }
        compose.onNodeWithText("保存到草稿").performClick()
        assertEquals(replacement, savedTitle)
        assertEquals("导入的自定义类别", savedCategory)
    }
}
