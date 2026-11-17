package io.github.githuiy.starlive

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h800dp-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WithinUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() {
        scenario?.close()
    }

    @Test fun sixStarsStayReachableAndFocusReturnsOnNarrowScreenWithLargerText() {
        RuntimeEnvironment.setFontScale(1.6f)
        openWithin()
        val music = node("music_toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val nav = node("nav_ability").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val labels = mapOf("SELF" to "自我", "WORLD" to "世界", "MOMENT" to "瞬间",
            "ECHO" to "回响", "FOOTPRINT" to "足迹", "BEYOND" to "远方")
        labels.forEach { (name, label) ->
            list().performScrollToIndex(0)
            node("within_gemini_sky").assertIsDisplayed()
            val star = node("within_star_$name").assertIsDisplayed().assertHasClickAction()
            val bounds = star.fetchSemanticsNode().boundsInRoot
            assertTrue("star $name must be below the top controls", bounds.top >= music.bottom)
            assertTrue("star $name must stay above the bottom navigation", bounds.bottom <= nav.top)
            star.performClick()
            node("within_focused_star").assertIsDisplayed()
            listNode("within_list_title").assertTextContains(label)
            listNode("within_empty").assertIsDisplayed()
            listNode("within_all_stars").performClick()
            listNode("within_list_title").assertTextContains("全部随笔")
        }
        node("star_0").assertDoesNotExist()
        node("music_toggle").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "zh-rCN-w800dp-h360dp-land-xxxhdpi", shadows = [PosixAtomicFileShadow::class])
    @Test fun shortLandscapeShowsBothEndsOfSkyAndCanScrollToEssays() {
        openWithin()
        list().performScrollToIndex(0)
        for (name in listOf("SELF", "WORLD", "MOMENT", "ECHO", "FOOTPRINT", "BEYOND")) {
            node("within_star_$name").assertIsDisplayed().assertHasClickAction()
        }
        listNode("within_add").assertIsDisplayed()
        listNode("within_empty").assertIsDisplayed()
        node("music_toggle").assertIsDisplayed()
        node("nav_ability").assertIsDisplayed()
    }

    @Test fun essaysCanBeUnclassifiedOrMultiCategoryEditedReopenedAndDeleted() {
        openWithin()
        add()
        node("within_editor_save").assertIsNotEnabled()
        node("within_editor_category_SELF").reveal().assertIsNotSelected()
        editorText("body", "验收 · 无分类")
        node("within_editor_save").performClick()
        waitForEditorToClose()
        preview("验收 · 无分类").assertIsDisplayed()

        selectStar("WORLD")
        listNode("within_empty").assertIsDisplayed()
        add()
        node("within_editor_category_WORLD").reveal().assertIsSelected()
        node("within_editor_category_MOMENT").reveal().performClick().assertIsSelected()
        editorText("body", "验收 · 两个分类")
        editorText("date", "2026-02-30")
        node("within_editor_save").performClick()
        node("within_editor_date_error").reveal().assertIsDisplayed()
        editorText("date", "2026-11-15")
        node("within_editor_save").performClick()
        waitForEditorToClose()
        preview("验收 · 两个分类").assertIsDisplayed()

        selectAll()
        selectStar("MOMENT")
        openEssay("验收 · 两个分类")
        node("within_essay_body").assertTextContains("验收 · 两个分类")
        node("within_edit").performClick()
        editorText("body", "验收 · 放弃的修改")
        node("within_editor_cancel").performClick()
        node("within_editor_discard").performClick()
        waitForEditorToClose()
        node("within_essay_body").assertTextContains("验收 · 两个分类")

        node("within_edit").performClick()
        node("within_editor_category_WORLD").reveal().assertIsSelected().performClick().assertIsNotSelected()
        editorText("body", "验收 · 已编辑")
        node("within_editor_save").performClick()
        waitForEditorToClose()
        node("within_essay_body").assertTextContains("验收 · 已编辑")
        node("within_reader_back").performClick()
        selectAll()
        selectStar("WORLD")
        listNode("within_empty").assertIsDisplayed()
        checkNotNull(scenario).onActivity { it.onBackPressedDispatcher.onBackPressed() }
        listNode("within_list_title").assertTextContains("全部随笔")
        preview("验收 · 已编辑").assertIsDisplayed()
        compose.onAllNodesWithText("验收 · 已编辑", useUnmergedTree = true).assertCountEquals(1)

        checkNotNull(scenario).close()
        scenario = null
        openWithin()
        openEssay("验收 · 已编辑")
        node("within_remove").performClick()
        node("within_delete_cancel").performClick()
        node("within_essay_body").assertTextContains("验收 · 已编辑")
        node("within_remove").performClick()
        node("within_delete_confirm").performClick()
        compose.waitUntil(10_000) { !hasTag("within_reader") }
        preview("验收 · 无分类").assertIsDisplayed()
        compose.onAllNodesWithText("验收 · 已编辑", useUnmergedTree = true).assertCountEquals(0)
    }

    private fun openWithin() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The framework caches this factory across Robolectric application instances.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { hasTag("nav_ability") }
        compose.onNodeWithTag("nav_ability").performClick().assertIsSelected().assertTextContains("Within")
        listNode("within_list_title").assertTextContains("全部随笔")
        listNode("within_add")
        compose.waitUntil(10_000) {
            runCatching { !node("within_add").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
                .getOrDefault(false)
        }
    }

    private fun add() {
        listNode("within_add").performClick()
        compose.waitUntil(10_000) { hasTag("within_editor") }
    }

    private fun selectStar(name: String) {
        list().performScrollToIndex(0)
        node("within_star_$name").performClick()
        node("within_focused_star").assertIsDisplayed()
    }

    private fun selectAll() {
        listNode("within_all_stars").performClick()
        listNode("within_list_title").assertTextContains("全部随笔")
    }

    private fun openEssay(text: String) {
        list().performScrollToNode(hasText(text))
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("within_essays"))).performClick()
        node("within_reader").assertIsDisplayed()
    }

    private fun preview(text: String): SemanticsNodeInteraction {
        list().performScrollToNode(hasText(text))
        return compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("within_essays"))).reveal()
    }

    private fun editorText(field: String, text: String) {
        node("within_editor_$field").reveal().performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString(text))
        }
    }

    private fun list() = node("within_essays")
    private fun listNode(tag: String): SemanticsNodeInteraction {
        list().performScrollToNode(hasTestTag(tag))
        return node(tag).reveal()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction =
        if (isDisplayed()) this else performScrollTo()

    private fun hasTag(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForEditorToClose() = compose.waitUntil(10_000) { !hasTag("within_editor") }
}
