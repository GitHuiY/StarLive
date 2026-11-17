package io.github.githuiy.starlive

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertValueEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.PlanEngine
import io.github.githuiy.starlive.ui.StarNames
import java.io.File
import java.time.LocalDate
import java.time.DayOfWeek
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
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        // Skip platform video during the initial host-only launch. Subsequent
        // gestures still exercise the real Compose animations.
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        opened = true
        compose.waitUntil(15_000) { exists("overview") || exists("focused_sky") }
        compose.waitForIdle()
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
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

    private fun sparksDateClick(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().performClick()
    }

    private fun chooseSparksWeek(monday: String) {
        sparksDateClick("sparks_week_picker")
        contentClick("sparks_week_$monday")
    }

    private fun goOverview() {
        compose.onNodeWithTag("nav_stars").performClick()
        compose.waitForIdle()
        if (exists("focused_sky")) tapBlankSky()
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun tapBlankSky() {
        compose.onNodeWithTag("sky_background", useUnmergedTree = true).performScrollTo()
            .performTouchInput { click(Offset(width * 0.08f, height * 0.1f)) }
    }

    private fun tapBlankSparksSky() {
        compose.onNodeWithTag("sparks_sky_background", useUnmergedTree = true).assertIsDisplayed()
            .performTouchInput { click(Offset(width * 0.08f, height * 0.1f)) }
    }

    private fun openMondayPlannerFromSparks() {
        compose.onNodeWithTag("nav_ideas").performClick()
        sparksDateClick("sparks_star_0")
        sparksDateClick("sparks_selected_date")
        sparksDateClick("open_planner")
    }

    private fun tapOutsideDetail() {
        scenario.onActivity {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog())
            check(dialog.isShowing)
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try {
                dialog.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun selectMonday() {
        goOverview()
        tapMondayStar()
        compose.waitUntil(5_000) { exists("day_panel") }
    }

    private fun tapMondayStar() {
        compose.onNodeWithTag("star_0", useUnmergedTree = true).assertIsDisplayed()
            .performTouchInput { click() }
    }

    private fun mondayItemId(week: String, title: String): String {
        var id: String? = null
        scenario.onActivity { activity ->
            val data = checkNotNull(ViewModelProvider(activity)[PlanViewModel::class.java].data)
            id = PlanEngine.confirmed(data, week).days[0].single { it.title == title }.id
        }
        return checkNotNull(id)
    }

    private fun planCard(id: String): SemanticsNodeInteraction {
        return compose.onNodeWithTag("plan_$id", useUnmergedTree = true)
    }

    private fun companion(id: String): SemanticsNodeInteraction {
        return compose.onNodeWithTag("companion_$id", useUnmergedTree = true)
    }

    private fun holdAndDrag(card: SemanticsNodeInteraction, destinationInRoot: Offset) {
        val destination = destinationInRoot - card.fetchSemanticsNode().boundsInRoot.topLeft
        card.performTouchInput {
            down(center)
            advanceEventTime(1_600)
            for (step in 0..8) {
                moveTo(center + (destination - center) * (step / 8f), delayMillis = 32)
            }
            up()
        }
    }

    private fun assertCardAnimationPrecedesDetail(card: SemanticsNodeInteraction,
        detail: SemanticsNodeInteraction) {
        compose.mainClock.autoAdvance = false
        try {
            card.performTouchInput { click() }
            compose.mainClock.advanceTimeBy(80)
            detail.assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        detail.assertIsDisplayed()
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

    @Test fun startupFocusesTodayExceptOnMondayAndBlankSkyStaysInOverview() {
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_500)
        val day = LocalDate.now().dayOfWeek
        if (day == DayOfWeek.MONDAY) {
            compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
            tapMondayStar()
        }
        compose.waitUntil(5_000) { exists("focused_sky") && exists("day_panel") }
        if (day != DayOfWeek.MONDAY) {
            compose.onNodeWithText(StarNames[day.value - 1]).assertIsDisplayed()
        }
        tapBlankSky()
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
        screenshot("01-overview.png")
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("day_panel", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("nav_ideas").performClick()
        compose.onNodeWithTag("nav_stars").performClick()
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun focusedSkyUsesTwoColumnsAndOnlyCompletesWhenDraggedToItsStar() {
        val monday = LocalDate.parse(PlanEngine.weekKey(LocalDate.now()))
        val completed = "读十页书"
        val unfinished = "散步十五分钟"
        val body = "读完一章，记下喜欢的一句话。"
        scenario.onActivity { activity ->
            val repository = ViewModelProvider(activity)[PlanViewModel::class.java].repository
            repository.addPlan(monday.toString(), 0, completed, "阅读", body)
            repository.addPlan(monday.toString(), 0, unfinished, "运动", "")
        }
        selectMonday()
        compose.waitUntil(10_000) { textExists(completed) && textExists(unfinished) }
        val completedId = mondayItemId(monday.toString(), completed)
        val first = planCard(completedId).performScrollTo().assertValueEquals("未完成")
        val second = planCard(mondayItemId(monday.toString(), unfinished)).assertValueEquals("未完成")
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val secondBounds = second.fetchSemanticsNode().boundsInRoot
        assertEquals(firstBounds.top, secondBounds.top, 1f)
        assertEquals(firstBounds.bottom, secondBounds.bottom, 1f)
        assertTrue(firstBounds.right <= secondBounds.left)
        screenshot("02-personal-day.png")

        val detail = compose.onNodeWithTag("plan_detail", useUnmergedTree = true)
        assertCardAnimationPrecedesDetail(first, detail)
        compose.onNodeWithText(body).assertIsDisplayed()
        screenshot("10-plan-detail.png", dialog = true)
        compose.onNodeWithText("关闭").performClick()
        first.assertValueEquals("未完成")

        first.performTouchInput {
            down(center)
            advanceEventTime(1_600)
            up()
        }
        detail.assertDoesNotExist()
        first.assertValueEquals("未完成")
        companion(completedId).assertDoesNotExist()

        val completedCompanion = companion(completedId)
        val sky = compose.onNodeWithTag("sky_background", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        holdAndDrag(first, sky.topLeft + Offset(sky.width * .08f, sky.height * .1f))
        first.assertValueEquals("未完成")
        completedCompanion.assertDoesNotExist()

        val primary = compose.onNodeWithTag("primary_star", useUnmergedTree = true).assertIsDisplayed()
        val primaryBounds = primary.fetchSemanticsNode().boundsInRoot
        holdAndDrag(first, primaryBounds.center)
        awaitRepository("Dragging the plan onto the primary star did not create a completed companion") {
            runCatching { completedCompanion.assertValueEquals("已完成"); true }.getOrDefault(false)
        }
        first.assertDoesNotExist()
        detail.assertDoesNotExist()
        second.assertValueEquals("未完成")
        val date = hasText("${monday.monthValue} 月 ${monday.dayOfMonth} 日", substring = true)
        val dayPanel = hasAnyAncestor(hasTestTag("day_panel"))
        val focusedSky = hasAnyAncestor(hasTestTag("focused_sky"))
        compose.onNode(date and dayPanel, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(completed) and dayPanel, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText(unfinished) and dayPanel, useUnmergedTree = true).assertExists()
        compose.onAllNodes((date or hasText(completed) or hasText(unfinished)) and focusedSky,
            useUnmergedTree = true).assertCountEquals(0)
        screenshot("11-companion-star.png")

        val companionAtRest = completedCompanion.fetchSemanticsNode().boundsInRoot.center
        completedCompanion.performClick()
        compose.waitUntil(5_000) { exists("plan_detail") }
        detail.assertIsDisplayed()
        compose.onNodeWithText(body).assertIsDisplayed()
        val companionFocused = completedCompanion.fetchSemanticsNode().boundsInRoot.center
        assertTrue((companionFocused - primaryBounds.center).getDistance()
            < (companionAtRest - primaryBounds.center).getDistance())
        tapOutsideDetail()
        detail.assertDoesNotExist()
        completedCompanion.assertIsDisplayed()
        val primaryAtRest = primary.fetchSemanticsNode().boundsInRoot.center
        assertEquals(primaryBounds.center.x, primaryAtRest.x, 1f)
        assertEquals(primaryBounds.center.y, primaryAtRest.y, 1f)
        tapBlankSky()
        compose.onNodeWithTag("overview", useUnmergedTree = true).assertIsDisplayed()
        completedCompanion.assertDoesNotExist()
    }

    // Robolectric #8460 affects both dialogs that contain a text field.
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun ideasOpenInDetailAndAddTheirBodyDirectlyToPlans() {
        val title = "给房间一个小小整理"
        val body = "从桌面开始。\n把不用的东西放回原处。"
        val targetWeek = PlanEngine.weekKey(LocalDate.now().plusWeeks(4))
        val targetDay = 2
        compose.onNodeWithTag("nav_ideas").performClick()
        compose.onNodeWithTag("idea_card_0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("idea_open_draft", useUnmergedTree = true).assertDoesNotExist()
        chooseSparksWeek(targetWeek)
        sparksDateClick("sparks_star_$targetDay")
        compose.onNodeWithTag("sparks_selected_date", useUnmergedTree = true).assertIsDisplayed()
        val first = compose.onNodeWithTag("idea_card_0", useUnmergedTree = true)
        val second = compose.onNodeWithTag("idea_card_1", useUnmergedTree = true)
        val firstBounds = first.performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val secondBounds = second.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(firstBounds.left, secondBounds.left, 1f)
        assertTrue(firstBounds.bottom <= secondBounds.top)
        compose.onNodeWithTag("idea_category_家务", useUnmergedTree = true).assertExists()
        compose.onNode((hasText("家\n务") or hasText("家务")) and hasAnyAncestor(hasTestTag("idea_card_0")),
            useUnmergedTree = true).assertDoesNotExist()
        screenshot("08-ideas.png")

        val detail = compose.onNodeWithTag("idea_detail", useUnmergedTree = true)
        assertCardAnimationPrecedesDetail(first, detail)
        compose.onNodeWithTag("idea_body", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(body)) }
        val add = compose.onNodeWithTag("idea_add_0", useUnmergedTree = true)
        add.assertIsDisplayed()
        screenshot("09-idea-detail.png", dialog = true)
        add.performClick()
        compose.waitUntil(5_000) { !exists("idea_detail") }
        awaitRepository("The selected idea and its body did not persist as a plan") {
            var added = false
            scenario.onActivity { activity ->
                val data = ViewModelProvider(activity)[PlanViewModel::class.java].data!!
                assertTrue("Adding an idea created a draft", data.drafts.isEmpty())
                added = data.weeks[targetWeek]?.days?.get(targetDay)?.any {
                    it.title == title && it.body == body
                } == true
            }
            added
        }
        sparksDateClick("sparks_selected_date")
        compose.onNodeWithTag("sparks_configured_plans", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(body).assertIsDisplayed()
    }

    // The custom plan editor contains text fields, so use the same Robolectric width workaround.
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun sparksReturnsToTheChosenWeekAndUsesTheSelectedCategoryForCustomPlans() {
        val title = "留给下周日的小计划"
        val category = "运动"
        val today = LocalDate.now()
        val day = 6
        val nextWeek = PlanEngine.weekKey(today.plusWeeks(1))

        compose.onNodeWithTag("nav_ideas").performClick()
        sparksDateClick("sparks_star_$day")
        compose.onNodeWithTag("sparks_show_overview", useUnmergedTree = true).assertDoesNotExist()
        tapBlankSparksSky()
        chooseSparksWeek(nextWeek)
        compose.onNodeWithTag("idea_custom", useUnmergedTree = true).assertDoesNotExist()
        sparksDateClick("sparks_star_$day")
        val categoryNode = compose.onNodeWithTag("idea_category_$category", useUnmergedTree = true)
        categoryNode.performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("散步，看看路边的树").assertIsDisplayed()
        compose.onNodeWithText("给房间一个小小整理").assertDoesNotExist()
        sparksDateClick("idea_custom")
        compose.onNodeWithTag("plan_category_fixed").assertIsDisplayed().assertTextContains(category, substring = true)
        compose.onNodeWithTag("plan_category_picker", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and !hasTestTag("plan_body"), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(title)) }
        compose.onNodeWithTag("plan_editor_save", useUnmergedTree = true).performClick()
        awaitRepository("The custom plan did not land in next Sunday's plans") {
            var added = false
            scenario.onActivity { activity ->
                val data = checkNotNull(ViewModelProvider(activity)[PlanViewModel::class.java].data)
                added = data.weeks[nextWeek]?.days?.get(day)?.any {
                    it.title == title && it.category == category
                } == true
            }
            added
        }

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("sparks_star_$day", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("idea_custom", useUnmergedTree = true).assertDoesNotExist()

        sparksDateClick("sparks_star_0")
        tapBlankSparksSky()
        compose.onNodeWithTag("sparks_star_0", useUnmergedTree = true).assertIsDisplayed()

        sparksDateClick("sparks_star_0")
        compose.onNodeWithTag("nav_space").performClick()
        compose.onNodeWithTag("nav_ideas").performClick()
        compose.onNodeWithTag("sparks_star_0", useUnmergedTree = true).assertIsDisplayed()
        sparksDateClick("sparks_week_picker")
        compose.onNodeWithTag("sparks_week_$nextWeek", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("idea_custom", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun sparksMatchesStarmapStarPositionsInBothViews() {
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        goOverview()
        val homeStars = (0..6).map { bounds("star_$it").center }
        tapMondayStar()
        val homePrimary = bounds("primary_star").center
        val homeFocusedSky = bounds("sky_background")
        compose.onNodeWithTag("nav_ideas").performClick()
        val sparksStars = (0..6).map { bounds("sparks_star_$it").center }
        homeStars.zip(sparksStars).forEachIndexed { day, (home, sparks) ->
            assertEquals("Star $day x", home.x, sparks.x, 1f)
            assertEquals("Star $day y", home.y, sparks.y, 1f)
        }
        sparksDateClick("sparks_star_0")
        val sparksPrimary = bounds("sparks_selected_date").center
        assertEquals(homePrimary.x, sparksPrimary.x, 1f)
        assertEquals(homePrimary.y, sparksPrimary.y, 1f)
        assertEquals(homeFocusedSky, bounds("sparks_sky_background"))
    }

    @Test fun focusedSparksStarSwitchesBetweenRecommendationsAndPlansForItsDate() {
        val week = PlanEngine.weekKey(LocalDate.now().plusWeeks(4))
        val selectedDay = 2
        val date = LocalDate.parse(week).plusDays(selectedDay.toLong())
        val targetTitle = "只在选中的星期三读书"
        val neighboringTitle = "隔天的计划"
        scenario.onActivity { activity ->
            val repository = ViewModelProvider(activity)[PlanViewModel::class.java].repository
            repository.addPlan(week, selectedDay, targetTitle, "阅读", "")
            repository.addPlan(week, selectedDay + 1, neighboringTitle, "阅读", "")
        }
        awaitRepository("The future plans did not load") {
            var loaded = false
            scenario.onActivity { activity ->
                loaded = ViewModelProvider(activity)[PlanViewModel::class.java].data?.weeks?.get(week)
                    ?.days?.get(selectedDay + 1)?.any { it.title == neighboringTitle } == true
            }
            loaded
        }

        compose.onNodeWithTag("nav_ideas").performClick()
        chooseSparksWeek(week)
        sparksDateClick("sparks_star_$selectedDay")
        val configured = compose.onNodeWithTag("sparks_configured_plans", useUnmergedTree = true)
        val categories = compose.onNodeWithTag("idea_categories", useUnmergedTree = true)
        val custom = compose.onNodeWithTag("idea_custom", useUnmergedTree = true)
        val hint = compose.onNodeWithTag("sparks_focus_hint", useUnmergedTree = true).assertIsDisplayed()
        val picker = compose.onNodeWithTag("sparks_week_picker", useUnmergedTree = true)
        hint.assertTextEquals("点击星星，查看当天计划")
        picker.assertDoesNotExist()
        custom.assertIsDisplayed()
        configured.assertDoesNotExist()
        sparksDateClick("sparks_selected_date")
        configured.assertIsDisplayed()
        picker.assertIsDisplayed()
        hint.assertTextEquals("点击星星，返回备选计划")
        categories.assertDoesNotExist()
        custom.assertDoesNotExist()
        compose.onNodeWithTag("sparks_configured_date", useUnmergedTree = true)
            .assertTextContains("${date.monthValue} 月 ${date.dayOfMonth} 日", substring = true)
        compose.onNodeWithText(targetTitle).assertIsDisplayed()
        compose.onNodeWithText(neighboringTitle).assertDoesNotExist()
        compose.onNodeWithTag("open_planner", useUnmergedTree = true).assertDoesNotExist()

        sparksDateClick("sparks_selected_date")
        custom.assertIsDisplayed()
        configured.assertDoesNotExist()
        tapBlankSparksSky()
        chooseSparksWeek(PlanEngine.weekKey(LocalDate.now()))
        sparksDateClick("sparks_star_$selectedDay")
        sparksDateClick("sparks_selected_date")
        compose.onNodeWithTag("sparks_configured_empty", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("open_planner", useUnmergedTree = true).assertExists()
        chooseSparksWeek(week)
        configured.assertDoesNotExist()
        sparksDateClick("sparks_star_$selectedDay")
        custom.assertIsDisplayed()
    }

    // The edit dialog's text fields need the same Robolectric width workaround as the add dialog.
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun configuredSparksPlanCanBeEditedAndDeletedInPlace() {
        val week = PlanEngine.weekKey(LocalDate.now())
        val original = "留一段时间阅读"
        val updated = "读完这一章"
        val body = "从上次的书签继续读。"
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[PlanViewModel::class.java].repository.addPlan(week, 0, original, "阅读", "")
        }
        awaitRepository("The plan did not load") {
            var loaded = false
            scenario.onActivity { activity ->
                loaded = ViewModelProvider(activity)[PlanViewModel::class.java].data?.weeks?.get(week)
                    ?.days?.get(0)?.any { it.title == original } == true
            }
            loaded
        }
        val id = mondayItemId(week, original)
        compose.onNodeWithTag("nav_ideas").performClick()
        sparksDateClick("sparks_star_0")
        sparksDateClick("sparks_selected_date")
        val row = compose.onNodeWithTag("sparks_plan_$id", useUnmergedTree = true)
        row.assertIsDisplayed().performClick()
        val editor = compose.onNodeWithTag("plan_editor", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and !hasTestTag("plan_body"), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(updated)) }
        compose.onNodeWithTag("plan_body", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(body)) }
        compose.onNodeWithTag("plan_editor_save", useUnmergedTree = true).performClick()
        editor.assertDoesNotExist()
        compose.waitUntil(10_000) { textExists(updated) && !textExists(original) }
        compose.onNodeWithText(body).assertIsDisplayed()
        row.assertIsDisplayed().performClick()
        compose.onNodeWithTag("plan_editor_delete", useUnmergedTree = true).performClick()
        val confirm = compose.onNodeWithTag("plan_delete_confirm", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("保留").performClick()
        editor.assertIsDisplayed()
        compose.onNodeWithTag("plan_editor_delete", useUnmergedTree = true).performClick()
        confirm.assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { !exists("sparks_plan_$id") }
        compose.onNodeWithTag("sparks_configured_empty", useUnmergedTree = true).assertIsDisplayed()
        selectMonday()
        planCard(id).assertDoesNotExist()
    }

    @Test fun withinUsesItsOwnStarsAndTheGlobalMusicControl() {
        compose.onNodeWithTag("open_planner", useUnmergedTree = true).assertDoesNotExist()
        val music = compose.onNodeWithTag("music_toggle", useUnmergedTree = true)
        music.assertIsDisplayed().assertHasClickAction().assertContentDescriptionContains("播放背景音乐")
        val indicator = compose.onNodeWithTag("music_playing_indicator", useUnmergedTree = true)
        indicator.assertDoesNotExist()
        goOverview()
        compose.onNodeWithTag("star_0", useUnmergedTree = true).assertIsDisplayed()
        val ability = compose.onNodeWithTag("nav_ability")
        ability.assertIsDisplayed().performClick().assertIsSelected()
        val content = compose.onNodeWithTag("within_essays", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("within_gemini_sky", useUnmergedTree = true)
            .assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("star_0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("within_star_SELF", useUnmergedTree = true)
            .assertHasClickAction().performClick()
        compose.onNodeWithTag("within_focused_star", useUnmergedTree = true).assertIsDisplayed()
        music.assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("nav_space").performClick().assertIsSelected()
        content.assertDoesNotExist()
        compose.onNodeWithTag("nav_stars").performClick().assertIsSelected()
        compose.onNodeWithTag("star_0", useUnmergedTree = true).assertIsDisplayed()
    }

    // Robolectric #8460: >320dp Dialog + TextField can loop in layout. Keep the
    // product untouched; this editor flow uses 320dp, other visual cases use 360dp.
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun weeklyEditorSavesImmediatelyAndHomeCompletionPersistsAfterReload() {
        val week = PlanEngine.weekKey(LocalDate.now())
        val title = "读十页书，让今天慢一点"
        val body = "把手机放在旁边，翻开书。"
        openMondayPlannerFromSparks()
        contentClick("planner_add")
        // Exercise the real TextField onValueChange without Robolectric's virtual
        // IME/focus loop. Physical keyboard behavior remains a device-test item.
        compose.onNode(hasSetTextAction() and !hasTestTag("plan_body"), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(title)) }
        compose.onNodeWithTag("plan_body", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(body)) }
        compose.onNodeWithText("阅读").performScrollTo().performClick()
        compose.onNodeWithTag("plan_editor_save", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { textExists(title) }
        scenario.onActivity {
            val data = ViewModelProvider(it)[PlanViewModel::class.java].data!!
            assertTrue("Saving a plan should populate the selected day immediately",
                data.weeks[week]?.days?.get(0)?.any { it.title == title && it.body == body } == true)
            assertTrue(data.drafts.isEmpty())
        }
        compose.onNodeWithText(title).performScrollTo()
        screenshot("07-planner.png")
        compose.onNodeWithTag("planner_confirm", useUnmergedTree = true).assertDoesNotExist()
        selectMonday()
        compose.waitUntil(10_000) { textExists(title) && exists("day_panel") }
        val id = mondayItemId(week, title)
        val card = planCard(id).performScrollTo()
        card.performSemanticsAction(SemanticsActions.OnLongClick)
        awaitRepository("Accessible completion action did not persist") {
            runCatching { companion(id).assertValueEquals("已完成"); true }.getOrDefault(false)
        }
        card.assertDoesNotExist()
        scenario.recreate()
        compose.waitUntil(10_000) { exists("day_panel") }
        closeScenario()
        launch()
        selectMonday()
        planCard(id).assertDoesNotExist()
        companion(id).performScrollTo().assertValueEquals("已完成").performClick()
        compose.waitUntil(5_000) { exists("plan_detail") }
        compose.onNodeWithText(body).assertIsDisplayed()
    }

    @Test fun importedBackupShowsPreviewAndDoesNotReplaceUntilConfirmed() {
        val today = LocalDate.now()
        val key = PlanEngine.weekKey(today)
        val engine = PlanEngine(today)
        val saved = engine.addPlan(AppState.empty(), key, 0, "备份中的一页书", "阅读", "")
        val json = BackupCodec.encode(saved)
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
        compose.onNodeWithText("屏幕留白").assertDoesNotExist()
        compose.onNodeWithText("当前没有可用的使用记录").assertIsDisplayed()
        screenshot("04-screen-space.png")
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
