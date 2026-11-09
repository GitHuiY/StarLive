package io.github.githuiy.starlive

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.PlanEngine
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** Host-only UI flows. They never connect to a physical phone or grant app permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class StarLiveUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var opened = false

    @Before fun openFreshPersonalStore() {
        // Lifecycle's factory is process-global, while Robolectric replaces the
        // Application per test. Do not retain a previous test's files directory.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        val app = ApplicationProvider.getApplicationContext<Application>()
        // These are the isolated Robolectric app files, never real device files.
        app.filesDir.listFiles()?.filter { it.name.startsWith("starlive-") }?.forEach {
            check(it.delete()) { "Unable to reset isolated test data" }
        }
        println("UI fresh application directory=${app.filesDir.canonicalPath}; remaining=${app.filesDir.listFiles()?.map { it.name }}")
        launch()
        scenario.onActivity { activity ->
            assertEquals("Application and Activity must share the isolated files directory",
                app.filesDir.canonicalPath, activity.filesDir.canonicalPath)
            val model = ViewModelProvider(activity)[PlanViewModel::class.java]
            val modelApplication = model.getApplication<Application>()
            println("UI application identities: provider=${System.identityHashCode(app)}, activity=${System.identityHashCode(activity.application)}, model=${System.identityHashCode(modelApplication)}; modelDirectory=${modelApplication.filesDir.canonicalPath}")
            assertEquals("ViewModel factory retained a different test Application directory",
                app.filesDir.canonicalPath, modelApplication.filesDir.canonicalPath)
            val data = checkNotNull(model.data) { "UI reported ready before repository data arrived" }
            println("UI fresh model=${System.identityHashCode(model)}; weeks=${data.weeks.size}; drafts=${data.drafts.size}; error=${model.error}")
            assertTrue("Fresh test store already contains weeks: ${data.weeks.keys}; ${model.error}", data.weeks.isEmpty())
            assertTrue("Fresh test store already contains drafts: ${data.drafts.keys}; ${model.error}", data.drafts.isEmpty())
            assertEquals(60, data.settings.continuousMinutes)
            assertTrue("Fresh store has an error: ${model.error}", model.error == null)
            assertTrue("Fresh test reminders must all be enabled", data.settings.continuousEnabled
                    && data.settings.perAppDailyEnabled && data.settings.totalDailyEnabled)
        }
    }

    @After fun closeActivity() {
        if (opened) closeScenario()
    }

    private fun closeScenario() {
        val drained = AtomicBoolean(false)
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[PlanViewModel::class.java].repository.exportJson {
                // Success or failure both mean all earlier accepted work ran.
                drained.set(true)
            }
        }
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!drained.get() && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(5)
            }
            check(drained.get()) { "Storage queue did not drain before isolated Activity teardown" }
        } finally {
            scenario.close()
            opened = false
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        opened = true
        compose.waitUntil(15_000) { exists("overview") || exists("focused_sky") }
    }

    private fun exists(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun textExists(text: String): Boolean = runCatching {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun repositorySummary(): String {
        var summary = "No active Activity"
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[PlanViewModel::class.java]
            val settings = model.data?.settings
            val modelApplication = model.getApplication<Application>()
            summary = "model=${System.identityHashCode(model)}, directory=${activity.filesDir}, " +
                    "modelDirectory=${modelApplication.filesDir}, " +
                    "weeks=${model.data?.weeks?.keys}, drafts=${model.data?.drafts?.keys}, " +
                    "minutes=${settings?.continuousMinutes}/${settings?.perAppDailyMinutes}/${settings?.totalDailyMinutes}, " +
                    "enabled=${settings?.continuousEnabled}/${settings?.perAppDailyEnabled}/${settings?.totalDailyEnabled}, " +
                    "error=${model.error}, files=${activity.filesDir.listFiles()?.map { it.name + ':' + it.length() }}"
        }
        return summary
    }

    private fun awaitRepository(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                condition()
            }
        } catch (failure: Throwable) {
            throw AssertionError("$description; ${repositorySummary()}", failure)
        }
    }

    private fun contentClick(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
    }

    private fun goOverview() {
        compose.onNodeWithTag("nav_stars").performClick()
        compose.waitForIdle()
        if (exists("mini_map")) contentClick("mini_map")
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun selectMonday() {
        goOverview()
        contentClick("star_0")
        compose.waitUntil(5_000) { exists("day_panel") }
    }

    private fun screenshot(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        var rendered: Bitmap? = null
        scenario.onActivity { activity ->
            val decor = if (dialog) {
                val modal = checkNotNull(ShadowDialog.getLatestDialog()) { "Expected a visible preview dialog" }
                check(modal.isShowing)
                checkNotNull(modal.window).decorView
            } else activity.window.decorView
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

    @Test fun overviewCanStayAndDemoNeverWritesPersonalPlans() {
        goOverview()
        screenshot("01-overview.png")
        selectMonday()
        contentClick("mode_toggle")
        compose.waitUntil(5_000) { textExists("给房间一个小小整理") }
        compose.onNodeWithContentDescription("完成：给房间一个小小整理").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("取消完成：给房间一个小小整理").assertIsOn(); true }
                .getOrDefault(false)
        }
        scenario.onActivity {
            val data = ViewModelProvider(it)[PlanViewModel::class.java].data!!
            assertTrue("Demo changed personal weeks: ${data.weeks.keys}", data.weeks.isEmpty())
            assertTrue("Demo changed personal drafts: ${data.drafts.keys}", data.drafts.isEmpty())
        }
        contentClick("mode_toggle")
        compose.onAllNodesWithText("给房间一个小小整理").assertCountEquals(0)
        contentClick("mini_map")
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("day_panel", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("nav_ideas").performClick()
        screenshot("08-ideas.png")
        compose.onNodeWithTag("nav_stars").performClick()
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
    }

    // Robolectric #8460: >320dp Dialog + TextField can loop in layout. Keep the
    // product untouched; this editor flow uses 320dp, other visual cases use 360dp.
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun draftConfirmationAndCompletionSurviveActivityRecreationAndReload() {
        val title = "读十页书，让今天慢一点"
        selectMonday()
        compose.onNodeWithTag("open_planner").performClick()
        contentClick("planner_add")
        // Exercise the real TextField onValueChange without Robolectric's virtual
        // IME/focus loop. Physical keyboard behavior remains a device-test item.
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString(title))
        }
        compose.onNodeWithText("阅读").performScrollTo().performClick()
        compose.onNodeWithText("保存到草稿").performClick()
        compose.waitUntil(10_000) { textExists(title) }
        scenario.onActivity {
            val data = ViewModelProvider(it)[PlanViewModel::class.java].data!!
            assertTrue("Editing a draft must not confirm it", data.weeks.isEmpty())
        }
        compose.onNodeWithTag("nav_stars").performClick()
        compose.onAllNodesWithText(title).assertCountEquals(0)
        compose.onNodeWithTag("open_planner").performClick()
        compose.onNodeWithText(title).performScrollTo()
        screenshot("07-planner.png")
        contentClick("planner_confirm")
        compose.onNodeWithTag("planner_confirm_dialog", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { textExists(title) && exists("day_panel") }
        compose.onNodeWithContentDescription("完成：$title").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithContentDescription("取消完成：$title").assertIsOn(); true }
                .getOrDefault(false)
        }
        screenshot("02-personal-day.png")
        scenario.recreate()
        compose.waitUntil(10_000) { exists("day_panel") }
        closeScenario()
        launch()
        selectMonday()
        compose.onNodeWithContentDescription("取消完成：$title").performScrollTo().assertIsOn()
    }

    @Test fun importedBackupShowsPreviewAndDoesNotReplaceUntilConfirmed() {
        val today = LocalDate.now()
        val key = PlanEngine.weekKey(today)
        val engine = PlanEngine(today)
        val draft = engine.addDraftItem(AppState.empty(), key, 0, "备份中的一页书", "阅读")
        val json = BackupCodec.encode(engine.confirmDraft(draft, key))
        compose.onNodeWithTag("nav_space").performClick()
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[PlanViewModel::class.java]
            model.repository.prepareImport(json) { result ->
                result.onSuccess { model.backupPreview = it; model.restoring = false }
                    .onFailure { throw AssertionError(it) }
            }
        }
        compose.waitUntil(10_000) { exists("backup_confirm") }
        scenario.onActivity {
            assertTrue(ViewModelProvider(it)[PlanViewModel::class.java].data!!.weeks.isEmpty())
        }
        screenshot("03-backup-preview.png", dialog = true)
        compose.onNodeWithTag("backup_confirm", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { !exists("backup_confirm") }
        selectMonday()
        compose.waitUntil(10_000) { textExists("备份中的一页书") }
    }

    @Test fun screenTimeHasNoFakeUsageAndSettingsPersist() {
        compose.onNodeWithTag("nav_space").performClick()
        compose.onNodeWithText("当前没有可用的使用记录").assertIsDisplayed()
        val field = compose.onNodeWithTag("setting_continuous_minutes", useUnmergedTree = true)
        field.performScrollTo().performTextClearance()
        field.performTextInput("75")
        contentClick("setting_continuous_save")
        awaitRepository("Continuous threshold did not publish 75") { textExists("当前保存：75 分钟") }
        contentClick("setting_per_app_enabled")
        awaitRepository("Per-app switch did not publish disabled") {
            var disabled = false
            scenario.onActivity { disabled = !ViewModelProvider(it)[PlanViewModel::class.java].data!!.settings.perAppDailyEnabled }
            disabled
        }
        compose.onNodeWithText("抬起头，\n生活在这里。").performScrollTo()
        screenshot("04-screen-space.png")
        closeScenario()
        launch()
        compose.onNodeWithTag("nav_space").performClick()
        compose.waitUntil(10_000) { textExists("当前保存：75 分钟") }
        scenario.onActivity {
            val settings = ViewModelProvider(it)[PlanViewModel::class.java].data!!.settings
            assertEquals(75, settings.continuousMinutes)
            assertEquals(60, settings.perAppDailyMinutes)
            assertEquals(180, settings.totalDailyMinutes)
            assertTrue(!settings.perAppDailyEnabled)
        }
    }

    @Test fun rapidSwitchChangesBothPersistWithoutWaitingBetweenActions() {
        compose.onNodeWithTag("nav_space").performClick()
        contentClick("setting_continuous_enabled")
        contentClick("setting_per_app_enabled")
        awaitRepository("Rapid field intents did not both publish") {
            var persisted = false
            scenario.onActivity {
                val settings = ViewModelProvider(it)[PlanViewModel::class.java].data!!.settings
                persisted = !settings.continuousEnabled && !settings.perAppDailyEnabled
                        && settings.totalDailyEnabled
            }
            persisted
        }
        closeScenario()
        launch()
        scenario.onActivity {
            val settings = ViewModelProvider(it)[PlanViewModel::class.java].data!!.settings
            assertTrue(!settings.continuousEnabled && !settings.perAppDailyEnabled)
            assertTrue(settings.totalDailyEnabled)
        }
    }
}
